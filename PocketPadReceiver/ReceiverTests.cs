using InTheHand.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace PocketPadModes;

internal static class ReceiverTests
{
    [StructLayout(LayoutKind.Sequential)] private struct Gamepad { internal ushort Buttons; internal byte LT, RT; internal short LX, LY, RX, RY; }
    [StructLayout(LayoutKind.Sequential)] private struct State { internal uint Packet; internal Gamepad Pad; }
    [DllImport("xinput1_4.dll")] private static extern uint XInputGetState(uint slot, out State state);
    internal static int Run(string[] args)
    {
        var report = new Dictionary<string, object>();
        int code = 0;
        try
        {
            if (!ModeProtocol.TryParse("POCKETPAD/1 MODE XBOX", out bool xbox) || !xbox ||
                !ModeProtocol.TryParse("POCKETPAD/1 MODE DINPUT", out bool direct) || direct ||
                ModeProtocol.TryParse("XBOX", out _) || ModeProtocol.TryParse("POCKETPAD/1 MODE XBOX extra", out _) ||
                ModeProtocol.Response(true) != "OK XBOX" || ModeProtocol.Response(false) != "OK DINPUT") throw new Exception("Mode protocol failed");
            report["modeProtocol"] = "PASS";
            long now = 100;
            var presence = new PhonePresence(() => now);
            if (presence.IsAlive) throw new Exception("Unconfirmed phone marked alive");
            presence.Touch(); now += 4999;
            if (!presence.IsAlive) throw new Exception("Phone lease ended too soon");
            now++;
            if (presence.IsAlive) throw new Exception("Stale phone lease stayed alive");
            presence.Touch(); presence.Disconnect();
            if (presence.IsAlive) throw new Exception("Disconnected phone stayed alive");
            report["phonePresenceExpiry"] = "PASS";
            using (var listener = new BluetoothListener(ModeProtocol.Service) { ServiceName = "PocketPad Mode Control Test" })
            {
                listener.Start();
                if (!listener.Active || listener.ServiceRecord == null) throw new Exception("RFCOMM service was not published");
                report["bluetoothListenerAndSdp"] = "PASS";
                report["serviceUuid"] = ModeProtocol.Service;
                listener.Stop();
            }
            using var source = new PhysicalPad();
            var input = source.Read();
            report["physicalConnected"] = input.Connected;
            var sample = PadMapping.Convert(new(true, (ushort)((1 << 0) | (1 << 8) | (1 << 10) | (1 << 11)), 3, 12000, short.MinValue, -20000, 9000, "test"));
            if (sample.Buttons != 0x1039 || sample.LT != 255 || sample.LY != short.MaxValue || sample.RY != -9000) throw new Exception("Pad mapping failed");
            report["padMapping"] = "PASS";
            var free = Enumerable.Range(0, 4).Where(i => XInputGetState((uint)i, out _) != 0).ToArray();
            if (free.Length == 0) throw new Exception("No free XInput slot for test");
            int slot = -1;
            using (var output = new XboxOutput())
            {
                for (int retry = 0; retry < 80; retry++)
                {
                    slot = free.FirstOrDefault(i => XInputGetState((uint)i, out _) == 0, -1);
                    if (slot >= 0) break;
                    Thread.Sleep(50);
                }
                if (slot < 0) throw new Exception("Xbox was not detected");
                output.Send(sample);
                bool verified = false;
                for (int retry = 0; retry < 40; retry++)
                {
                    if (XInputGetState((uint)slot, out var actual) == 0 && actual.Pad.Buttons == sample.Buttons && actual.Pad.LX == 12000 && actual.Pad.LY == short.MaxValue && actual.Pad.LT == 255) { verified = true; break; }
                    Thread.Sleep(25);
                }
                if (!verified) throw new Exception("XInput report failed");
                report["xinputReport"] = "PASS";
            }
            bool removed = false;
            for (int retry = 0; retry < 40; retry++) { if (XInputGetState((uint)slot, out _) != 0) { removed = true; break; } Thread.Sleep(50); }
            if (!removed) throw new Exception("Xbox cleanup failed");
            report["xinputCleanup"] = "PASS";
            report["phoneToReceiverTransport"] = "Requires installing Pocket Pad 1.2 on the phone and checking acknowledgement";
        }
        catch (Exception ex) { report["error"] = ex.ToString(); code = 1; }
        var json = JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true });
        int pathIndex = Array.IndexOf(args, "--result");
        if (pathIndex >= 0 && pathIndex + 1 < args.Length) File.WriteAllText(args[pathIndex + 1], json);
        Console.WriteLine(json);
        return code;
    }
}
