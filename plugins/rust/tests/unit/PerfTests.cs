using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using Oxide.Plugins;
using Xunit;
using Map = Oxide.Plugins.PlayerManagerRust.Map;
using P = Oxide.Plugins.PlayerManagerRust.Protocol;
using Core = Oxide.Plugins.PlayerManagerRust.BridgeCore;

/// <summary>Main-thread cost features: read coalescing, wake-driven drain pump, encoder equivalence.</summary>
public class PerfTests
{
    const string Bob = "76561198000000001";
    const string Eve = "76561198000000002";

    static Map GetArgs(string id, bool sens = false, params string[] fields)
    {
        var a = new Map().Put("id", id);
        if (sens) a["include_sensitive"] = true;
        if (fields.Length > 0) a["fields"] = fields.Cast<object>().ToList();
        return a;
    }

    [Fact]
    public void Identical_reads_in_flight_share_one_game_thread_computation()
    {
        using (var h = new Harness()) using (var a = h.Connect()) using (var b = h.Connect())
        {
            a.Hello(); b.Hello();
            h.Game.Block = true;
            for (long i = 0; i < 4; i++) a.Send(new Map().Put("id", 100 + i).Put("op", "player.get").Put("args", GetArgs(Bob)));
            b.Send(new Map().Put("id", 200L).Put("op", "player.get").Put("args", GetArgs(Bob)));
            Thread.Sleep(100);
            h.Game.Block = false;
            var seen = new List<Map>();
            for (int i = 0; i < 4; i++) seen.Add(a.Recv());
            seen.Add(b.Recv());
            Assert.All(seen, r => Assert.True((bool)r["ok"]));
            Assert.Equal(1, h.Game.PlayerGetCalls);
            // responses are identical to an uncached call (hash + fingerprint filled on the shared tree)
            var first = P.Pack.Encode(((Map)seen[0]["data"]), true);
            foreach (var r in seen) Assert.Equal(first, P.Pack.Encode((Map)r["data"], true));
            Assert.True(((Map)((Map)seen[0]["data"])["held_item"]).ContainsKey("fingerprint"));
        }
    }

    [Fact]
    public void Finished_read_is_reused_until_it_expires()
    {
        using (var h = new Harness(tweak: c => c.ReadCoalesceMs = 300)) using (var c = h.Connect())
        {
            c.Hello();
            var args = GetArgs(Bob);
            var r1 = c.Call("player.get", args);
            var r2 = c.Call("player.get", GetArgs(Bob));
            Assert.Equal(1, h.Game.PlayerGetCalls);
            Assert.Equal(P.Pack.Encode(r1["data"], true), P.Pack.Encode(r2["data"], true));
            Thread.Sleep(400);
            c.Call("player.get", GetArgs(Bob));
            Assert.Equal(2, h.Game.PlayerGetCalls);
        }
    }

    [Fact]
    public void Sensitive_and_field_selections_are_never_mixed()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            var plain = c.Call("player.get", GetArgs(Bob));
            var sens = c.Call("player.get", GetArgs(Bob, true));
            var fields = c.Call("player.get", GetArgs(Bob, false, "health"));
            Assert.Equal(3, h.Game.PlayerGetCalls);
            Assert.False(((Map)plain["data"]).ContainsKey("ip"));
            Assert.Equal("9.9.9.9", (string)((Map)sens["data"])["ip"]);
            // same args in a different key order are the same read
            var again = c.Call("player.get", new Map().Put("include_sensitive", true).Put("id", Bob));
            Assert.Equal(3, h.Game.PlayerGetCalls);
            Assert.Equal("9.9.9.9", (string)((Map)again["data"])["ip"]);
            c.Call("players.list", new Map().Put("include_sensitive", true));
            c.Call("players.list", new Map());
            Assert.Equal(2, h.Game.PlayersListCalls);
        }
    }

    [Fact]
    public void Actions_and_disconnects_invalidate_only_the_touched_player()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            c.Call("player.get", GetArgs(Bob)); c.Call("player.get", GetArgs(Eve));
            Assert.Equal(2, h.Game.PlayerGetCalls);
            var act = c.Call("action.run", new Map().Put("action", "hurt").Put("target", Bob).Put("args", new Map().Put("amount", 5L)));
            Assert.True((bool)act["ok"]);
            c.Call("player.get", GetArgs(Eve));
            Assert.Equal(2, h.Game.PlayerGetCalls);      // Eve untouched
            c.Call("player.get", GetArgs(Bob));
            Assert.Equal(3, h.Game.PlayerGetCalls);      // Bob recomputed
            h.Core.InvalidateReads(Eve);                 // disconnect hook
            c.Call("player.get", GetArgs(Eve));
            Assert.Equal(4, h.Game.PlayerGetCalls);
            c.Call("player.get", GetArgs(Bob));
            Assert.Equal(4, h.Game.PlayerGetCalls);
            // roster-wide reads are dropped whenever any player is invalidated
            c.Call("players.list", new Map());
            h.Core.InvalidateReads(Bob);
            c.Call("players.list", new Map());
            Assert.Equal(2, h.Game.PlayersListCalls);
        }
    }

    [Fact]
    public void Errors_are_not_cached()
    {
        using (var h = new Harness()) using (var c = h.Connect())
        {
            c.Hello();
            var r1 = c.Call("player.get", GetArgs("76561198000000404"));
            var r2 = c.Call("player.get", GetArgs("76561198000000404"));
            Assert.False((bool)r1["ok"]); Assert.False((bool)r2["ok"]);
            Assert.Equal(2, h.Game.PlayerGetCalls);
        }
    }

    [Fact]
    public void Main_work_wakes_the_pump_once_and_idle_costs_nothing()
    {
        var cfg = new PlayerManagerRust.BridgeConfig();
        var core = new Core(new FakeProvider(), cfg, "1.0.0", false);
        int wakes = 0;
        core.OnMainWork = () => Interlocked.Increment(ref wakes);
        Thread.Sleep(50);
        Assert.Equal(0, wakes);                          // nothing queued: nothing scheduled
        int ran = 0;
        for (int i = 0; i < 5; i++) core.RunOnMain(() => ran++);
        Assert.Equal(1, wakes);                          // five enqueues, one wake
        Assert.False(core.DrainStep(20, 4.0));           // drained completely
        Assert.Equal(5, ran);
        core.RunOnMain(() => ran++);
        Assert.Equal(2, wakes);                          // re-armed after the drain
        for (int i = 0; i < 30; i++) core.RunOnMain(() => ran++);
        Assert.True(core.DrainStep(20, 4.0));            // backlog beyond the batch: pump again shortly
        Assert.False(core.DrainStep(20, 4.0));
        Assert.Equal(36, ran);
    }

    [Fact]
    public void Encoder_reuse_and_framed_form_match_the_plain_encoder()
    {
        var m = new Map().Put("id", 7L).Put("ok", true).Put("data", new Map().Put("name", "Zoë 日本").Put("list", new List<object> { 1L, "x", 2.5, null, new byte[] { 1, 2 } }));
        var plain = P.Pack.Encode(m, false);
        var framed = P.Pack.EncodeFramed(m);
        Assert.Equal(P.Framing.Wrap(plain), framed);
        Assert.Equal(plain, P.Pack.Encode(m, false));    // reused buffer gives the same bytes on every call
        var big = new Map().Put("s", new string('é', 70000));
        Assert.Equal(P.Pack.Encode(big, false), P.Pack.Encode(big, false));
        Assert.Equal(plain, P.Pack.Encode(m, false));
    }
}
