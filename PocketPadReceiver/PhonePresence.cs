using System.Diagnostics;

namespace PocketPadModes;

internal sealed class PhonePresence
{
    private long last;
    private readonly Func<long> clock;
    internal PhonePresence(Func<long>? clock = null) => this.clock = clock ?? (() => Environment.TickCount64);
    internal void Touch() => Interlocked.Exchange(ref last, clock());
    internal void Disconnect() => Interlocked.Exchange(ref last, 0);
    internal bool IsAlive { get { var tick = Interlocked.Read(ref last); return tick != 0 && clock() - tick < 5000; } }
}
