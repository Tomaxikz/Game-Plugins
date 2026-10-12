using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using Oxide.Plugins;
using Map = Oxide.Plugins.PlayerManagerRust.Map;

// usage: Host <server-root> <path-to-pm-conformance.mjs> [tcp]
// Starts the bridge core on <root>/.playermanager with fake data, runs the conformance client, exits with its code.
sealed class HostProvider : PlayerManagerRust.IGameProvider
{
    const string Me = "76561198000000001";
    const string World = "procedural:4500:1234567";
    float x = 10f;

    public PlayerManagerRust.HelloInfo Describe()
    {
        var h = new PlayerManagerRust.HelloInfo { Platform = "oxide", PlatformVersion = "2.0.test", GameVersion = "2500" };
        h.PlayerFields.AddRange(new[] { "world", "position", "health", "max_health", "held_item", "ping", "game",  });
        h.Events.AddRange(PlayerManagerRust.EventTypes);
        h.Features.AddRange(new[] { "positions.stream", "offline_players", "sensitive", "assets" });
        Func<string, string, object, object, Map> f = (k, kind, min, max) => new Map().Put("key", k).Put("label", k).Put("kind", kind).Put("choices", new List<string>()).Put("min", min).Put("max", max);
        Func<string, bool, bool, Map[], Map> a = (id, destr, off, fs) => new Map().Put("id", id).Put("label", id).Put("target", true).Put("destructive", destr).Put("offline", off).Put("fields", new List<Map>(fs));
        h.Actions.Add(a("kick", true, false, new[] { f("reason", "text", null, null) }));
        h.Actions.Add(a("hurt", false, true, new[] { f("amount", "number", 0, 100) }));
        h.Actions.Add(a("give_item", false, true, new[] { f("item", "identifier", null, null), f("amount", "integer", 1, 64000) }));
        h.Actions.Add(a("teleport", false, false, new[] { f("x", "number", -2250, 2250), f("y", "number", -100, 1500), f("z", "number", -2250, 2250), f("to_player", "target", null, null), f("world", "text", null, null) }));
        h.Actions.Add(new Map().Put("id", "save").Put("label", "Save").Put("target", false).Put("destructive", false).Put("offline", false).Put("fields", new List<Map>()));
        return h;
    }

    public Map ServerInfo()
    {
        return new Map().Put("name", "Fake Rust").Put("max_players", 100L).Put("online", 1L).Put("uptime", 1000L)
            .Put("performance", new Map().Put("frame_ms", 17.1))
            .Put("worlds", new List<object> { new Map().Put("world", World).Put("name", "Procedural Map").Put("players", 1L).Put("loaded", true).Put("size", 4500L).Put("seed", "1234567") })
            .Put("extensions", new List<object>())
            .Put("game", new Map().Put("hostname", "Fake Rust").Put("description", "").Put("url", "").Put("header_image", "").Put("map_name", "Procedural Map")
                .Put("world_size", 4500L).Put("seed", 1234567L).Put("entity_count", 100L).Put("fps", 58.4).Put("save_interval", 300L)
                .Put("backpacks_plugin", false).Put("roles", new List<object>()).Put("bans", new List<object>()));
    }

    public Map PlayersList(bool off, bool sens)
    {
        var p = new Map().Put("id", Me).Put("name", "Bob").Put("online", true).Put("ping", 20L).Put("world", World).Put("groups", new List<string>()).Put("admin", false);
        if (sens) p["ip"] = "10.0.0.2";
        var s = new Map().Put("id", "76561198000000002").Put("name", "Sleepy").Put("online", false).Put("groups", new List<string> { "sleeping" }).Put("admin", false);
        return new Map().Put("players", off ? new List<object> { p, s } : new List<object> { p });
    }

    PlayerManagerRust.ItemMap Ak()
    {
        var mod = new PlayerManagerRust.ItemMap(); mod["id"] = "weapon.mod.silencer"; mod["count"] = 1L; mod["slot"] = 0L; mod["condition"] = 100.0; mod["max_condition"] = 100.0;
        mod["components"] = new Map().Put("uid", 123456790UL).Put("definition_id", 1815301988L);
        var ak = new PlayerManagerRust.ItemMap(); ak["id"] = "rifle.ak"; ak["count"] = 1L; ak["skin"] = 1234567890UL; ak["condition"] = 92.5; ak["max_condition"] = 100.0;
        ak["ammo"] = new Map().Put("id", "ammo.rifle").Put("count", 27L);
        ak["components"] = new Map().Put("uid", 123456789UL).Put("definition_id", 1545779598L);
        ak["contents"] = new List<object> { mod };
        return ak;
    }

    public Map PlayerGet(string id, HashSet<string> fields, bool sens)
    {
        if (id != Me) throw PlayerManagerRust.Err("not_found", "Player is not online or sleeping");
        Func<string, bool> want = k => fields == null || fields.Contains(k);
        var m = new Map().Put("id", id).Put("name", "Bob").Put("online", true);
        if (want("world")) m["world"] = World;
        if (want("position")) m["position"] = new Map().Put("x", (double)x).Put("y", 1.0).Put("z", 2.5);
        if (want("health")) m["health"] = 100.0;
        if (want("max_health")) m["max_health"] = 100.0;
        if (want("ping")) m["ping"] = 20L;
        if (want("held_item")) m["held_item"] = Ak();
        if (sens) m["ip"] = "10.0.0.2";
        if (want("game")) m["game"] = new Map().Put("net_id", 1048576L).Put("sleeping", false).Put("alive", true).Put("team", null)
            .Put("metabolism", new Map().Put("calories", 320.5).Put("hydration", 180.0));
        return m;
    }

    public Map PlayerInventory(string id)
    {
        if (id != Me) throw PlayerManagerRust.Err("not_found", "Player is not online or sleeping");
        return new Map().Put("containers", new List<object>
        {
            new Map().Put("id", "main").Put("size", 24L).Put("slots", new List<object>()),
            new Map().Put("id", "belt").Put("size", 6L).Put("slots", new List<object> { new Map().Put("slot", 0L).Put("item", Ak()) }),
            new Map().Put("id", "wear").Put("size", 8L).Put("slots", new List<object>())
        });
    }

    public Map ItemGet(string id, string container, int slot, int[] path)
    {
        if (id != Me) throw PlayerManagerRust.Err("not_found", "Player is not online or sleeping");
        if (container == "belt" && slot == 0 && path != null && path.Length == 1 && path[0] == 0) { var ak = Ak(); return new Map().Put("item", (Map)((List<object>)ak["contents"])[0]); }
        if (container == "belt" && slot == 0) return new Map().Put("item", Ak());
        throw PlayerManagerRust.Err("not_found", "Slot is empty");
    }

    public Map RunAction(string action, string target, Map args, Map expect)
    {
        if (expect != null) throw PlayerManagerRust.Err("conflict", "Expected item is not in the slot");
        return new Map().Put("ok", true);
    }

    public PlayerManagerRust.PosSample[] SamplePositions()
    {
        x += 0.5f; if (x > 60f) x = 10f;
        return new[] { new PlayerManagerRust.PosSample { Id = Me, Name = "Bob", World = World, X = x, Y = 1f, Z = 2.5f } };
    }
}

static class Program
{
    static int Main(string[] a)
    {
        string root = Path.GetFullPath(a[0]);
        string conf = a[1];
        bool tcp = a.Length > 2 && a[2] == "tcp";
        if (Directory.Exists(root)) Directory.Delete(root, true);
        string dir = Path.Combine(root, ".playermanager");
        Directory.CreateDirectory(dir);
        var cfg = new PlayerManagerRust.BridgeConfig { Transport = tcp ? "tcp" : "unix", TcpFallbackPort = 25590 + new Random().Next(1000) };
        var game = new HostProvider();
        var core = new PlayerManagerRust.BridgeCore(game, cfg, "1.0.0", false);
        core.Log = s => Console.WriteLine("[host] " + s);
        core.SetHello(game.Describe());
        var tr = new PlayerManagerRust.Transport(core, dir, core.Log);
        core.Start();
        if (!tr.Start()) { Console.WriteLine("bind failed"); return 3; }
        tr.WriteBridgeJson("1.0.0");
        bool stop = false;
        var drain = new Thread(() => { while (!stop) { core.Drain(20, 4.0); Thread.Sleep(10); } }) { IsBackground = true };
        drain.Start();
        var psi = new ProcessStartInfo("node", "\"" + conf + "\" --root \"" + root + "\" --profile rust --player 76561198000000001 --json \"" + Path.Combine(root, "report.json") + "\"") { UseShellExecute = false };
        int code;
        using (var p = Process.Start(psi)) { p.WaitForExit(); code = p.ExitCode; }
        stop = true; core.Stop(); tr.Stop();
        return code;
    }
}
