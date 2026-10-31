using System.Text.Json;

namespace PocketPadModes;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--stop"))
        {
            try { using var signal = EventWaitHandle.OpenExisting("Local\\PocketPadReceiver.Stop"); signal.Set(); } catch (WaitHandleCannotBeOpenedException) { }
            return 0;
        }
        if (args.Contains("--self-test")) return ReceiverTests.Run(args);
        if (args.Contains("--watchdog-test")) return WatchdogTests.Run(args).GetAwaiter().GetResult();
        using var mutex = new Mutex(true, "Local\\PocketPadModes.Gregor", out bool first);
        if (!first) { MessageBox.Show("Odbiornik Pocket Pad lub poprzedni PocketPadModes już działa. Zamknij poprzednią wersję i uruchom odbiornik ponownie.", "Pocket Pad"); return 1; }
        ApplicationConfiguration.Initialize();
        Application.Run(new ReceiverContext());
        return 0;
    }
}

internal sealed class ReceiverContext : ApplicationContext
{
    private readonly Control dispatcher = new();
    private readonly NotifyIcon tray;
    private readonly ToolStripMenuItem mode = new() { Enabled = false };
    private readonly ToolStripMenuItem phone = new() { Enabled = false };
    private readonly ToolStripMenuItem radio = new() { Enabled = false };
    private readonly Bridge bridge;
    private readonly ModeControlServer server;
    private readonly EventWaitHandle stopSignal = new(false, EventResetMode.AutoReset, "Local\\PocketPadReceiver.Stop");
    private readonly RegisteredWaitHandle stopRegistration;
    private Form? statusForm;
    private Label? statusLabel;
    private BridgeStatus last = new(false, PadSample.Empty, "");
    private string radioText = "Bluetooth: uruchamianie…";
    private bool ready, closing;
    private string lastSaved = "";
    internal ReceiverContext()
    {
        dispatcher.CreateControl();
        var menu = new ContextMenuStrip();
        menu.Items.AddRange(new ToolStripItem[] { mode, phone, radio, new ToolStripSeparator() });
        menu.Items.Add("Pokaż stan", null, (_, _) => ShowStatus());
        menu.Items.Add("Zakończ odbiornik", null, async (_, _) => await Shutdown());
        tray = new NotifyIcon { Icon = SystemIcons.Application, Text = "Pocket Pad — odbiornik", Visible = true, ContextMenuStrip = menu };
        tray.DoubleClick += (_, _) => ShowStatus();
        bridge = new Bridge();
        server = new ModeControlServer(bridge);
        bridge.Changed += state => Post(() => { last = state; Update(); });
        server.StateChanged += (listening, text) => Post(() => { ready = listening; radioText = text; Update(); });
        stopRegistration = ThreadPool.RegisterWaitForSingleObject(stopSignal, (_, _) => Post(async () => await Shutdown()), null, Timeout.Infinite, false);
        Update();
        bridge.Start(); server.Start();
    }
    private void Post(Action action) { if (!closing && !dispatcher.IsDisposed) try { dispatcher.BeginInvoke(action); } catch (InvalidOperationException) { } }
    private string Description() => "Tryb: " + (last.Xbox ? "Xbox / XInput" : "DirectInput") + "\n" +
        (last.Input.Connected ? "Pocket Pad połączony." : "Oczekiwanie na połączenie telefonu.") + "\n\n" + radioText +
        (last.Error.Length == 0 ? "" : "\n\n" + last.Error) +
        "\n\nTryb wybierasz w Pocket Pad 1.2 na telefonie.\nMonitor usuwa Xbox i ukrywa nieaktywny Pocket Pad po utracie sygnału z aplikacji (do 5 sekund).\nPozostaw odbiornik uruchomiony podczas grania.";
    private void Update()
    {
        mode.Text = "Tryb: " + (last.Xbox ? "Xbox / XInput" : "DirectInput") + " (wybór na telefonie)";
        phone.Text = last.Input.Connected ? "Telefon: połączony" : "Telefon: oczekiwanie";
        radio.Text = ready ? "Sterowanie Bluetooth: gotowe" : "Sterowanie Bluetooth: niedostępne";
        tray.Text = "Pocket Pad: " + (last.Xbox ? "Xbox" : "DirectInput") + (last.Input.Connected ? " · połączony" : " · oczekiwanie");
        if (statusLabel != null) statusLabel.Text = Description();
        var json = JsonSerializer.Serialize(new { Mode = last.Xbox ? "XBOX" : "DINPUT", PhoneConnected = last.Input.Connected, Listening = ready, Error = last.Error, Radio = radioText, Stopped = false });
        if (json != lastSaved)
        {
            try { Directory.CreateDirectory(ReceiverSettings.Folder); File.WriteAllText(Path.Combine(ReceiverSettings.Folder, "status.json"), json); lastSaved = json; } catch (IOException) { }
        }
    }
    private void ShowStatus()
    {
        if (statusForm != null) { statusForm.Show(); statusForm.Activate(); return; }
        statusForm = new Form { Text = "Pocket Pad — stan odbiornika", ClientSize = new Size(550, 300), StartPosition = FormStartPosition.CenterScreen, MaximizeBox = false, Font = new Font("Segoe UI", 10), BackColor = Color.FromArgb(17, 24, 31), ForeColor = Color.WhiteSmoke };
        statusLabel = new Label { Text = Description(), Dock = DockStyle.Fill, Padding = new Padding(22), AutoSize = false };
        statusForm.Controls.Add(statusLabel);
        statusForm.FormClosed += (_, _) => { statusForm = null; statusLabel = null; };
        statusForm.Show();
    }
    private async Task Shutdown()
    {
        if (closing) return;
        closing = true;
        await server.StopAsync();
        await bridge.StopAsync();
        server.Dispose(); bridge.Dispose();
        stopRegistration.Unregister(null); stopSignal.Dispose();
        statusForm?.Close(); tray.Visible = false; tray.Dispose();
        try { File.WriteAllText(Path.Combine(ReceiverSettings.Folder, "status.json"), JsonSerializer.Serialize(new { Mode = "DINPUT", Stopped = true })); } catch (IOException) { }
        dispatcher.Dispose(); ExitThread();
    }
}
