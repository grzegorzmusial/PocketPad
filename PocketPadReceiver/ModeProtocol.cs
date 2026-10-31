using System.Text.Json;

namespace PocketPadModes;

internal static class ModeProtocol
{
    internal static readonly Guid Service = new("b0c6da00-21e8-4abc-b52d-e1f4a3a9c61d");
    internal static bool TryParse(string line, out bool xbox)
    {
        xbox = line == "POCKETPAD/1 MODE XBOX";
        return xbox || line == "POCKETPAD/1 MODE DINPUT";
    }
    internal static string Response(bool xbox) => xbox ? "OK XBOX" : "OK DINPUT";
}

internal static class ReceiverSettings
{
    internal static readonly string Folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PocketPadReceiver");
    private static readonly string ModeFile = Path.Combine(Folder, "mode.json");
    internal static bool LoadMode()
    {
        try { return File.Exists(ModeFile) && JsonSerializer.Deserialize<bool>(File.ReadAllText(ModeFile)); }
        catch { return false; }
    }
    internal static void SaveMode(bool mode)
    {
        Directory.CreateDirectory(Folder);
        File.WriteAllText(ModeFile + ".new", JsonSerializer.Serialize(mode));
        File.Move(ModeFile + ".new", ModeFile, true);
    }
}
