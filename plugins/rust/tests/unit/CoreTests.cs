using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using Oxide.Plugins;
using Xunit;
using Map = Oxide.Plugins.PlayerManagerRust.Map;
using P = Oxide.Plugins.PlayerManagerRust.Protocol;
using Core = Oxide.Plugins.PlayerManagerRust.BridgeCore;

/// <summary>Runs the real protocol core + transport in-process against a fake game provider.</summary>
public sealed class FakeProvider : PlayerManagerRust.IGameProvider
{
    public List<PlayerManagerRust.PosSample> Players = new List<PlayerManagerRust.PosSample>();
    public int ServerInfoCalls;
    public volatile bool Block;
    public Map LastAction;
    public int MainThreadId;

    public PlayerManagerRust.HelloInfo Describe()
    {
        var h = new PlayerManagerRust.HelloInfo { Platform = "oxide", PlatformVersion = "2.0.test", GameVersion = "1" };
        h.PlayerFields.AddRange(new[] { "world", "position", "health", "game" });
        h.Events.AddRange(PlayerManagerRust.EventTypes);
        h.Features.AddRange(new[] { "positions.stream", "offline_players", "sensitive" });
        Func<string, string, object, object, Map> f = (k, kind, min, max) => new Map().Put("key", k).Put("label", k).Put("kind", kind).Put("choices", new List<string>()).Put("min", min).Put("max", max);
        h.Actions.Add(new Map().Put("id", "hurt").Put("label", "Hurt").Put("target", true).Put("destructive", false).Put("offline", false).Put("fields", new List<Map> { f("amount", "number", 0, 100) }));
        h.Actions.Add(new Map().Put("id", "save").Put("label", "Save").Put("target", false).Put("destructive", false).Put("offline", false).Put("fields", new List<Map>()));
        return h;
    }
    void Gate() { while (Block) Thread.Sleep(5); MainThreadId = Thread.CurrentThread.ManagedThreadId; }
    public Map ServerInfo() { Gate(); ServerInfoCalls++; return new Map().Put("name", "Fake").Put("max_players", 10L).Put("online", (long)Players.Count).Put("worlds", new List<object>()); }
    public Map PlayersList(bool off, bool s) { Gate(); return new Map().Put("players", Players.Select(p => (object)new Map().Put("id", p.Id).Put("name", p.Name).Put("online", true).Put("groups", new List<string>()).Put("admin", false).PutIf(s, "ip", "1.2.3.4")).ToList()); }
    public Map PlayerGet(string id, HashSet<string> fields, bool s)
    {
        Gate();
        if (id == "76561198000000404") throw PlayerManagerRust.Err("not_found", "no such player");
        var item = new PlayerManagerRust.ItemMap(); item["id"] = "rifle.ak"; item["count"] = 1L; item["skin"] = 5UL;
        return new Map().Put("id", id).Put("name", "Bob").Put("online", true).Put("held_item", item).Put("game", new Map());
    }
    public Map PlayerInventory(string id) { Gate(); return new Map().Put("containers", new List<object>()); }
    public Map ItemGet(string id, string c, int slot, int[] path) { Gate(); throw PlayerManagerRust.Err("not_found", "Slot is empty"); }
    public Map RunAction(string action, string target, Map args, Map expect) { Gate(); LastAction = args; return new Map().Put("ok", true).Put("message", action + ":" + target); }
    public PlayerManagerRust.PosSample[] SamplePositions() { Gate(); return Players.ToArray(); }
}

public sealed class Harness : IDisposable
{
    public readonly FakeProvider Game = new FakeProvider();
    public readonly Core Core;
    public readonly PlayerManagerRust.Transport Transport;
    public readonly string Dir;
    private readonly Thread drain;
    private volatile bool stop;
    public readonly List<string> Logs = new List<string>();

    public Harness(string transport = "auto", int port = 0, Action<PlayerManagerRust.BridgeConfig> tweak = null)
    {
        Dir = Path.Combine(Path.GetTempPath(), "pm" + Guid.NewGuid().ToString("N").Substring(0, 8));
        Directory.CreateDirectory(Dir);
        var cfg = new PlayerManagerRust.BridgeConfig { Transport = transport, PositionsMinIntervalMs = 50 };
        if (port != 0) cfg.TcpFallbackPort = port;
        if (tweak != null) tweak(cfg);
        Core = new Core(Game, cfg, "1.0.0", false);
        Core.Log = s => { lock (Logs) Logs.Add(s); };
        Core.SetHello(Game.Describe());
        Transport = new PlayerManagerRust.Transport(Core, Dir, Core.Log);
        Core.Start();
        Assert.True(Transport.Start());
        drain = new Thread(() => { while (!stop) { Core.Drain(20, 4.0); Thread.Sleep(10); } }) { IsBackground = true };
        drain.Start();
    }

    public Client Connect()
    {
        Socket s;
        if (Transport.Mode == "unix")
        {
            s = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            s.Connect(new UnixDomainSocketEndPoint(Path.Combine(Dir, "bridge.sock")));
        }
        else
        {
            s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            s.Connect(new IPEndPoint(IPAddress.Loopback, Transport.Port));
        }
        return new Client(s);
    }

    public void Dispose() { stop = true; Core.Stop(); Transport.Stop(); try { Directory.Delete(Dir, true); } catch { } }
}

public sealed class Client : IDisposable
{
    private readonly Socket s; private readonly NetworkStream ns; private long next = 1;
    public Client(Socket s) { this.s = s; ns = new NetworkStream(s); s.ReceiveTimeout = 5000; }
    public void Send(Map m) { var f = P.Framing.Wrap(P.Pack.Encode(m, false)); ns.Write(f, 0, f.Length); }
    public Map Recv() { var b = P.Framing.Read(ns, PlayerManagerRust.MaxFrame); return b == null ? null : (Map)P.Pack.Decode(b, 0, b.Length); }
    public Map Call(string op, Map args = null, long? id = null)
    {
        long i = id ?? next++;
        var m = new Map().Put("id", i).Put("op", op);
        if (args != null) m["args"] = args;
        Send(m);
        while (true) { var r = Recv(); if (r == null) return null; if (r.ContainsKey("id") && (long)r["id"] == i) return r; }
    }
    public Map Hello(string token = null)
    {
        var a = new Map().Put("panel_version", "1.0.0");
        if (token != null) a["token"] = token;
        return Call("hello", a);
    }
    public static string ErrCode(Map r) { return (string)((Map)r["error"])["code"]; }
    public void Dispose() { try { s.Close(); } catch { } }
}

public class CoreTests
{
    [Fact]
    public void Hello_shape_and_ops()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            Assert.Equal("unix", h.Transport.Mode);
            var r = c.Hello();
            Assert.True((bool)r["ok"]);
            var d = (Map)r["data"];
            Assert.Equal(1L, d["protocol"]);
            Assert.Equal("rust", d["game"]);
            Assert.Equal("oxide", d["platform"]);
            Assert.Equal("1.0.0", d["bridge_version"]);
            var caps = (Map)d["capabilities"];
            var ops = ((List<object>)caps["ops"]).Cast<string>().ToList();
            Assert.Contains("action.run", ops);
            Assert.DoesNotContain("player.nearby", ops);
            var act = (Map)((List<object>)caps["actions"])[0];
            Assert.Equal("hurt", act["id"]);
            var field = (Map)((List<object>)act["fields"])[0];
            Assert.Equal(0L, field["min"]); Assert.Equal(100L, field["max"]); Assert.True(field.ContainsKey("choices"));
            var lim = (Map)d["limits"];
            Assert.Equal(4194304L, lim["max_frame"]); Assert.Equal(16L, lim["max_inflight"]);
        }
    }

    [Fact]
    public void Hello_requires_panel_version_and_op_before_hello_is_invalid_args_on_unix()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            var r = c.Call("server.info");
            Assert.False((bool)r["ok"]);
            Assert.Equal("invalid_args", Client.ErrCode(r));
            r = c.Call("hello", new Map());
            Assert.Equal("invalid_args", Client.ErrCode(r));
            Assert.True((bool)c.Hello()["ok"]);                       // connection stayed open
            Assert.True((bool)c.Call("server.info")["ok"]);
        }
    }

    [Fact]
    public void Requests_run_on_the_drain_thread_not_the_socket_thread_and_unknown_ops_are_unsupported()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            var r = c.Call("server.info");
            Assert.Equal("Fake", ((Map)r["data"])["name"]);
            Assert.NotEqual(0, h.Game.MainThreadId);
            Assert.Equal("unsupported", Client.ErrCode(c.Call("player.nearby", new Map().Put("id", "76561198000000000"))));
            Assert.Equal("unsupported", Client.ErrCode(c.Call("nope")));
        }
    }

    [Fact]
    public void Enabled_ops_config_removes_ops()
    {
        using (var h = new Harness(tweak: cfg => cfg.EnabledOps = new HashSet<string> { "players.list" })) using (var c = h.Connect())
        {
            var d = (Map)c.Hello()["data"];
            var ops = ((List<object>)((Map)d["capabilities"])["ops"]).Cast<string>().ToList();
            Assert.Equal(new[] { "players.list" }, ops.ToArray());
            Assert.Equal("unsupported", Client.ErrCode(c.Call("server.info")));
        }
    }

    [Fact]
    public void Player_ids_are_validated_and_provider_errors_pass_through()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            Assert.Equal("invalid_args", Client.ErrCode(c.Call("player.get", new Map().Put("id", "@a"))));
            Assert.Equal("invalid_args", Client.ErrCode(c.Call("player.get", new Map().Put("id", "76561198000000000").Put("fields", new List<object> { "bogus" }))));
            Assert.Equal("not_found", Client.ErrCode(c.Call("player.get", new Map().Put("id", "76561198000000404"))));
            var ok = c.Call("player.get", new Map().Put("id", "76561198000000000").Put("fields", new List<object> { "world", "game.team" }));
            var item = (Map)((Map)ok["data"])["held_item"];
            Assert.Equal("rifle.ak", item["id"]);
            Assert.Equal(16, ((string)item["hash"]).Length);        // finalised off-thread
            Assert.Equal(64, ((string)item["fingerprint"]).Length);
            Assert.Equal(P.Canon.RenderHash(new Map().Put("id", "rifle.ak").Put("skin", 5L)), item["hash"]);
        }
    }

    [Fact]
    public void Actions_validated_before_reaching_the_game()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            string t = "76561198000000000";
            Assert.Equal("unsupported", Client.ErrCode(c.Call("action.run", new Map().Put("action", "teleport_player").Put("target", t))));
            Assert.Equal("unsupported", Client.ErrCode(c.Call("action.run", new Map().Put("action", "rcon"))));
            Assert.Equal("invalid_args", Client.ErrCode(c.Call("action.run", new Map().Put("action", "hurt").Put("target", "@a"))));
            Assert.Equal("invalid_args", Client.ErrCode(c.Call("action.run", new Map().Put("action", "hurt").Put("target", t).Put("args", new Map().Put("amount", 101L)))));
            Assert.Equal("invalid_args", Client.ErrCode(c.Call("action.run", new Map().Put("action", "hurt").Put("target", t).Put("args", new Map().Put("amount", double.NaN)))));
            Assert.Null(h.Game.LastAction);
            var ok = c.Call("action.run", new Map().Put("action", "hurt").Put("target", t).Put("args", new Map().Put("amount", 5L).Put("confirmed", true)));
            Assert.True((bool)((Map)ok["data"])["ok"]);
            Assert.False(h.Game.LastAction.ContainsKey("confirmed"));
            Assert.True((bool)((Map)c.Call("action.run", new Map().Put("action", "save"))["data"])["ok"]);   // server-wide: no target
        }
    }

    [Fact]
    public void Disabled_actions_are_hidden_and_refused()
    {
        using (var h = new Harness(tweak: cfg => cfg.DisabledActions.Add("hurt"))) using (var c = h.Connect())
        {
            var d = (Map)c.Hello()["data"];
            var acts = ((List<object>)((Map)d["capabilities"])["actions"]).Cast<Map>().Select(a => (string)a["id"]).ToList();
            Assert.DoesNotContain("hurt", acts);
            Assert.Equal("unsupported", Client.ErrCode(c.Call("action.run", new Map().Put("action", "hurt").Put("target", "76561198000000000").Put("args", new Map().Put("amount", 1L)))));
        }
    }

    [Fact]
    public void Deadline_gives_timeout_and_late_result_is_discarded()
    {
        using (var h = new Harness())
        {
            h.Game.Block = true;
            using (var c = h.Connect())
            {
                c.Hello();
                var sw = System.Diagnostics.Stopwatch.StartNew();
                var r = c.Call("server.info");
                Assert.Equal("timeout", Client.ErrCode(r));
                Assert.InRange(sw.ElapsedMilliseconds, 1800, 4000);
                h.Game.Block = false;
                Thread.Sleep(200);
                var again = c.Call("server.info");                       // late result of the first one must not leak as a second response
                Assert.True((bool)again["ok"]);
            }
        }
    }

    [Fact]
    public void Inflight_limit_gives_busy()
    {
        using (var h = new Harness())
        {
            h.Game.Block = true;
            using (var c = h.Connect())
            {
                c.Hello();
                for (int i = 0; i < 17; i++) c.Send(new Map().Put("id", (long)(100 + i)).Put("op", "server.info"));
                var busy = 0;
                var sw = System.Diagnostics.Stopwatch.StartNew();
                while (busy == 0 && sw.ElapsedMilliseconds < 1500)
                {
                    var r = c.Recv();
                    if (!(bool)r["ok"] && Client.ErrCode(r) == "busy") busy++;
                }
                Assert.Equal(1, busy);
                h.Game.Block = false;
            }
        }
    }

    [Fact]
    public void Positions_stream_keyframe_then_delta_gone_and_second_subscription_busy()
    {
        using (var h = new Harness())
        {
            h.Game.Players.Add(new PlayerManagerRust.PosSample { Id = "76561198000000001", Name = "A", World = "w", X = 12.5f, Y = 1f, Z = -80.25f });
            h.Game.Players.Add(new PlayerManagerRust.PosSample { Id = "76561198000000002", Name = "B", World = "w", X = 0, Y = 0, Z = 0 });
            using (var c = h.Connect())
            {
                c.Hello();
                var r = c.Call("positions.subscribe", new Map().Put("interval_ms", 10L));
                long sub = (long)((Map)r["data"])["sub"];
                Assert.Equal("busy", Client.ErrCode(c.Call("positions.subscribe", new Map().Put("interval_ms", 100L))));
                var f0 = c.Recv();
                Assert.Equal(sub, f0["sub"]); Assert.Equal(0L, f0["seq"]);
                var d0 = (Map)f0["data"];
                Assert.True((bool)d0["key"]);
                var pa = ((List<object>)d0["players"]).Cast<Map>().First(m => (string)m["id"] == "76561198000000001");
                Assert.Equal(200L, pa["x"]); Assert.Equal(16L, pa["y"]); Assert.Equal(-1284L, pa["z"]);
                Assert.Equal("alive", pa["state"]);
                Assert.False(pa.ContainsKey("yaw")); Assert.False(pa.ContainsKey("dimension"));
                // move A, remove B
                h.Game.Players[0] = new PlayerManagerRust.PosSample { Id = "76561198000000001", Name = "A", World = "w", X = 13f, Y = 1f, Z = -80.25f };
                h.Game.Players.RemoveAt(1);
                Map f1;
                do { f1 = c.Recv(); } while (f1 != null && (f1["data"] is Map) && ((Map)f1["data"]).ContainsKey("key") && (bool)((Map)f1["data"])["key"]);
                var d1 = (Map)f1["data"];
                Assert.False((bool)d1["key"]);
                Assert.Single((List<object>)d1["players"]);
                Assert.Equal(new object[] { "76561198000000002" }, ((List<object>)d1["gone"]).ToArray());
                // unchanged -> no frame; explicit keyframe -> key frame
                Assert.True((bool)c.Call("positions.keyframe", new Map().Put("sub", sub))["ok"]);
                Map fk;
                do { fk = c.Recv(); } while (!(bool)((Map)fk["data"])["key"]);
                Assert.True((long)fk["seq"] >= 2);
                Assert.True((bool)c.Call("positions.unsubscribe", new Map().Put("sub", sub))["ok"]);
                Assert.Equal("not_found", Client.ErrCode(c.Call("positions.unsubscribe", new Map().Put("sub", sub))));
            }
        }
    }

    [Fact]
    public void Events_subscription_delivers_only_requested_types_and_validates()
    {
        using (var h = new Harness())
        using (var c = h.Connect())
        {
            c.Hello();
            Assert.Equal("invalid_args", Client.ErrCode(c.Call("events.subscribe", new Map().Put("types", new List<object>()))));
            Assert.Equal("unsupported", Client.ErrCode(c.Call("events.subscribe", new Map().Put("types", new List<object> { "advancement" }))));
            int demand = -1;
            h.Core.EventDemandChanged = m => demand = m;
            var r = c.Call("events.subscribe", new Map().Put("types", new List<object> { "chat", "join" }));
            long sub = (long)((Map)r["data"])["sub"];
            Assert.Equal("busy", Client.ErrCode(c.Call("events.subscribe", new Map().Put("types", new List<object> { "leave" }))));
            Assert.Equal(PlayerManagerRust.EventBit("chat") | PlayerManagerRust.EventBit("join"), demand);
            Assert.True(h.Core.Wants("chat")); Assert.False(h.Core.Wants("damage"));
            h.Core.Emit("damage", new Map().Put("type", "damage"));
            h.Core.Emit("chat", new Map().Put("type", "chat").Put("message", "hi"));
            var f = c.Recv();
            Assert.Equal(sub, f["sub"]); Assert.Equal(0L, f["seq"]);
            Assert.Equal("chat", ((Map)f["data"])["type"]);
        }
    }

    [Fact]
    public void Event_queue_overflow_drops_oldest_and_reports()
    {
        var s = new PlayerManagerRust.EvSub();
        for (int i = 0; i < 1030; i++) s.Enqueue(new Map().Put("n", (long)i));
        int dropped;
        var items = s.Take(out dropped);
        Assert.Equal(1024, items.Count);
        Assert.Equal(6, dropped);
        Assert.Equal(6L, items[0]["n"]);
    }

    [Fact]
    public void Assets_are_empty_and_unavailable()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            var m = (Map)c.Call("assets.manifest", new Map())["data"];
            Assert.Empty((List<object>)m["entries"]);
            var mh = (string)m["manifest_hash"];
            var un = (Map)c.Call("assets.manifest", new Map().Put("since_hash", mh))["data"];
            Assert.True((bool)un["unchanged"]);
            var g = (Map)c.Call("assets.get", new Map().Put("keys", new List<object> { "item:rifle.ak" }))["data"];
            var a = (Map)((List<object>)g["assets"])[0];
            Assert.Equal("unavailable", a["render"]); Assert.Equal("no_art_on_server", a["unavailable_reason"]);
            Assert.Equal("invalid_args", Client.ErrCode(c.Call("assets.get", new Map().Put("keys", Enumerable.Range(0, 65).Select(i => (object)("k" + i)).ToList()))));
        }
    }

    [Fact]
    public void Oversize_frame_is_rejected_and_connection_closed()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            var raw = new byte[] { 0x01, 0x00, 0x00, 0x00 };           // 16 MiB header
            var ns = typeof(Client).GetField("ns", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance).GetValue(c) as NetworkStream;
            ns.Write(raw, 0, 4);
            var r = c.Recv();
            Assert.Equal("invalid_args", Client.ErrCode(r));
            Assert.Null(c.Recv());
        }
    }

    [Fact]
    public void Garbage_body_gets_error_and_connection_survives()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            var ns = typeof(Client).GetField("ns", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance).GetValue(c) as NetworkStream;
            var f = P.Framing.Wrap(new byte[] { 0xc1, 0xff });
            ns.Write(f, 0, f.Length);
            Assert.Equal("invalid_args", Client.ErrCode(c.Recv()));
            Assert.True((bool)c.Call("server.info")["ok"]);
        }
    }

    [Fact]
    public void Connection_limit_gives_busy()
    {
        using (var h = new Harness(tweak: cfg => cfg.MaxConnections = 8))
        {
            var cs = new List<Client>();
            try
            {
                for (int i = 0; i < 8; i++) { var c = h.Connect(); c.Hello(); cs.Add(c); }
                using (var extra = h.Connect())
                    Assert.Equal("busy", Client.ErrCode(extra.Recv()));
            }
            finally { foreach (var c in cs) c.Dispose(); }
        }
    }

    [Fact]
    public void Bridge_json_written_and_socket_removed_on_stop()
    {
        var h = new Harness();
        h.Transport.WriteBridgeJson("1.0.0");
        var j = Newtonsoft.Json.Linq.JObject.Parse(File.ReadAllText(Path.Combine(h.Dir, "bridge.json")));
        Assert.Equal("unix", (string)j["transport"]);
        Assert.Equal("rust", (string)j["game"]);
        Assert.Equal("oxide", (string)j["platform"]);
        Assert.Equal(1, (int)j["protocol"]);
        Assert.Equal(".playermanager/bridge.sock", (string)j["socket"]);
        Assert.Null(j["port"].Type == Newtonsoft.Json.Linq.JTokenType.Null ? null : "x");
        Assert.False(File.Exists(Path.Combine(h.Dir, "bridge.json.tmp")));
        Assert.True(File.Exists(Path.Combine(h.Dir, "bridge.sock")));
        h.Dispose();
    }

    private static int FreePort() { var l = new TcpListener(IPAddress.Loopback, 0); l.Start(); int p = ((IPEndPoint)l.LocalEndpoint).Port; l.Stop(); return p; }

    [Fact]
    public void Tcp_fallback_requires_token_in_first_hello()
    {
        int port = FreePort();
        using (var h = new Harness("tcp", port))
        {
            Assert.Equal("tcp", h.Transport.Mode);
            var token = File.ReadAllText(Path.Combine(h.Dir, "token")).Trim();
            Assert.Matches("^[0-9a-f]{64}$", token);
            using (var c = h.Connect())                                  // op before hello
            {
                Assert.Equal("unauthorized", Client.ErrCode(c.Call("server.info")));
                Assert.Null(c.Recv());                                    // closed
            }
            using (var c = h.Connect())                                  // wrong token
            {
                Assert.Equal("unauthorized", Client.ErrCode(c.Hello("0".PadRight(64, '0'))));
                Assert.Null(c.Recv());
            }
            using (var c = h.Connect())                                  // no token
            {
                Assert.Equal("unauthorized", Client.ErrCode(c.Hello()));
            }
            using (var c = h.Connect())
            {
                Assert.True((bool)c.Hello(token)["ok"]);
                Assert.True((bool)c.Call("server.info")["ok"]);
            }
            // token is kept across restarts
            Assert.Equal(token, PlayerManagerRust.Transport.ReadOrCreateToken(Path.Combine(h.Dir, "token"), s => { }));
        }
    }

    [Fact]
    public void Token_file_is_mode_0600_on_unix()
    {
        if (!(Environment.OSVersion.Platform == PlatformID.Unix)) return;
        var dir = Path.Combine(Path.GetTempPath(), "pmtok" + Guid.NewGuid().ToString("N").Substring(0, 6));
        Directory.CreateDirectory(dir);
        try
        {
            var p = Path.Combine(dir, "token");
            PlayerManagerRust.Transport.ReadOrCreateToken(p, s => { });
            var mode = File.GetUnixFileMode(p);
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, mode);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Idle_connections_are_not_closed_while_active()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            Thread.Sleep(300);
            Assert.True((bool)c.Call("server.info")["ok"]);
        }
    }
}
