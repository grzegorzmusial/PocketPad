package pl.pocketpad;

/** Separate RFCOMM control channel. The existing HID descriptor and reports stay unchanged. */
public final class ModeProtocol {
    private ModeProtocol() {}
    public static final String SERVICE_UUID = "b0c6da00-21e8-4abc-b52d-e1f4a3a9c61d";
    public static String command(boolean xbox) { return "POCKETPAD/1 MODE " + (xbox ? "XBOX" : "DINPUT") + "\n"; }
    public static boolean accepted(boolean xbox, String reply) { return ("OK " + (xbox ? "XBOX" : "DINPUT")).equals(reply); }
}
