using InTheHand.Net;
using InTheHand.Net.Sockets;
using System.Text;

namespace PocketPadModes;

internal sealed class ModeControlServer : IDisposable
{
    private readonly Bridge bridge;
    private readonly CancellationTokenSource stop = new();
    private readonly object gate = new();
    private BluetoothListener? listener;
    private BluetoothClient? activeClient;
    private Task worker = Task.CompletedTask;
    internal event Action<bool, string>? StateChanged;
    internal ModeControlServer(Bridge bridge) => this.bridge = bridge;
    internal void Start() => worker = Task.Factory.StartNew(Run, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
    private void Run()
    {
        while (!stop.IsCancellationRequested)
        {
            BluetoothListener? current = null;
            try
            {
                current = new BluetoothListener(ModeProtocol.Service) { ServiceName = "PocketPad Mode Control" };
                lock (gate) listener = current;
                current.Start();
                StateChanged?.Invoke(true, "Bluetooth: gotowy na wybór trybu z telefonu.");
                while (!stop.IsCancellationRequested)
                {
                    using var client = current.AcceptBluetoothClient();
                    lock (gate) activeClient = client;
                    try { Handle(client); }
                    catch (Exception) when (!stop.IsCancellationRequested) { /* A dropped phone request must not stop the listener. */ }
                    finally { bridge.Presence.Disconnect(); lock (gate) activeClient = null; }
                }
            }
            catch (Exception ex) when (!stop.IsCancellationRequested)
            {
                StateChanged?.Invoke(false, "Bluetooth: " + ex.Message);
                stop.Token.WaitHandle.WaitOne(3000);
            }
            finally
            {
                try { current?.Stop(); } catch { }
                current?.Dispose();
                lock (gate) { if (listener == current) listener = null; }
            }
        }
    }
    private void Handle(BluetoothClient client)
    {
        // A mode request is accepted only from a device already paired with this PC.
        if (client.Client.RemoteEndPoint is not BluetoothEndPoint remote || !new BluetoothDeviceInfo(remote.Address).Authenticated) return;
        var stream = client.GetStream();
        stream.ReadTimeout = 5000; stream.WriteTimeout = 3000;
        bool selected = false;
        while (!stop.IsCancellationRequested)
        {
        var command = new StringBuilder();
        bool terminated = false;
        for (int i = 0; i < 64; i++)
        {
            int next = stream.ReadByte();
            if (next < 0) return;
            if (next == '\n') { terminated = true; break; }
            if (next != '\r') command.Append((char)next);
        }
        string response;
        if (terminated && command.ToString() == "POCKETPAD/1 ALIVE" && selected)
        { bridge.Presence.Touch(); response = "OK ALIVE"; }
        else if (terminated && ModeProtocol.TryParse(command.ToString(), out bool xbox))
        {
            bridge.Presence.Touch();
            response = bridge.ApplyModeAsync(xbox, stop.Token).GetAwaiter().GetResult();
            selected = response.StartsWith("OK ", StringComparison.Ordinal);
        }
        else response = "ERROR INVALID_COMMAND";
        var bytes = Encoding.ASCII.GetBytes(response + "\n");
        stream.Write(bytes, 0, bytes.Length); stream.Flush();
        if (!selected) return;
        }
    }
    internal async Task StopAsync()
    {
        stop.Cancel();
        lock (gate) { try { activeClient?.Close(); } catch { } try { listener?.Stop(); } catch { } }
        try { await worker; } catch (Exception) when (stop.IsCancellationRequested) { }
    }
    public void Dispose() => stop.Dispose();
}
