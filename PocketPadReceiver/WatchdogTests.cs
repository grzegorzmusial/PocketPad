using System.Text.Json;

namespace PocketPadModes;

internal static class WatchdogTests
{
    internal static async Task<int> Run(string[] args)
    {
        var report = new Dictionary<string, object>();
        bool originalMode = ReceiverSettings.LoadMode();
        var beforeDevices = HideGuard.Run("--dev-list");
        var beforeCloak = HideGuard.Run("--cloak-state");
        using var bridge = new Bridge();
        using var pulseStop = new CancellationTokenSource();
        Task pulse = Task.CompletedTask;
        int code = 0;
        try
        {
            bridge.Start();
            await Task.Delay(2500);
            if (bridge.Latest.Error.Length > 0) throw new Exception(bridge.Latest.Error);
            if (bridge.Latest.Xbox || bridge.Latest.Input.Connected) throw new Exception("Unconfirmed phone exposed to games");
            if (!HideGuard.Run("--dev-list").Contains("_VID&00010075_PID&0100", StringComparison.OrdinalIgnoreCase)) throw new Exception("Stale physical controller was not hidden");
            report["startupWithoutHeartbeatHidesStalePad"] = "PASS";
            pulse = Task.Run(async () => { while (!pulseStop.IsCancellationRequested) { bridge.Presence.Touch(); await Task.Delay(250, pulseStop.Token); } });
            bridge.Presence.Touch();
            var response = await bridge.ApplyModeAsync(true, CancellationToken.None);
            if (response != "OK XBOX" || !bridge.Latest.Xbox) throw new Exception("Xbox activation: " + response + " " + bridge.Latest.Error);
            report["livePhoneCreatesXbox"] = "PASS";
            pulseStop.Cancel(); try { await pulse; } catch (OperationCanceledException) { }
            // Simulate an abrupt radio failure: no orderly disconnect callback.
            await Task.Delay(5600);
            if (bridge.Latest.Xbox || bridge.Latest.Input.Connected) throw new Exception("Xbox survived heartbeat expiry");
            if (!HideGuard.Run("--dev-list").Contains("_VID&00010075_PID&0100", StringComparison.OrdinalIgnoreCase)) throw new Exception("Stale source became visible after timeout");
            report["heartbeatTimeoutRemovesXboxAndKeepsSourceHidden"] = "PASS";
            bridge.Presence.Touch();
            response = await bridge.ApplyModeAsync(false, CancellationToken.None);
            if (response != "OK DINPUT" || bridge.Latest.Xbox || !bridge.Latest.Input.Connected) throw new Exception("DirectInput recovery: " + response + " " + bridge.Latest.Error);
            if (HideGuard.Run("--dev-list") != beforeDevices) throw new Exception("Live DirectInput still hidden");
            report["reconnectRestoresDirectInput"] = "PASS";
        }
        catch (Exception ex) { report["error"] = ex.ToString(); code = 1; }
        finally
        {
            pulseStop.Cancel(); try { await pulse; } catch (OperationCanceledException) { }
            await bridge.StopAsync();
            ReceiverSettings.SaveMode(originalMode);
        }
        if (HideGuard.Run("--dev-list") != beforeDevices || HideGuard.Run("--cloak-state") != beforeCloak)
        { report["cleanup"] = "FAIL"; code = 1; }
        else report["cleanup"] = "PASS";
        var json = JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true });
        int index = Array.IndexOf(args, "--result");
        if (index >= 0 && index + 1 < args.Length) File.WriteAllText(args[index + 1], json);
        Console.WriteLine(json);
        return code;
    }
}
