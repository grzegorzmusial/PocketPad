using System.Diagnostics;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PocketPadModes;

internal sealed class HideGuard
{
    private static readonly string Cli = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Nefarius Software Solutions", "HidHide", "x64", "HidHideCLI.exe");
    private static readonly string StateFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PocketPadModes", "recovery.json");
    private Recovery state = new();
    private readonly HashSet<string> configured = new(StringComparer.OrdinalIgnoreCase);
    internal sealed class Recovery
    {
        public List<string> Devices { get; set; } = new();
        public bool RestoreCloakOff { get; set; }
        public string RegisteredApp { get; set; } = "";
    }
    internal static string Run(params string[] args)
    {
        if (!File.Exists(Cli)) throw new InvalidOperationException("Brak HidHide. Tryb Xbox wymaga zainstalowanego HidHide.");
        var start = new ProcessStartInfo(Cli) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var argument in args) start.ArgumentList.Add(argument);
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Nie można uruchomić HidHide.");
        var outputTask = process.StandardOutput.ReadToEndAsync();
        var errorTask = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit(6000)) { process.Kill(); throw new InvalidOperationException("HidHide nie odpowiada."); }
        var output = outputTask.GetAwaiter().GetResult();
        var error = errorTask.GetAwaiter().GetResult();
        if (process.ExitCode != 0 || output.Contains("denied", StringComparison.OrdinalIgnoreCase) || error.Length > 0)
            throw new InvalidOperationException("HidHide: uruchom PocketPadModes jako administrator. " + error + output);
        return output;
    }
    private static HashSet<string> Paths(string output) => Regex.Matches(output, "\"([^\"]+)\"").Select(m => m.Groups[1].Value).ToHashSet(StringComparer.OrdinalIgnoreCase);
    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(StateFile)!);
        File.WriteAllText(StateFile + ".new", JsonSerializer.Serialize(state));
        File.Move(StateFile + ".new", StateFile, true);
    }
    internal void Recover()
    {
        if (!File.Exists(StateFile)) return;
        state = JsonSerializer.Deserialize<Recovery>(File.ReadAllText(StateFile)) ?? throw new InvalidOperationException("Nie można odczytać pliku przywracania HidHide.");
        Restore();
    }
    internal void Hide(string instanceId)
    {
        if (string.IsNullOrEmpty(instanceId) || configured.Contains(instanceId)) return;
        if (!instanceId.StartsWith("HID\\{00001124-", StringComparison.OrdinalIgnoreCase) || !instanceId.Contains("_VID&00010075_PID&0100\\", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Przerwano: interfejs nie należy do Pocket Pad.");
        if (Run("--inv-state").Contains("--inv-on")) throw new InvalidOperationException("HidHide używa odwróconej listy aplikacji. Wyłącz ten tryb w HidHide przed użyciem PocketPadModes.");
        var app = Environment.ProcessPath ?? throw new InvalidOperationException("Brak ścieżki programu.");
        if (!Paths(Run("--app-list")).Contains(app))
        {
            state.RegisteredApp = app;
            Save();
            Run("--app-reg", app);
        }
        if (!Paths(Run("--dev-list")).Contains(instanceId))
        {
            state.Devices.Add(instanceId);
            Save();
            Run("--dev-hide", instanceId);
        }
        if (Run("--cloak-state").Contains("--cloak-off"))
        {
            state.RestoreCloakOff = true;
            Save();
            Run("--cloak-on");
        }
        configured.Add(instanceId);
    }
    internal void Restore()
    {
        // Only undo entries this process added; leave the user's other controller configuration intact.
        foreach (var id in state.Devices.ToArray())
        {
            Run("--dev-unhide", id);
            state.Devices.Remove(id);
            Save();
        }
        if (state.RestoreCloakOff)
        {
            if (Paths(Run("--dev-list")).Count == 0) Run("--cloak-off");
            state.RestoreCloakOff = false;
            Save();
        }
        if (state.RegisteredApp.Length > 0)
        {
            Run("--app-unreg", state.RegisteredApp);
            state.RegisteredApp = "";
            Save();
        }
        if (File.Exists(StateFile)) File.Delete(StateFile);
        configured.Clear();
    }
}
