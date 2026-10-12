using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Oxide.Plugins;
using Xunit;
using Map = Oxide.Plugins.PlayerManagerRust.Map;
using P = Oxide.Plugins.PlayerManagerRust.Protocol;

public class MsgPackTests
{
    private static string Hex(byte[] b) { return string.Join(" ", b.Select(x => x.ToString("x2"))); }

    [Fact]
    public void Hash_test_vector_corrected_D17()
    {
        // D17: "minecraft:stone" is 15 bytes -> fixstr header 0xaf (the doc's 0xae is malformed).
        var bytes = P.Pack.Encode(new Map().Put("id", "minecraft:stone"), true);
        Assert.Equal("81 a2 69 64 af 6d 69 6e 65 63 72 61 66 74 3a 73 74 6f 6e 65", Hex(bytes));
        Assert.Equal("90d892ebcdb67d59", P.Canon.RenderHash(new Map().Put("id", "minecraft:stone")));
    }

    [Fact]
    public void Documented_erratum_bytes_hash_to_the_documented_value()
    {
        // Documented-erratum check: the bytes printed in items-and-assets.md section 2 (with 0xae) hash to 027f2bef638386bd.
        var doc = new byte[] { 0x81, 0xa2, 0x69, 0x64, 0xae, 0x6d, 0x69, 0x6e, 0x65, 0x63, 0x72, 0x61, 0x66, 0x74, 0x3a, 0x73, 0x74, 0x6f, 0x6e, 0x65 };
        Assert.Equal("027f2bef638386bd", P.Canon.Hex(P.Canon.Sha256(doc), 16));
    }

    [Fact]
    public void Rust_render_subset_is_id_and_skin_only()
    {
        var rec = new Map().Put("id", "rifle.ak").Put("count", 1L).Put("skin", 1234567890UL).Put("condition", 80.5);
        var s = P.Canon.RenderSubset(rec);
        Assert.Equal(new[] { "id", "skin" }, s.Keys.OrderBy(k => k).ToArray());
        rec.Remove("skin");
        Assert.Equal(new[] { "id" }, P.Canon.RenderSubset(rec).Keys.ToArray());
        Assert.Equal(P.Canon.RenderHash(new Map().Put("id", "rifle.ak")), P.Canon.RenderHash(P.Canon.RenderSubset(rec)));
    }

    [Theory]
    [InlineData(0L, "00")] [InlineData(127L, "7f")] [InlineData(128L, "cc 80")] [InlineData(255L, "cc ff")] [InlineData(256L, "cd 01 00")]
    [InlineData(65535L, "cd ff ff")] [InlineData(65536L, "ce 00 01 00 00")] [InlineData(4294967295L, "ce ff ff ff ff")]
    [InlineData(4294967296L, "cf 00 00 00 01 00 00 00 00")]
    [InlineData(-1L, "ff")] [InlineData(-32L, "e0")] [InlineData(-33L, "d0 df")] [InlineData(-128L, "d0 80")] [InlineData(-129L, "d1 ff 7f")]
    [InlineData(-32768L, "d1 80 00")] [InlineData(-32769L, "d2 ff ff 7f ff")] [InlineData(-2147483648L, "d2 80 00 00 00")]
    [InlineData(-2147483649L, "d3 ff ff ff ff 7f ff ff ff")]
    public void Integers_use_smallest_encoding(long v, string hex)
    {
        Assert.Equal(hex, Hex(P.Pack.Encode(v, false)));
        Assert.Equal(v, P.Pack.Decode(P.Pack.Encode(v, false), 0, P.Pack.Encode(v, false).Length));
    }

    [Fact]
    public void Large_unsigned_uses_uint64()
    {
        Assert.Equal("cf ff ff ff ff ff ff ff ff", Hex(P.Pack.Encode(ulong.MaxValue, false)));
        var b = P.Pack.Encode(ulong.MaxValue, false);
        Assert.Equal(ulong.MaxValue, P.Pack.Decode(b, 0, b.Length));
    }

    [Fact]
    public void Canonical_sorts_keys_bytewise_at_every_level_and_drops_nulls()
    {
        var m = new Map().Put("b", 1L).Put("a", new Map().Put("z", 1L).Put("y", null).Put("x", 2L)).Put("é", 3L);
        var enc = P.Pack.Encode(m, true);
        // keys: "a" (0x61), "b" (0x62), "é" (c3 a9)
        Assert.Equal("83 a1 61 82 a1 78 02 a1 7a 01 a1 62 01 a2 c3 a9 03", Hex(enc));
    }

    [Fact]
    public void Floats_are_float64_on_the_wire_and_ints_when_integral_in_canonical_mode()
    {
        Assert.Equal("cb 40 59 00 00 00 00 00 00", Hex(P.Pack.Encode(100.0, false)));
        Assert.Equal("64", Hex(P.Pack.Encode(100.0, true)));
        Assert.Equal("cb 40 57 20 00 00 00 00 00", Hex(P.Pack.Encode(92.5, true)));
        Assert.Equal("cb 40 57 20 00 00 00 00 00", Hex(P.Pack.Encode(92.5f, false)));
    }

    [Fact]
    public void Strings_use_fixstr_str8_str16()
    {
        Assert.Equal("a0", Hex(P.Pack.Encode("", false)));
        Assert.StartsWith("bf ", Hex(P.Pack.Encode(new string('a', 31), false)));
        Assert.StartsWith("d9 20 ", Hex(P.Pack.Encode(new string('a', 32), false)));
        Assert.StartsWith("da 01 00 ", Hex(P.Pack.Encode(new string('a', 256), false)));
    }

    [Fact]
    public void Roundtrip_nested_structures_binary_and_utf8()
    {
        var m = new Map().Put("id", 7L).Put("ok", true).Put("name", "Zoë 世界")
            .Put("bin", new byte[] { 1, 2, 3 }).Put("list", new List<object> { 1L, "x", null, 2.5, new Map().Put("k", false) });
        var enc = P.Pack.Encode(m, false);
        var dec = (Map)P.Pack.Decode(enc, 0, enc.Length);
        Assert.Equal(7L, dec["id"]);
        Assert.Equal("Zoë 世界", dec["name"]);
        Assert.Equal(new byte[] { 1, 2, 3 }, (byte[])dec["bin"]);
        var l = (List<object>)dec["list"];
        Assert.Equal(5, l.Count);
        Assert.Null(l[2]);
        Assert.Equal(2.5, l[3]);
        Assert.False((bool)((Map)l[4])["k"]);
    }

    [Fact]
    public void Decoder_rejects_garbage_without_throwing_other_exceptions()
    {
        foreach (var bad in new[] { new byte[] { 0xc1 }, new byte[] { 0x91 }, new byte[] { 0x81, 0x01, 0x01 }, new byte[] { 0xdd, 0xff, 0xff, 0xff, 0xff }, new byte[] { 0xa5, 0x61 }, new byte[] { 0x01, 0x02 } })
        {
            var e = Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Pack.Decode(bad, 0, bad.Length));
            Assert.Equal("invalid_args", e.Code);
        }
    }

    [Fact]
    public void Decoder_depth_limit()
    {
        var b = Enumerable.Repeat((byte)0x91, 100).Concat(new byte[] { 0xc0 }).ToArray();
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Pack.Decode(b, 0, b.Length));
    }
}

public class FingerprintTests
{
    private static PlayerManagerRust.ItemMap Rifle(double cond, long childSlot)
    {
        var child = new PlayerManagerRust.ItemMap();
        child["id"] = "weapon.mod.silencer"; child["count"] = 1L; child["slot"] = childSlot;
        var m = new PlayerManagerRust.ItemMap();
        m["id"] = "rifle.ak"; m["count"] = 1L; m["slot"] = 3L; m["condition"] = cond; m["skin"] = 5UL;
        m["contents"] = new List<object> { child };
        return m;
    }

    [Fact]
    public void Fingerprint_is_64_hex_and_ignores_slots_hash_fingerprint_truncated()
    {
        var a = Rifle(80.5, 0);
        var f = P.Canon.Fingerprint(a);
        Assert.Matches("^[0-9a-f]{64}$", f);
        var b = Rifle(80.5, 4);                    // different slot of the child and of the item itself
        b["slot"] = 9L; b["hash"] = "x"; b["fingerprint"] = "y"; b["truncated"] = true;
        Assert.Equal(f, P.Canon.Fingerprint(b));
    }

    [Fact]
    public void Fingerprint_changes_with_content()
    {
        Assert.NotEqual(P.Canon.Fingerprint(Rifle(80.5, 0)), P.Canon.Fingerprint(Rifle(80.0, 0)));
    }

    [Fact]
    public void Finalize_fills_hash_and_fingerprint_recursively()
    {
        var resp = new Map().Put("item", Rifle(80.5, 0));
        P.Canon.Finalize(resp);
        var it = (Map)resp["item"];
        Assert.Equal(16, ((string)it["hash"]).Length);
        Assert.Equal(64, ((string)it["fingerprint"]).Length);
        var child = (Map)((List<object>)it["contents"])[0];
        Assert.Equal(16, ((string)child["hash"]).Length);
        // fingerprint must not depend on the (post-finalize) hashes of children
        Assert.Equal(it["fingerprint"], P.Canon.Fingerprint(Rifle(80.5, 0)));
    }
}

public class FramingTests
{
    [Fact]
    public void Roundtrip_and_eof()
    {
        var body = new byte[] { 1, 2, 3, 4, 5 };
        var framed = P.Framing.Wrap(body);
        Assert.Equal(new byte[] { 0, 0, 0, 5, 1, 2, 3, 4, 5 }, framed);
        var ms = new MemoryStream(framed);
        Assert.Equal(body, P.Framing.Read(ms, 1024));
        Assert.Null(P.Framing.Read(ms, 1024));
    }

    [Fact]
    public void Oversize_and_truncated()
    {
        var big = new MemoryStream(new byte[] { 0, 0x40, 0, 1 });
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Framing.Read(big, 4194304));
        Assert.Throws<EndOfStreamException>(() => P.Framing.Read(new MemoryStream(new byte[] { 0, 0, 0, 9, 1 }), 1024));
        Assert.Throws<EndOfStreamException>(() => P.Framing.Read(new MemoryStream(new byte[] { 0, 0 }), 1024));
    }

    [Fact]
    public void Four_mebibytes_exactly_is_accepted()
    {
        var body = new byte[4194304];
        var ms = new MemoryStream(P.Framing.Wrap(body));
        Assert.Equal(4194304, P.Framing.Read(ms, 4194304).Length);
    }
}

public class QuantTests
{
    [Theory]
    [InlineData(0.0, 0)] [InlineData(1.0, 16)] [InlineData(12.5, 200)] [InlineData(-80.25, -1284)] [InlineData(0.03, 0)] [InlineData(0.04, 1)] [InlineData(-0.04, -1)]
    [InlineData(2250.0, 36000)]
    public void Q16(double v, int expected) { Assert.Equal(expected, P.Quant.Q16(v)); }

    [Fact] public void Q100() { Assert.Equal(9000, P.Quant.Q100(90.0)); Assert.Equal(-1, P.Quant.Q100(-0.006)); }
    [Fact] public void Q16_clamps() { Assert.Equal(int.MaxValue, P.Quant.Q16(1e12)); Assert.Equal(int.MinValue, P.Quant.Q16(-1e12)); }
}

public class ArgValidationTests
{
    [Theory]
    [InlineData("76561198000000000", true)] [InlineData("7656119800000000", false)] [InlineData("765611980000000000", false)]
    [InlineData("7656119800000000a", false)] [InlineData("@a", false)] [InlineData("", false)] [InlineData(null, false)]
    [InlineData("76561198000000000\n", false)] [InlineData("-6561198000000000", false)]
    public void SteamId(string s, bool ok) { Assert.Equal(ok, P.Check.IsSteamId(s)); }

    [Theory]
    [InlineData("rifle.ak", true)] [InlineData("ammo.rifle", true)] [InlineData("wood", true)] [InlineData("Rifle", false)]
    [InlineData("a b", false)] [InlineData("a;b", false)] [InlineData("a\nb", false)] [InlineData("", false)] [InlineData("@a", false)]
    public void Shortname(string s, bool ok) { Assert.Equal(ok, P.Check.IsShortname(s)); }

    [Fact]
    public void Text_rejects_control_chars_and_long()
    {
        Assert.Equal("ok", P.Check.Text("ok", "reason", 10));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Text("a\nb", "reason", 10));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Text("a\rb", "reason", 10));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Text(new string('x', 11), "reason", 10));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Text(5L, "reason", 10));
    }

    [Fact]
    public void Number_and_integer_bounds()
    {
        Assert.Equal(5.5, P.Check.Number(5.5, "a", 0, 10));
        Assert.Equal(5.0, P.Check.Number(5L, "a", 0, 10));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Number(11L, "a", 0, 10));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Number(double.NaN, "a", 0, 10));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Number(double.PositiveInfinity, "a", null, null));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Number("5", "a", 0, 10));
        Assert.Equal(64000L, P.Check.Integer(64000L, "n", 1, 64000));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Integer(0L, "n", 1, 64000));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Integer(1.5, "n", 1, 64000));
        Assert.Equal(3L, P.Check.Integer(3.0, "n", 1, 64000));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Integer(true, "n", 1, 64000));
    }

    [Fact]
    public void Slot_accepts_int_or_decimal_string_never_hotbar_form()
    {
        Assert.Equal(3, P.Check.Slot(3L, "slot"));
        Assert.Equal(12, P.Check.Slot("12", "slot"));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Slot("hotbar:0", "slot"));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Slot("-1", "slot"));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => P.Check.Slot(-1L, "slot"));
    }

    private static Map Spec()
    {
        Func<string, string, object, object, Map> f = (k, kind, min, max) => new Map().Put("key", k).Put("label", k).Put("kind", kind).Put("choices", new List<string>()).Put("min", min).Put("max", max);
        return new Map().Put("id", "teleport").Put("target", true).Put("fields", new List<Map>
        {
            f("x", "number", -2250, 2250), f("y", "number", -100, 1500), f("z", "number", -2250, 2250), f("to_player", "target", null, null), f("item", "identifier", null, null), f("amount", "integer", 1, 64000), f("reason", "text", null, null)
        });
    }

    [Fact]
    public void Action_args_validated_against_spec_unknown_keys_dropped()
    {
        var ok = PlayerManagerRust.BridgeCore.ValidateActionArgs(Spec(), new Map().Put("x", 10L).Put("y", 5.5).Put("z", -2250L).Put("junk", "ignored").Put("confirmed", true));
        Assert.Equal(3, ok.Count);
        Assert.Throws<PlayerManagerRust.ProtoException>(() => PlayerManagerRust.BridgeCore.ValidateActionArgs(Spec(), new Map().Put("x", 2251L)));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => PlayerManagerRust.BridgeCore.ValidateActionArgs(Spec(), new Map().Put("to_player", "@a")));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => PlayerManagerRust.BridgeCore.ValidateActionArgs(Spec(), new Map().Put("item", "rifle.ak; quit")));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => PlayerManagerRust.BridgeCore.ValidateActionArgs(Spec(), new Map().Put("amount", 0L)));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => PlayerManagerRust.BridgeCore.ValidateActionArgs(Spec(), new Map().Put("reason", "bye\nquit")));
        Assert.Throws<PlayerManagerRust.ProtoException>(() => PlayerManagerRust.BridgeCore.ValidateActionArgs(Spec(), new Map().Put("y", double.NaN)));
    }
}

public class ConfigTests
{
    [Fact]
    public void Default_json_parses_to_defaults()
    {
        var c = PlayerManagerRust.BridgeConfig.Parse(PlayerManagerRust.BridgeConfig.DefaultJson);
        Assert.Null(c.EnabledOps);
        Assert.Equal(250, c.PositionsMinIntervalMs);
        Assert.Equal(8, c.MaxConnections);
        Assert.Equal(25590, c.TcpFallbackPort);
        Assert.Equal("auto", c.Transport);
        Assert.True(c.ExportAddress);
        Assert.Contains("login", c.RedactCommands);
    }

    [Fact]
    public void Enabled_ops_and_disabled_actions()
    {
        var c = PlayerManagerRust.BridgeConfig.Parse("{\"enabled_ops\":[\"players.list\"],\"disabled_actions\":[\"kill\"]}");
        Assert.True(c.OpEnabled("hello"));
        Assert.True(c.OpEnabled("players.list"));
        Assert.False(c.OpEnabled("action.run"));
        Assert.Contains("kill", c.DisabledActions);
    }
}
