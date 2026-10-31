using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace PocketPadModes;

internal readonly record struct XboxSample(ushort Buttons, byte LT, byte RT, short LX, short LY, short RX, short RY);

internal static class PadMapping
{
    internal static XboxSample Convert(PadSample input)
    {
        if (!input.Connected) return default;
        ushort buttons = 0;
        void Map(int bit, ushort value) { if ((input.Buttons & (1 << bit)) != 0) buttons |= value; }
        Map(0, 0x1000); Map(1, 0x2000); Map(3, 0x4000); Map(4, 0x8000);
        Map(6, 0x100); Map(7, 0x200); Map(10, 0x20); Map(11, 0x10); Map(13, 0x40); Map(14, 0x80);
        if ((input.Hat & 1) != 0) buttons |= 1;
        if ((input.Hat & 4) != 0) buttons |= 2;
        if ((input.Hat & 8) != 0) buttons |= 4;
        if ((input.Hat & 2) != 0) buttons |= 8;
        byte Trigger(int bit) => (byte)((input.Buttons & (1 << bit)) != 0 ? 255 : 0);
        short Axis(short value, bool invert = false)
        {
            if (Math.Abs((int)value) < 2600) return 0;
            return (short)Math.Clamp(invert ? -(int)value : value, short.MinValue, short.MaxValue);
        }
        return new(buttons, Trigger(8), Trigger(9), Axis(input.LX), Axis(input.LY, true), Axis(input.RX), Axis(input.RY, true));
    }
}

internal sealed class XboxOutput : IDisposable
{
    private readonly ViGEmClient client;
    private readonly IXbox360Controller pad;
    private bool connected;
    internal XboxOutput()
    {
        client = new ViGEmClient();
        try
        {
            pad = client.CreateXbox360Controller();
            pad.AutoSubmitReport = false;
            pad.Connect();
            connected = true;
            Send(default);
        }
        catch { client.Dispose(); throw; }
    }
    internal void Send(XboxSample state)
    {
        var buttons = new[] { Xbox360Button.Up, Xbox360Button.Down, Xbox360Button.Left, Xbox360Button.Right,
            Xbox360Button.Start, Xbox360Button.Back, Xbox360Button.LeftThumb, Xbox360Button.RightThumb,
            Xbox360Button.LeftShoulder, Xbox360Button.RightShoulder, Xbox360Button.Guide,
            Xbox360Button.A, Xbox360Button.B, Xbox360Button.X, Xbox360Button.Y };
        var masks = new ushort[] { 1, 2, 4, 8, 0x10, 0x20, 0x40, 0x80, 0x100, 0x200, 0x400, 0x1000, 0x2000, 0x4000, 0x8000 };
        for (int i = 0; i < buttons.Length; i++) pad.SetButtonState(buttons[i], (state.Buttons & masks[i]) != 0);
        pad.SetAxisValue(Xbox360Axis.LeftThumbX, state.LX);
        pad.SetAxisValue(Xbox360Axis.LeftThumbY, state.LY);
        pad.SetAxisValue(Xbox360Axis.RightThumbX, state.RX);
        pad.SetAxisValue(Xbox360Axis.RightThumbY, state.RY);
        pad.SetSliderValue(Xbox360Slider.LeftTrigger, state.LT);
        pad.SetSliderValue(Xbox360Slider.RightTrigger, state.RT);
        pad.SubmitReport();
    }
    public void Dispose()
    {
        try { if (connected) { try { Send(default); } finally { pad.Disconnect(); connected = false; } } }
        finally { (pad as IDisposable)?.Dispose(); client.Dispose(); }
    }
}

internal readonly record struct BridgeStatus(bool Xbox, PadSample Input, string Error, int Revision = 0);

internal sealed class Bridge : IDisposable
{
    private readonly CancellationTokenSource stop = new();
    private Task worker = Task.CompletedTask;
    private readonly object gate = new();
    private bool requestedXbox = ReceiverSettings.LoadMode();
    private int requestedRevision;
    private BridgeStatus latest = new(false, PadSample.Empty, "");
    internal readonly PhonePresence Presence = new();
    internal BridgeStatus Latest { get { lock (gate) return latest; } }
    internal event Action<BridgeStatus>? Changed;
    internal void Start() => worker = Task.Factory.StartNew(Run, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
    internal async Task<string> ApplyModeAsync(bool value, CancellationToken cancellation)
    {
        int revision;
        lock (gate) { requestedXbox = value; revision = ++requestedRevision; }
        var deadline = DateTime.UtcNow.AddSeconds(9);
        while (DateTime.UtcNow < deadline)
        {
            cancellation.ThrowIfCancellationRequested();
            var status = Latest;
            if (status.Revision >= revision)
            {
                if (status.Error.Length > 0) return "ERROR SWITCH_FAILED";
                if (status.Xbox == value && (!value || status.Input.Connected))
                {
                    ReceiverSettings.SaveMode(value);
                    return ModeProtocol.Response(value);
                }
            }
            await Task.Delay(80, cancellation);
        }
        return "ERROR PHONE_NOT_CONNECTED";
    }
    private void Publish(BridgeStatus status, bool notify)
    {
        lock (gate) latest = status;
        if (notify) Changed?.Invoke(status);
    }
    private void Run()
    {
        XboxOutput? output = null;
        var guard = new HideGuard();
        string error = "";
        int errorRevision = -1;
        bool hiding = false;
        DateTime nextNotify = DateTime.MinValue;
        try
        {
            guard.Recover();
            using var input = new PhysicalPad();
            while (!stop.IsCancellationRequested)
            {
                var sample = PadSample.Empty;
                bool wanted;
                int revision;
                lock (gate) { wanted = requestedXbox; revision = requestedRevision; }
                if (revision != errorRevision) error = "";
                try
                {
                    sample = input.Read();
                    // Windows can keep a Bluetooth HID node present after the phone has gone.
                    // Only a live, authenticated phone session may expose it to games.
                    bool alive = Presence.IsAlive;
                    if (sample.Connected && (!alive || wanted)) { guard.Hide(sample.InstanceId); hiding = true; }
                    else if (hiding) { guard.Restore(); hiding = false; }
                    if (!alive) sample = PadSample.Empty;
                    if (wanted && sample.Connected)
                    {
                        output ??= new XboxOutput();
                        output.Send(PadMapping.Convert(sample));
                        error = "";
                    }
                    else if (output != null)
                    {
                        output.Dispose(); output = null;
                        error = "";
                    }
                }
                catch (Exception ex)
                {
                    lock (gate) { if (requestedRevision == revision) requestedXbox = false; }
                    try { output?.Dispose(); } catch { }
                    output = null;
                    error = ex.Message;
                    errorRevision = revision;
                    try { guard.Restore(); hiding = false; } catch (Exception restore) { error += " Przywracanie: " + restore.Message; }
                    nextNotify = DateTime.MinValue;
                    stop.Token.WaitHandle.WaitOne(500);
                }
                bool notify = DateTime.UtcNow >= nextNotify;
                Publish(new(output != null, sample, error, revision), notify);
                if (notify)
                {
                    nextNotify = DateTime.UtcNow.AddMilliseconds(150);
                }
                stop.Token.WaitHandle.WaitOne(8);
            }
        }
        catch (Exception ex) { Publish(new(false, PadSample.Empty, ex.Message, int.MaxValue), true); }
        finally
        {
            try { output?.Dispose(); }
            finally { try { guard.Restore(); } catch (Exception ex) { Changed?.Invoke(new(false, PadSample.Empty, "Przywrócenie DirectInput wymaga uruchomienia programu jako administrator. " + ex.Message)); } }
        }
    }
    internal async Task StopAsync() { stop.Cancel(); await worker; }
    public void Dispose() => stop.Dispose();
}
