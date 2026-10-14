using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Reflection;
using System.Threading;
using Oxide.Plugins;
using Map = Oxide.Plugins.PlayerManagerRust.Map;
using P = Oxide.Plugins.PlayerManagerRust.Protocol;
using Core = Oxide.Plugins.PlayerManagerRust.BridgeCore;

// Counts the game-thread work a burst of requests causes. The provider is a counting fake: what matters is how many
// times the (expensive, main-thread) provider methods run, and how long the pure-CPU parts (encoding) take.
sealed class CountingProvider : PlayerManagerRust.IGameProvider
{
    public int Calls;
    public PlayerManagerRust.HelloInfo Describe()
    {
        var h = new PlayerManagerRust.HelloInfo();
        h.Events.AddRange(PlayerManagerRust.EventTypes);
        h.PlayerFields.AddRange(new[] { "world", "position", "health", "game" });
        return h;
    }
    public Map ServerInfo() { Calls++; return new Map().Put("name", "x"); }
    public Map PlayersList(bool o, bool s) { Calls++; return new Map().Put("players", new List<object>()); }
    public Map PlayerGet(string id, HashSet<string> f, bool s) { Calls++; return new Map().Put("id", id); }
    public Map PlayerInventory(string id) { Calls++; return new Map().Put("containers", new List<object>()); }
    public Map ItemGet(string id, string c, int slot, int[] p) { Calls++; return new Map(); }
    public Map RunAction(string a, string t, Map args, Map e) { Calls++; return new Map().Put("ok", true); }
    public PlayerManagerRust.PosSample[] SamplePositions() { return new PlayerManagerRust.PosSample[0]; }
}

static class Bench
{
    static double Time(Action a, int iter)
    {
        for (int i = 0; i < Math.Min(30, iter); i++) a();
        double best = double.MaxValue;
        for (int r = 0; r < 5; r++)
        {
            var sw = Stopwatch.StartNew();
            for (int i = 0; i < iter; i++) a();
            best = Math.Min(best, sw.Elapsed.TotalMilliseconds / iter);
        }
        return best;
    }

    static void Row(string name, double ms, string extra = "") { Console.WriteLine(name.PadRight(48) + ms.ToString("F4").PadLeft(10) + " ms  " + extra); }

    static void Main()
    {
        const int N = 500;
        var players = new List<object>();
        for (int i = 0; i < N; i++)
            players.Add(new Map().Put("id", "7656119800" + i.ToString("D7")).Put("name", "Player" + i).Put("world", "procedural:4500:1234567")
                .Put("x", (long)(i * 31)).Put("y", (long)(-i * 17)).Put("z", 220L).Put("state", "alive"));
        var frame = new Map().Put("sub", 1L).Put("seq", 7L).Put("data", new Map().Put("key", false).Put("players", players));
        var list = new Map().Put("id", 3L).Put("ok", true).Put("data", new Map().Put("players", players));
        Console.WriteLine("# encoder, " + N + " players");
        Row("encode position frame", Time(() => P.Pack.Encode(frame, false), 300));
        Row("encode players.list reply", Time(() => P.Pack.Encode(list, false), 300));
        var item = new PlayerManagerRust.ItemMap(); item["id"] = "rifle.ak"; item["count"] = 1L; item["skin"] = 5UL; item["condition"] = 92.5;
        item["components"] = new Map().Put("uid", 123456789UL).Put("definition_id", 1545779598L);
        Row("fingerprint of one item (canonical encode+sha)", Time(() => P.Canon.Fingerprint(item), 5000));
        var inv = new Map().Put("containers", new List<object> { new Map().Put("id", "main").Put("slots", Enumerable.Range(0, 24).Select(i => (object)new Map().Put("slot", (long)i).Put("item", Clone(item))).ToList()) });
        Row("Finalize (hash+fingerprint) 24-slot inventory", Time(() => P.Canon.Finalize(inv), 500));

        Console.WriteLine("\n# ten identical requests arriving together (player.get)");
        var prov = new CountingProvider();
        var cfg = new PlayerManagerRust.BridgeConfig();
        var core = new Core(prov, cfg, "1.0.0", false);
        core.SetHello(prov.Describe());
        var f = typeof(PlayerManagerRust.BridgeConfig).GetField("ReadCoalesceMs");
        bool hasCoalesce = f != null;
        Console.WriteLine(hasCoalesce ? "(plugin has read coalescing: ReadCoalesceMs=" + f.GetValue(cfg) + ")" : "(plugin has no read coalescing)");
        var pumpedBy = typeof(Core).GetMethod("DrainStep");
        // Drive Handle() directly through a loopback pair of streams is overkill; use the public Enqueue path via Handle with a fake Conn.
        var conn = FakeConn.Make(core);
        for (int rep = 0; rep < 3; rep++)
        {
            prov.Calls = 0;
            var sw = Stopwatch.StartNew();
            for (int i = 0; i < 10; i++) core.Handle(conn, new Map().Put("id", (long)(rep * 10 + i + 1)).Put("op", "player.get").Put("args", new Map().Put("id", "76561198000000001")));
            core.Drain(100, 100);
            Row("10x player.get, one drain (rep " + rep + ")", sw.Elapsed.TotalMilliseconds, "provider calls on the game thread = " + prov.Calls);
        }

        Console.WriteLine("\n# idle");
        int wakes = 0;
        var onMain = typeof(Core).GetField("OnMainWork");
        if (onMain != null)
        {
            onMain.SetValue(core, (Action)(() => Interlocked.Increment(ref wakes)));
            Thread.Sleep(1000);
            Console.WriteLine("main-thread wake-ups in 1 s with nothing queued: " + wakes + " (a 50 ms timer would fire 20)");
        }
        else Console.WriteLine("main-thread drain timer fires 20x per second even when idle (50 ms timer)");
    }

    static Map Clone(Map m) { var c = new PlayerManagerRust.ItemMap(); foreach (var kv in m) c[kv.Key] = kv.Value; return c; }
}

static class FakeConn
{
    public static PlayerManagerRust.Conn Make(Core core)
    {
        var ms = new System.IO.MemoryStream();
        var c = new PlayerManagerRust.Conn(core, (System.IO.Stream)new System.IO.MemoryStream(), "bench");
        c.Authed = true;
        return c;
    }
}
