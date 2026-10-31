using System.Runtime.InteropServices;

namespace PocketPadModes;

internal readonly record struct PadSample(bool Connected, ushort Buttons, byte Hat, short LX, short LY, short RX, short RY, string InstanceId)
{
    internal static PadSample Empty => new(false, 0, 0, 0, 0, 0, 0, "");
}

internal sealed class PhysicalPad : IDisposable
{
    private IntPtr handle;
    private DateTime nextScan;
    private string instanceId = "";
    internal PhysicalPad()
    {
        SDL_SetHint("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1");
        if (!SDL_Init(0x4200)) throw new InvalidOperationException("SDL: " + Marshal.PtrToStringUTF8(SDL_GetError()));
    }

    internal PadSample Read()
    {
        SDL_PumpEvents();
        SDL_UpdateJoysticks();
        if (handle != IntPtr.Zero && !SDL_JoystickConnected(handle))
        {
            SDL_CloseJoystick(handle);
            handle = IntPtr.Zero;
            instanceId = "";
        }
        if (handle == IntPtr.Zero && DateTime.UtcNow >= nextScan)
        {
            nextScan = DateTime.UtcNow.AddSeconds(1);
            var ids = SDL_GetJoysticks(out int count);
            try
            {
                var matches = new List<uint>();
                for (int i = 0; i < count; i++)
                {
                    uint id = (uint)Marshal.ReadInt32(ids, i * 4);
                    if (SDL_GetJoystickVendorForID(id) == 0x75 && SDL_GetJoystickProductForID(id) == 0x100 &&
                        string.Equals(Marshal.PtrToStringUTF8(SDL_GetJoystickNameForID(id)), "Pocket Pad", StringComparison.Ordinal)) matches.Add(id);
                }
                if (matches.Count > 1) throw new InvalidOperationException("Połącz tylko jeden telefon z Pocket Pad.");
                if (matches.Count == 1)
                {
                    handle = SDL_OpenJoystick(matches[0]);
                    if (handle == IntPtr.Zero) throw new InvalidOperationException("Nie można odczytać Pocket Pad: " + Marshal.PtrToStringUTF8(SDL_GetError()));
                    try
                    {
                        if (SDL_GetNumJoystickAxes(handle) != 4 || SDL_GetNumJoystickButtons(handle) != 16 || SDL_GetNumJoystickHats(handle) != 1)
                            throw new InvalidOperationException("Telefon zgłasza inny układ niż Pocket Pad 1.0 (4 osie, 16 przycisków, krzyżak).");
                        instanceId = FindInstanceId();
                    }
                    catch { SDL_CloseJoystick(handle); handle = IntPtr.Zero; throw; }
                }
            }
            finally { if (ids != IntPtr.Zero) SDL_free(ids); }
        }
        if (handle == IntPtr.Zero) return PadSample.Empty;
        ushort buttons = 0;
        for (int i = 0; i < 16; i++) if (SDL_GetJoystickButton(handle, i)) buttons |= (ushort)(1 << i);
        return new(true, buttons, SDL_GetJoystickHat(handle, 0), SDL_GetJoystickAxis(handle, 0), SDL_GetJoystickAxis(handle, 1),
            SDL_GetJoystickAxis(handle, 2), SDL_GetJoystickAxis(handle, 3), instanceId);
    }

    private static string FindInstanceId()
    {
        if (CM_Get_Device_ID_List_SizeW(out uint size, IntPtr.Zero, 0x100) != 0) throw new InvalidOperationException("Nie można odczytać listy urządzeń Windows.");
        var buffer = new char[size];
        if (CM_Get_Device_ID_ListW(IntPtr.Zero, buffer, size, 0x100) != 0) throw new InvalidOperationException("Lista urządzeń uległa zmianie; połącz telefon ponownie.");
        var ids = new string(buffer).Split('\0', StringSplitOptions.RemoveEmptyEntries)
            .Where(s => s.StartsWith("HID\\{00001124-", StringComparison.OrdinalIgnoreCase) && s.Contains("_VID&00010075_PID&0100\\", StringComparison.OrdinalIgnoreCase)).ToArray();
        if (ids.Length != 1) throw new InvalidOperationException("Nie można jednoznacznie wskazać interfejsu Pocket Pad. Pozostaw podłączony jeden taki pad.");
        return ids[0];
    }

    public void Dispose() { if (handle != IntPtr.Zero) SDL_CloseJoystick(handle); SDL_Quit(); }
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)] private static extern uint CM_Get_Device_ID_List_SizeW(out uint size, IntPtr filter, uint flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)] private static extern uint CM_Get_Device_ID_ListW(IntPtr filter, [Out] char[] buffer, uint length, uint flags);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] [return: MarshalAs(UnmanagedType.I1)] private static extern bool SDL_Init(uint flags);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] [return: MarshalAs(UnmanagedType.I1)] private static extern bool SDL_SetHint([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string value);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern IntPtr SDL_GetError();
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern void SDL_PumpEvents();
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern void SDL_UpdateJoysticks();
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern IntPtr SDL_GetJoysticks(out int count);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern IntPtr SDL_GetJoystickNameForID(uint id);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern ushort SDL_GetJoystickVendorForID(uint id);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern ushort SDL_GetJoystickProductForID(uint id);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern IntPtr SDL_OpenJoystick(uint id);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern void SDL_CloseJoystick(IntPtr joystick);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] [return: MarshalAs(UnmanagedType.I1)] private static extern bool SDL_JoystickConnected(IntPtr joystick);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern int SDL_GetNumJoystickAxes(IntPtr joystick);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern int SDL_GetNumJoystickButtons(IntPtr joystick);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern int SDL_GetNumJoystickHats(IntPtr joystick);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern short SDL_GetJoystickAxis(IntPtr joystick, int axis);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] [return: MarshalAs(UnmanagedType.I1)] private static extern bool SDL_GetJoystickButton(IntPtr joystick, int button);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern byte SDL_GetJoystickHat(IntPtr joystick, int hat);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern void SDL_free(IntPtr memory);
    [DllImport("SDL3.dll", CallingConvention = CallingConvention.Cdecl)] private static extern void SDL_Quit();
}
