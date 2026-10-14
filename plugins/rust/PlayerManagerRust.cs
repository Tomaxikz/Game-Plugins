// PlayerManagerRust - Player Manager Bridge for Rust (Oxide/uMod and Carbon). One file serves both frameworks.
//
// Replaces the old file-export plugin of the same name. It
//   * serves the Player Manager bridge protocol (docs/protocol, MessagePack frames) over a Unix socket
//     at .playermanager/bridge.sock, with a TCP+token fallback, and
//   * keeps writing the legacy export file (oxide|carbon/data/PlayerManagerRust.json) and the legacy
//     server-console commands (pm.hurt, pm.heal, pm.give, pm.setmetabolism) for panels that still use them.
//     (The panel's ready-check greps this file for the marker string "pm.hurt".)
//
// Install as oxide/plugins/PlayerManagerRust.cs or carbon/plugins/PlayerManagerRust.cs (file name = class name).
// No outbound network calls, no telemetry, no Harmony, no assets are bundled.
//
// Code map (search for the #region names):
//   Protocol   MessagePack, canonical hash/fingerprint, framing, quantisation, argument validation
//   Core       connections, dispatch, main-thread queue, deadlines, subscriptions
//   Transport  Unix socket / TCP listener, bridge.json, token, config.json
//   Provider   everything that touches Rust (main thread only)
//   Plugin     Oxide/Carbon hooks, timers, legacy export, legacy console commands
using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using Oxide.Core;
using Oxide.Core.Plugins;
using UnityEngine;

namespace Oxide.Plugins
{
    [Info("PlayerManagerRust", "PlayerManager", "1.0.0")] // @VERSION@ (build.sh stamps the version here)
    [Description("Player Manager bridge: live player data and typed admin actions over a local socket.")]
    public class PlayerManagerRust : RustPlugin
    {
        public const string BridgeVersionDefault = "1.0.0"; // @VERSION@
        public const int ProtocolVersion = 1;
        public const int MaxFrame = 4194304;

        // ---- shared protocol value types ----
        /// <summary>String-keyed map used for every protocol object.</summary>
        public class Map : Dictionary<string, object>
        {
            public Map Put(string k, object v) { this[k] = v; return this; }
            public Map PutIf(bool cond, string k, object v) { if (cond) this[k] = v; return this; }
        }

        /// <summary>An encoded item record; hash and fingerprint are added off-thread by Canon.Finalize.</summary>
        public sealed class ItemMap : Map { }

        public sealed class ProtoException : Exception
        {
            public readonly string Code;
            public ProtoException(string code, string message) : base(message) { Code = code; }
        }

        public static ProtoException Err(string code, string msg) { return new ProtoException(code, msg); }


        #region Protocol

        /// <summary>Pure protocol pieces. No game types in here, so unit tests compile this file against stubs.</summary>
        public static class Protocol
        {
            #region MessagePack

            public static class Pack
            {
                private sealed class Buf
                {
                    public byte[] B = new byte[256];
                    public int N;
                    public void Ensure(int k)
                    {
                        if (N + k <= B.Length) return;
                        int nl = B.Length * 2;
                        while (nl < N + k) nl *= 2;
                        Array.Resize(ref B, nl);
                    }
                    public void U8(int v) { Ensure(1); B[N++] = (byte)v; }
                    public void U16(int v) { Ensure(2); B[N++] = (byte)(v >> 8); B[N++] = (byte)v; }
                    public void U32(uint v) { Ensure(4); B[N++] = (byte)(v >> 24); B[N++] = (byte)(v >> 16); B[N++] = (byte)(v >> 8); B[N++] = (byte)v; }
                    public void U64(ulong v) { U32((uint)(v >> 32)); U32((uint)v); }
                    public void Raw(byte[] b, int off, int len) { Ensure(len); Buffer.BlockCopy(b, off, B, N, len); N += len; }
                }

                /// <summary>Encode a value. canonical = sorted map keys, null map values dropped, integral floats as ints (hash input only).</summary>
                public static byte[] Encode(object v, bool canonical)
                {
                    var b = Scratch();
                    b.N = 0;
                    try
                    {
                        Enc(b, v, canonical, 0);
                        var r = new byte[b.N];
                        Buffer.BlockCopy(b.B, 0, r, 0, b.N);
                        return r;
                    }
                    finally { if (b.B.Length > (1 << 20)) b.B = new byte[4096]; }
                }

                /// <summary>Encode into one length-prefixed frame (4-byte big-endian length + body) with a single allocation.</summary>
                public static byte[] EncodeFramed(object v)
                {
                    var b = Scratch();
                    b.N = 4;
                    try
                    {
                        Enc(b, v, false, 0);
                        int len = b.N - 4;
                        b.B[0] = (byte)(len >> 24); b.B[1] = (byte)(len >> 16); b.B[2] = (byte)(len >> 8); b.B[3] = (byte)len;
                        var r = new byte[b.N];
                        Buffer.BlockCopy(b.B, 0, r, 0, b.N);
                        return r;
                    }
                    finally { if (b.B.Length > (1 << 20)) b.B = new byte[4096]; }
                }

                // One reusable encode buffer per thread (writer threads, hub thread, test threads). Enc never re-enters Encode.
                [ThreadStatic] private static Buf scratch;
                private static Buf Scratch() { return scratch ?? (scratch = new Buf()); }

                private static void Int(Buf b, long v)
                {
                    if (v >= 0)
                    {
                        if (v <= 127) b.U8((int)v);
                        else if (v <= 255) { b.U8(0xcc); b.U8((int)v); }
                        else if (v <= 65535) { b.U8(0xcd); b.U16((int)v); }
                        else if (v <= uint.MaxValue) { b.U8(0xce); b.U32((uint)v); }
                        else { b.U8(0xcf); b.U64((ulong)v); }
                    }
                    else
                    {
                        if (v >= -32) b.U8(0xe0 | (int)(v & 0x1f));
                        else if (v >= -128) { b.U8(0xd0); b.U8((int)(v & 0xff)); }
                        else if (v >= -32768) { b.U8(0xd1); b.U16((int)(v & 0xffff)); }
                        else if (v >= int.MinValue) { b.U8(0xd2); b.U32((uint)(int)v); }
                        else { b.U8(0xd3); b.U64((ulong)v); }
                    }
                }

                private static void UInt(Buf b, ulong v)
                {
                    if (v <= long.MaxValue) Int(b, (long)v);
                    else { b.U8(0xcf); b.U64(v); }
                }

                private static void Dbl(Buf b, double d, bool canon)
                {
                    if (canon && !double.IsNaN(d) && !double.IsInfinity(d) && Math.Floor(d) == d && Math.Abs(d) < 9.2e18)
                    {
                        Int(b, (long)d);
                        return;
                    }
                    b.U8(0xcb);
                    b.U64((ulong)BitConverter.DoubleToInt64Bits(d));
                }

                private static void Str(Buf b, string s)
                {
                    int n = Encoding.UTF8.GetByteCount(s);
                    if (n <= 31) b.U8(0xa0 | n);
                    else if (n <= 255) { b.U8(0xd9); b.U8(n); }
                    else if (n <= 65535) { b.U8(0xda); b.U16(n); }
                    else { b.U8(0xdb); b.U32((uint)n); }
                    b.Ensure(n);
                    Encoding.UTF8.GetBytes(s, 0, s.Length, b.B, b.N);   // straight into the buffer: no temporary byte[]
                    b.N += n;
                }

                private static void Bin(Buf b, byte[] d)
                {
                    int n = d.Length;
                    if (n <= 255) { b.U8(0xc4); b.U8(n); }
                    else if (n <= 65535) { b.U8(0xc5); b.U16(n); }
                    else { b.U8(0xc6); b.U32((uint)n); }
                    b.Raw(d, 0, n);
                }

                private static void ArrHeader(Buf b, int n)
                {
                    if (n <= 15) b.U8(0x90 | n);
                    else if (n <= 65535) { b.U8(0xdc); b.U16(n); }
                    else { b.U8(0xdd); b.U32((uint)n); }
                }

                private static void MapHeader(Buf b, int n)
                {
                    if (n <= 15) b.U8(0x80 | n);
                    else if (n <= 65535) { b.U8(0xde); b.U16(n); }
                    else { b.U8(0xdf); b.U32((uint)n); }
                }

                private static int CompareBytes(byte[] a, byte[] b)
                {
                    int n = Math.Min(a.Length, b.Length);
                    for (int i = 0; i < n; i++)
                        if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
                    return a.Length == b.Length ? 0 : (a.Length < b.Length ? -1 : 1);
                }

                private static void Enc(Buf b, object v, bool canon, int depth)
                {
                    if (depth > 40) throw new InvalidOperationException("msgpack: nesting too deep");
                    if (v == null) { b.U8(0xc0); return; }
                    var s = v as string;
                    if (s != null) { Str(b, s); return; }
                    if (v is bool) { b.U8((bool)v ? 0xc3 : 0xc2); return; }
                    if (v is int) { Int(b, (int)v); return; }
                    if (v is long) { Int(b, (long)v); return; }
                    if (v is double) { Dbl(b, (double)v, canon); return; }
                    if (v is float) { Dbl(b, (double)(float)v, canon); return; }
                    if (v is ulong) { UInt(b, (ulong)v); return; }
                    if (v is uint) { Int(b, (uint)v); return; }
                    if (v is short) { Int(b, (short)v); return; }
                    if (v is ushort) { Int(b, (ushort)v); return; }
                    if (v is byte) { Int(b, (byte)v); return; }
                    if (v is sbyte) { Int(b, (sbyte)v); return; }
                    var bin = v as byte[];
                    if (bin != null) { Bin(b, bin); return; }
                    var dict = v as IDictionary;
                    if (dict != null && !canon)
                    {
                        // Wire path: no per-map key list and no per-key byte[] (enumeration order is unchanged).
                        MapHeader(b, dict.Count);
                        foreach (DictionaryEntry e in dict)
                        {
                            var k = e.Key as string;
                            if (k == null) throw new InvalidOperationException("msgpack: map keys must be strings");
                            Str(b, k);
                            Enc(b, e.Value, false, depth + 1);
                        }
                        return;
                    }
                    if (dict != null)
                    {
                        var keys = new List<KeyValuePair<byte[], object>>();
                        foreach (DictionaryEntry e in dict)
                        {
                            var k = e.Key as string;
                            if (k == null) throw new InvalidOperationException("msgpack: map keys must be strings");
                            if (canon && e.Value == null) continue;
                            keys.Add(new KeyValuePair<byte[], object>(Encoding.UTF8.GetBytes(k), e.Value));
                        }
                        if (canon) keys.Sort((x, y) => CompareBytes(x.Key, y.Key));
                        MapHeader(b, keys.Count);
                        foreach (var kv in keys)
                        {
                            int n = kv.Key.Length;
                            if (n <= 31) b.U8(0xa0 | n);
                            else if (n <= 255) { b.U8(0xd9); b.U8(n); }
                            else if (n <= 65535) { b.U8(0xda); b.U16(n); }
                            else { b.U8(0xdb); b.U32((uint)n); }
                            b.Raw(kv.Key, 0, n);
                            Enc(b, kv.Value, canon, depth + 1);
                        }
                        return;
                    }
                    var ilist = v as IList;
                    if (ilist != null)
                    {
                        ArrHeader(b, ilist.Count);
                        for (int i = 0; i < ilist.Count; i++) Enc(b, ilist[i], canon, depth + 1);
                        return;
                    }
                    var en = v as IEnumerable;
                    if (en != null)
                    {
                        var items = new List<object>();
                        foreach (var o in en) items.Add(o);
                        ArrHeader(b, items.Count);
                        foreach (var o in items) Enc(b, o, canon, depth + 1);
                        return;
                    }
                    throw new InvalidOperationException("msgpack: unsupported type " + v.GetType().Name);
                }

                // ---- decoder ----
                public static object Decode(byte[] data, int offset, int length)
                {
                    int pos = offset;
                    int end = offset + length;
                    object v = Dec(data, ref pos, end, 0);
                    if (pos != end) throw Err("invalid_args", "trailing bytes after message");
                    return v;
                }

                private static void Need(int pos, int k, int end)
                {
                    if (k < 0 || pos + k > end) throw Err("invalid_args", "truncated message");
                }

                private static ulong BE(byte[] d, ref int pos, int n, int end)
                {
                    Need(pos, n, end);
                    ulong r = 0;
                    for (int i = 0; i < n; i++) r = (r << 8) | d[pos++];
                    return r;
                }

                private static string ReadStr(byte[] d, ref int pos, int n, int end)
                {
                    Need(pos, n, end);
                    string s;
                    try { s = new UTF8Encoding(false, true).GetString(d, pos, n); }
                    catch (ArgumentException) { throw Err("invalid_args", "invalid UTF-8"); }
                    pos += n;
                    return s;
                }

                private static object ReadArr(byte[] d, ref int pos, int n, int end, int depth)
                {
                    if (n > end - pos) throw Err("invalid_args", "array length exceeds message");
                    var l = new List<object>(n);
                    for (int i = 0; i < n; i++) l.Add(Dec(d, ref pos, end, depth + 1));
                    return l;
                }

                private static object ReadMap(byte[] d, ref int pos, int n, int end, int depth)
                {
                    if (n > (end - pos) / 2) throw Err("invalid_args", "map length exceeds message");
                    var m = new Map();
                    for (int i = 0; i < n; i++)
                    {
                        var k = Dec(d, ref pos, end, depth + 1) as string;
                        if (k == null) throw Err("invalid_args", "map keys must be strings");
                        m[k] = Dec(d, ref pos, end, depth + 1);
                    }
                    return m;
                }

                private static object Dec(byte[] d, ref int pos, int end, int depth)
                {
                    if (depth > 32) throw Err("invalid_args", "nesting too deep");
                    Need(pos, 1, end);
                    int t = d[pos++];
                    if (t <= 0x7f) return (long)t;
                    if (t >= 0xe0) return (long)(sbyte)t;
                    if (t >= 0xa0 && t <= 0xbf) return ReadStr(d, ref pos, t & 0x1f, end);
                    if (t >= 0x90 && t <= 0x9f) return ReadArr(d, ref pos, t & 0x0f, end, depth);
                    if (t >= 0x80 && t <= 0x8f) return ReadMap(d, ref pos, t & 0x0f, end, depth);
                    switch (t)
                    {
                        case 0xc0: return null;
                        case 0xc2: return false;
                        case 0xc3: return true;
                        case 0xc4: return Bytes(d, ref pos, (int)BE(d, ref pos, 1, end), end);
                        case 0xc5: return Bytes(d, ref pos, (int)BE(d, ref pos, 2, end), end);
                        case 0xc6: return Bytes(d, ref pos, SafeLen(BE(d, ref pos, 4, end)), end);
                        case 0xca: { uint u = (uint)BE(d, ref pos, 4, end); return (double)BitConverter.ToSingle(BitConverter.GetBytes(u), 0); }
                        case 0xcb: return BitConverter.Int64BitsToDouble((long)BE(d, ref pos, 8, end));
                        case 0xcc: return (long)BE(d, ref pos, 1, end);
                        case 0xcd: return (long)BE(d, ref pos, 2, end);
                        case 0xce: return (long)BE(d, ref pos, 4, end);
                        case 0xcf: { ulong u = BE(d, ref pos, 8, end); if (u <= long.MaxValue) return (long)u; return u; }
                        case 0xd0: return (long)(sbyte)BE(d, ref pos, 1, end);
                        case 0xd1: return (long)(short)BE(d, ref pos, 2, end);
                        case 0xd2: return (long)(int)BE(d, ref pos, 4, end);
                        case 0xd3: return (long)BE(d, ref pos, 8, end);
                        case 0xd9: return ReadStr(d, ref pos, (int)BE(d, ref pos, 1, end), end);
                        case 0xda: return ReadStr(d, ref pos, (int)BE(d, ref pos, 2, end), end);
                        case 0xdb: return ReadStr(d, ref pos, SafeLen(BE(d, ref pos, 4, end)), end);
                        case 0xdc: return ReadArr(d, ref pos, (int)BE(d, ref pos, 2, end), end, depth);
                        case 0xdd: return ReadArr(d, ref pos, SafeLen(BE(d, ref pos, 4, end)), end, depth);
                        case 0xde: return ReadMap(d, ref pos, (int)BE(d, ref pos, 2, end), end, depth);
                        case 0xdf: return ReadMap(d, ref pos, SafeLen(BE(d, ref pos, 4, end)), end, depth);
                    }
                    throw Err("invalid_args", "unsupported msgpack type 0x" + t.ToString("x2"));
                }

                private static int SafeLen(ulong n)
                {
                    if (n > int.MaxValue) throw Err("invalid_args", "length too large");
                    return (int)n;
                }

                private static byte[] Bytes(byte[] d, ref int pos, int n, int end)
                {
                    Need(pos, n, end);
                    var r = new byte[n];
                    Buffer.BlockCopy(d, pos, r, 0, n);
                    pos += n;
                    return r;
                }
            }

            #endregion

            #region Canonical hash and fingerprint

            public static class Canon
            {
                public static string Hex(byte[] b, int chars)
                {
                    var sb = new StringBuilder(b.Length * 2);
                    for (int i = 0; i < b.Length; i++) sb.Append(b[i].ToString("x2"));
                    return sb.ToString(0, Math.Min(chars, sb.Length));
                }

                public static byte[] Sha256(byte[] data)
                {
                    using (var h = SHA256.Create()) return h.ComputeHash(data);
                }

                /// <summary>Render hash: first 16 hex chars of SHA-256 over canonical msgpack of the render subset.</summary>
                public static string RenderHash(Map subset) { return Hex(Sha256(Pack.Encode(subset, true)), 16); }

                /// <summary>Render subset for a Rust record: id + skin (when non-zero).</summary>
                public static Map RenderSubset(Map rec)
                {
                    var s = new Map();
                    object o;
                    if (rec.TryGetValue("id", out o) && o != null) s["id"] = o;
                    if (rec.TryGetValue("skin", out o) && o != null && !IsZero(o)) s["skin"] = o;
                    return s;
                }

                private static bool IsZero(object o)
                {
                    if (o is long) return (long)o == 0;
                    if (o is ulong) return (ulong)o == 0;
                    if (o is int) return (int)o == 0;
                    return false;
                }

                /// <summary>Full 64-hex fingerprint of the record minus slot/hash/fingerprint/truncated (also inside contents).</summary>
                public static string Fingerprint(Map rec) { return Hex(Sha256(Pack.Encode(Strip(rec), true)), 64); }

                public static Map Strip(Map rec)
                {
                    var c = new Map();
                    foreach (var kv in rec)
                    {
                        if (kv.Key == "slot" || kv.Key == "hash" || kv.Key == "fingerprint" || kv.Key == "truncated") continue;
                        if (kv.Key == "contents" && kv.Value is IList)
                        {
                            var l = new List<object>();
                            foreach (var e in (IList)kv.Value)
                            {
                                var em = e as Map;
                                l.Add(em != null ? (object)Strip(em) : e);
                            }
                            c["contents"] = l;
                        }
                        else c[kv.Key] = kv.Value;
                    }
                    return c;
                }

                /// <summary>Walk a response tree and fill hash + fingerprint on every ItemMap. Runs on the writer thread.</summary>
                public static void Finalize(object node)
                {
                    var m = node as Map;
                    if (m != null)
                    {
                        if (m is ItemMap)
                        {
                            m["fingerprint"] = Fingerprint(m);
                            m["hash"] = RenderHash(RenderSubset(m));
                        }
                        foreach (var v in m.Values) Finalize(v);   // keys were added above, before enumeration starts
                        return;
                    }
                    if (node is string || node is byte[]) return;
                    var l = node as IEnumerable;
                    if (l != null && !(node is IDictionary)) foreach (var v in l) Finalize(v);
                }
            }

            #endregion

            #region Framing

            public static class Framing
            {
                public static byte[] Wrap(byte[] body)
                {
                    var r = new byte[body.Length + 4];
                    r[0] = (byte)(body.Length >> 24); r[1] = (byte)(body.Length >> 16); r[2] = (byte)(body.Length >> 8); r[3] = (byte)body.Length;
                    Buffer.BlockCopy(body, 0, r, 4, body.Length);
                    return r;
                }

                /// <summary>Reads one frame. Returns null on clean EOF before the first header byte. Throws on oversize / truncation.</summary>
                public static byte[] Read(Stream s, int maxFrame)
                {
                    var h = new byte[4];
                    int got = 0;
                    while (got < 4)
                    {
                        int r = s.Read(h, got, 4 - got);
                        if (r <= 0) { if (got == 0) return null; throw new EndOfStreamException("truncated frame header"); }
                        got += r;
                    }
                    long len = ((long)h[0] << 24) | ((long)h[1] << 16) | ((long)h[2] << 8) | h[3];
                    if (len > maxFrame) throw Err("invalid_args", "frame too large: " + len);
                    var body = new byte[len];
                    int g = 0;
                    while (g < len)
                    {
                        int r = s.Read(body, g, (int)len - g);
                        if (r <= 0) throw new EndOfStreamException("truncated frame body");
                        g += r;
                    }
                    return body;
                }
            }

            #endregion

            #region Quantisation

            public static class Quant
            {
                /// <summary>Stream coordinate: 1/16 metre, rounded half away from zero.</summary>
                public static int Q16(double v)
                {
                    double r = Math.Round(v * 16.0, MidpointRounding.AwayFromZero);
                    if (r > int.MaxValue) return int.MaxValue;
                    if (r < int.MinValue) return int.MinValue;
                    return (int)r;
                }
                /// <summary>Stream angle: 1/100 degree.</summary>
                public static int Q100(double v) { return (int)Math.Round(v * 100.0, MidpointRounding.AwayFromZero); }
            }

            #endregion

            #region Argument validation

            public static class Check
            {
                public static bool IsSteamId(string s)
                {
                    if (s == null || s.Length != 17) return false;
                    for (int i = 0; i < 17; i++) if (s[i] < '0' || s[i] > '9') return false;
                    return true;
                }

                public static ulong SteamId(object o, string field)
                {
                    var s = o as string;
                    if (!IsSteamId(s)) throw Err("invalid_args", "'" + field + "' must be a 17-digit SteamID64 string");
                    return ulong.Parse(s, System.Globalization.CultureInfo.InvariantCulture);
                }

                public static bool IsShortname(string s)
                {
                    if (string.IsNullOrEmpty(s) || s.Length > 64) return false;
                    foreach (char c in s)
                        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) return false;
                    return true;
                }

                /// <summary>Free text (reasons): no control characters, bounded length.</summary>
                public static string Text(object o, string field, int max)
                {
                    var s = o as string;
                    if (s == null) throw Err("invalid_args", "'" + field + "' must be a string");
                    if (s.Length > max) throw Err("invalid_args", "'" + field + "' is too long (max " + max + ")");
                    foreach (char c in s)
                        if (c < 0x20 || c == 0x7f) throw Err("invalid_args", "'" + field + "' contains control characters");
                    return s;
                }

                public static double Number(object o, string field, double? min, double? max)
                {
                    double d;
                    if (o is long) d = (long)o;
                    else if (o is ulong) d = (ulong)o;
                    else if (o is int) d = (int)o;
                    else if (o is double) d = (double)o;
                    else if (o is float) d = (float)o;
                    else throw Err("invalid_args", "'" + field + "' must be a number");
                    if (double.IsNaN(d) || double.IsInfinity(d)) throw Err("invalid_args", "'" + field + "' must be finite");
                    if (min.HasValue && d < min.Value) throw Err("invalid_args", "'" + field + "' must be >= " + min.Value.ToString(System.Globalization.CultureInfo.InvariantCulture));
                    if (max.HasValue && d > max.Value) throw Err("invalid_args", "'" + field + "' must be <= " + max.Value.ToString(System.Globalization.CultureInfo.InvariantCulture));
                    return d;
                }

                public static long Integer(object o, string field, long? min, long? max)
                {
                    long v;
                    if (o is long) v = (long)o;
                    else if (o is int) v = (int)o;
                    else if (o is ulong && (ulong)o <= long.MaxValue) v = (long)(ulong)o;
                    else if (o is double && Math.Floor((double)o) == (double)o && Math.Abs((double)o) < 9e15) v = (long)(double)o;
                    else throw Err("invalid_args", "'" + field + "' must be an integer");
                    if (min.HasValue && v < min.Value) throw Err("invalid_args", "'" + field + "' must be >= " + min.Value);
                    if (max.HasValue && v > max.Value) throw Err("invalid_args", "'" + field + "' must be <= " + max.Value);
                    return v;
                }

                /// <summary>Numbered slots: int or decimal string (D4).</summary>
                public static int Slot(object o, string field)
                {
                    var s = o as string;
                    if (s != null)
                    {
                        int r;
                        if (s.Length == 0 || s.Length > 6 || !int.TryParse(s, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out r))
                            throw Err("invalid_args", "'" + field + "' must be an integer slot");
                        return r;
                    }
                    return (int)Integer(o, field, 0, 100000);
                }

                public static bool Bool(Map args, string key, bool def)
                {
                    object o;
                    if (args == null || !args.TryGetValue(key, out o) || o == null) return def;
                    if (!(o is bool)) throw Err("invalid_args", "'" + key + "' must be a boolean");
                    return (bool)o;
                }
            }

            #endregion
        }

        #endregion

        #region BridgeCore

        public const int MaxInflight = 16;
        public static readonly string[] EventTypes = { "join", "leave", "death", "chat", "command", "damage", "kick", "ban" };

        public static int EventBit(string type)
        {
            for (int i = 0; i < EventTypes.Length; i++) if (EventTypes[i] == type) return 1 << i;
            return 0;
        }

        /// <summary>Plain copy of one player's position, taken on the game thread. State: 0 alive, 1 dead, 2 spectator.</summary>
        public struct PosSample
        {
            public string Id, Name, World;
            public float X, Y, Z;
            public byte State;
        }

        /// <summary>Static facts reported by hello; built on the game thread, read by workers.</summary>
        public sealed class HelloInfo
        {
            public string Platform = "oxide", PlatformVersion = "", GameVersion = "";
            public List<string> PlayerFields = new List<string>();
            public List<string> Events = new List<string>();
            public List<string> Integrations = new List<string>();
            public List<string> Features = new List<string>();
            public List<Map> Actions = new List<Map>();
        }

        /// <summary>Everything the protocol core needs from the game. Every method runs on the game (main) thread.</summary>
        public interface IGameProvider
        {
            HelloInfo Describe();
            Map ServerInfo();
            Map PlayersList(bool includeOffline, bool includeSensitive);
            Map PlayerGet(string id, HashSet<string> fields, bool includeSensitive);
            Map PlayerInventory(string id);
            Map ItemGet(string id, string container, int slot, int[] path);
            Map RunAction(string action, string target, Map args, Map expect);
            PosSample[] SamplePositions();
        }

        public sealed class BridgeConfig
        {
            public HashSet<string> EnabledOps;               // null = all
            public HashSet<string> DisabledActions = new HashSet<string>();
            public int PositionsMinIntervalMs = 250;
            public int MaxConnections = 8;
            public int TcpFallbackPort = 25590;
            public string Transport = "auto";               // auto | unix | tcp
            public List<string> RedactCommands = new List<string> { "login", "password", "passwd", "auth", "register", "pin", "pm.", "rcon", "server.rcon" };
            public bool ExportAddress = true;
            /// <summary>Identical read requests (server.info, players.list, player.get, player.inventory) in flight or finished within this window share one game-thread computation. 0 disables.</summary>
            public int ReadCoalesceMs = 500;

            public const string DefaultJson =
                "{\n  \"enabled_ops\": [\"*\"],\n  \"disabled_actions\": [],\n  \"positions_min_interval_ms\": 250,\n  \"max_connections\": 8,\n  \"tcp_fallback_port\": 25590,\n" +
                "  \"platform\": {\n    \"transport\": \"auto\",\n    \"redact_commands\": [\"login\", \"password\", \"passwd\", \"auth\", \"register\", \"pin\", \"pm.\", \"rcon\", \"server.rcon\"],\n    \"export_address\": true\n  }\n}\n";

            public bool OpEnabled(string op) { return op == "hello" || EnabledOps == null || EnabledOps.Contains(op); }

            public static BridgeConfig Parse(string json)
            {
                var c = new BridgeConfig();
                var o = JObject.Parse(json);
                var ops = o["enabled_ops"] as JArray;
                if (ops != null)
                {
                    var set = new HashSet<string>(ops.Select(t => (string)t));
                    c.EnabledOps = set.Contains("*") ? null : set;
                }
                var da = o["disabled_actions"] as JArray;
                if (da != null) c.DisabledActions = new HashSet<string>(da.Select(t => (string)t));
                JToken t2;
                if ((t2 = o["positions_min_interval_ms"]) != null) c.PositionsMinIntervalMs = Math.Max(50, Math.Min(60000, (int)t2));
                if ((t2 = o["max_connections"]) != null) c.MaxConnections = Math.Max(8, Math.Min(256, (int)t2));
                if ((t2 = o["tcp_fallback_port"]) != null) c.TcpFallbackPort = Math.Max(1, Math.Min(65535, (int)t2));
                var p = o["platform"] as JObject;
                if (p != null)
                {
                    if ((t2 = p["transport"]) != null) { var s = ((string)t2 ?? "auto").ToLowerInvariant(); if (s == "unix" || s == "tcp" || s == "auto") c.Transport = s; }
                    var rc = p["redact_commands"] as JArray;
                    if (rc != null) c.RedactCommands = rc.Select(t => ((string)t ?? "").ToLowerInvariant()).Where(s => s.Length > 0).ToList();
                    if ((t2 = p["export_address"]) != null) c.ExportAddress = (bool)t2;
                }
                return c;
            }
        }

        public sealed class Work
        {
            public Conn C;
            public long Id;
            public string Op;
            public Func<object> Run;
            public Action Direct;           // system work without a connection
            public long DeadlineMs;
            public int State;               // 0 pending, 1 finished
            public ReadEntry Entry;         // set on the leader of a coalesced read
            public bool Shared;             // response data is shared with other requests (see Conn.WriteOne)
        }

        /// <summary>One coalesced read: the leader computes on the game thread, followers (and later requests inside the window) reuse the result.</summary>
        public sealed class ReadEntry
        {
            public string Key, Player;
            public long At;
            public bool Done;
            public object Result;
            public ProtoException Error;
            public List<Work> Waiters = new List<Work>();
        }

        /// <summary>Frame whose data tree is shared between connections: hash/fingerprint filling and encoding are serialised.</summary>
        public sealed class SharedMap : Map { }

        public sealed class PosSub
        {
            public Conn C;
            public int Id;
            public int IntervalMs;
            public HashSet<string> Worlds;
            public long NextDueMs, LastKeyMs, LastFrameMs, Seq;
            public volatile bool ForceKey = true;
            public Dictionary<string, PosEntry> Last = new Dictionary<string, PosEntry>();
        }

        public struct PosEntry
        {
            public string Name, World;
            public int X, Y, Z;
            public byte State;
            public bool Same(PosEntry o) { return X == o.X && Y == o.Y && Z == o.Z && State == o.State && Name == o.Name && World == o.World; }
        }

        public sealed class EvSub
        {
            public Conn C;
            public int Id;
            public HashSet<string> Types;
            public long Seq, LastFrameMs;
            private readonly object ql = new object();
            private readonly LinkedList<Map> q = new LinkedList<Map>();
            private int dropped;
            public void Enqueue(Map data)
            {
                lock (ql)
                {
                    if (q.Count >= 1024) { q.RemoveFirst(); dropped++; }
                    q.AddLast(data);
                }
            }
            public List<Map> Take(out int drop)
            {
                lock (ql)
                {
                    drop = dropped; dropped = 0;
                    if (q.Count == 0) return null;
                    var l = new List<Map>(q);
                    q.Clear();
                    return l;
                }
            }
        }

        public sealed class Conn
        {
            private static readonly object SharedEncodeLock = new object();
            public readonly BridgeCore BridgeCore;
            public readonly string Remote;
            private readonly Stream stream;
            private readonly Socket sock;
            public volatile bool Authed;
            public int Inflight;
            public PosSub Pos;
            public EvSub Ev;
            private long lastActivity;
            private readonly Queue<object> outbox = new Queue<object>();
            private readonly SemaphoreSlim sem = new SemaphoreSlim(0);
            private volatile bool closed, closeAfterFlush;
            private int closedFlag;

            public Conn(BridgeCore core, Socket s, string remote)
            {
                BridgeCore = core; sock = s; Remote = remote;
                stream = new NetworkStream(s, false);
                Touch();
            }

            public Conn(BridgeCore core, Stream s, string remote)
            {
                BridgeCore = core; stream = s; Remote = remote;
                Touch();
            }

            public long LastActivityMs { get { return Interlocked.Read(ref lastActivity); } }
            public bool IsClosed { get { return closed; } }
            private void Touch() { Interlocked.Exchange(ref lastActivity, BridgeCore.NowMs); }

            public void Start()
            {
                var r = new Thread(ReadLoop) { IsBackground = true, Name = "pm-conn-read" };
                var w = new Thread(WriteLoop) { IsBackground = true, Name = "pm-conn-write" };
                r.Start(); w.Start();
            }

            public void SendOk(long id, object data) { Send(new Map().Put("id", id).Put("ok", true).Put("data", data ?? new Map())); }

            public void SendError(long id, string code, string msg)
            {
                Send(new Map().Put("id", id).Put("ok", false).Put("error", new Map().Put("code", code).Put("message", msg ?? "")));
            }

            public void Send(object frame)
            {
                if (closed) return;
                bool overflow;
                lock (outbox) { outbox.Enqueue(frame); overflow = outbox.Count > 8192; }
                if (overflow) { BridgeCore.Log("slow consumer " + Remote + ", closing"); Close(); return; }
                try { sem.Release(); } catch (ObjectDisposedException) { }
            }

            public void CloseAfterFlush() { closeAfterFlush = true; try { sem.Release(); } catch (ObjectDisposedException) { } }

            private void ReadLoop()
            {
                try
                {
                    while (!closed)
                    {
                        byte[] body;
                        try { body = Protocol.Framing.Read(stream, MaxFrame); }
                        catch (ProtoException e) { SendError(0, e.Code, e.Message); CloseAfterFlush(); return; }
                        if (body == null) break;
                        Touch();
                        Map req = null;
                        try
                        {
                            req = Protocol.Pack.Decode(body, 0, body.Length) as Map;
                            if (req == null) throw Err("invalid_args", "request must be a map");
                        }
                        catch (ProtoException e) { SendError(0, e.Code, e.Message); continue; }
                        BridgeCore.Handle(this, req);
                    }
                }
                catch (Exception) { /* connection reset, closed or truncated */ }
                finally { if (!closeAfterFlush) Close(); }   // otherwise the writer closes after flushing the error
            }

            private void WriteLoop()
            {
                try
                {
                    while (!closed)
                    {
                        sem.Wait(500);
                        while (true)
                        {
                            object o;
                            lock (outbox) { if (outbox.Count == 0) break; o = outbox.Dequeue(); }
                            WriteOne(o);
                        }
                        if (closeAfterFlush) { lock (outbox) { if (outbox.Count == 0) break; } }
                    }
                }
                catch (Exception) { }
                finally { Close(); }
            }

            private void WriteOne(object o)
            {
                byte[] body = o as byte[];
                if (body == null)
                {
                    var m = (Map)o;
                    byte[] enc = null;
                    try
                    {
                        if (m is SharedMap)
                        {
                            // The data tree is shared with other connections' writer threads: fill + encode under one lock.
                            lock (SharedEncodeLock) { Protocol.Canon.Finalize(m); enc = Protocol.Pack.Encode(m, false); }
                        }
                        else
                        {
                            Protocol.Canon.Finalize(m);
                            enc = Protocol.Pack.Encode(m, false);
                        }
                    }
                    catch (Exception e) { BridgeCore.Log("encode failed: " + e.Message); }
                    if (enc == null || enc.Length > MaxFrame)
                    {
                        object idv;
                        if (m.TryGetValue("id", out idv) && idv is long)
                            enc = Protocol.Pack.Encode(new Map().Put("id", idv).Put("ok", false).Put("error", new Map().Put("code", enc == null ? "internal" : "unsupported").Put("message", enc == null ? "response encoding failed" : "response too large")), false);
                        else return;
                    }
                    body = enc;
                }
                var frame = Protocol.Framing.Wrap(body);
                stream.Write(frame, 0, frame.Length);
                stream.Flush();
                Touch();
            }

            public void Close()
            {
                if (Interlocked.Exchange(ref closedFlag, 1) != 0) return;
                closed = true;
                try { sem.Release(); } catch (ObjectDisposedException) { }
                try { if (sock != null) { try { sock.Shutdown(SocketShutdown.Both); } catch { } sock.Close(); } else stream.Dispose(); } catch { }
                BridgeCore.OnClosed(this);
            }
        }

        public sealed class BridgeCore
        {
            public readonly IGameProvider Game;
            public readonly BridgeConfig Cfg;
            public readonly string BridgeVersion;
            public volatile bool TcpMode;
            public string Token;
            public Action<string> Log = delegate { };
            /// <summary>Called (from a worker thread) when the set of subscribed event types changes.</summary>
            public Action<int> EventDemandChanged;

            private volatile HelloInfo hello = new HelloInfo();
            private readonly ConcurrentQueue<Work> queue = new ConcurrentQueue<Work>();
            private readonly ConcurrentDictionary<Work, byte> pending = new ConcurrentDictionary<Work, byte>();
            private readonly Stopwatch clock = Stopwatch.StartNew();
            private readonly List<Conn> conns = new List<Conn>();
            private readonly object subLock = new object();
            private volatile PosSub[] posSubs = new PosSub[0];
            private volatile EvSub[] evSubs = new EvSub[0];
            private int nextSub;
            private Thread hubThread;
            private volatile bool running;
            private readonly AutoResetEvent signal = new AutoResetEvent(false);
            private volatile bool sampleInFlight;
            private PosSample[] newSample;
            private readonly object sampleLock = new object();
            private int eventMask;
            private int wakeArmed;
            private readonly object readLock = new object();
            private readonly Dictionary<string, ReadEntry> reads = new Dictionary<string, ReadEntry>();
            public int ReadHits, ReadMisses;
            /// <summary>Raised (from any thread) when work lands on an idle main-thread queue. The plugin pumps Drain from it, so no timer runs while idle.</summary>
            public Action OnMainWork;
            /// <summary>Raised when the first connection arrives / the last one leaves.</summary>
            public Action<bool> OnActive;
            public static readonly string ManifestHash = Protocol.Canon.Hex(Protocol.Canon.Sha256(Protocol.Pack.Encode(new List<object>(), true)), 16);

            public BridgeCore(IGameProvider game, BridgeConfig cfg, string bridgeVersion, bool tcpMode)
            {
                Game = game; Cfg = cfg; BridgeVersion = bridgeVersion; TcpMode = tcpMode;
            }

            public long NowMs { get { return clock.ElapsedMilliseconds; } }
            public int EventMask { get { return Volatile.Read(ref eventMask); } }
            public bool Wants(string type) { return (EventMask & EventBit(type)) != 0; }
            public int ConnectionCount { get { lock (conns) return conns.Count; } }
            public HelloInfo Hello { get { return hello; } }
            public void SetHello(HelloInfo h) { hello = h; }

            public void Start()
            {
                running = true;
                hubThread = new Thread(HubLoop) { IsBackground = true, Name = "pm-hub" };
                hubThread.Start();
            }

            public void Stop()
            {
                running = false;
                signal.Set();
                List<Conn> cs;
                lock (conns) cs = new List<Conn>(conns);
                foreach (var c in cs) c.Close();
                try { if (hubThread != null) hubThread.Join(1000); } catch { }
            }

            public bool AddConnection(Conn c)
            {
                lock (conns)
                {
                    if (conns.Count >= Cfg.MaxConnections) return false;
                    conns.Add(c);
                    if (conns.Count == 1 && OnActive != null) { try { OnActive(true); } catch (Exception e) { Log("active callback failed: " + e.Message); } }
                }
                signal.Set();
                return true;
            }

            public void OnClosed(Conn c)
            {
                bool nowIdle;
                lock (conns) { conns.Remove(c); nowIdle = conns.Count == 0; }
                if (nowIdle && OnActive != null) { try { OnActive(false); } catch (Exception e) { Log("active callback failed: " + e.Message); } }
                bool changed = false;
                lock (subLock)
                {
                    if (c.Pos != null) { posSubs = posSubs.Where(s => s != c.Pos).ToArray(); c.Pos = null; }
                    if (c.Ev != null) { evSubs = evSubs.Where(s => s != c.Ev).ToArray(); c.Ev = null; changed = true; }
                }
                if (changed) RecalcMask();
                // A coalesced leader keeps running for the other connections waiting on it; its reply to this closed connection is dropped.
                foreach (var w in pending.Keys) if (w.C == c && w.Entry == null) Complete(w, null, Err("internal", "connection closed"));
            }

            // ---------- main-thread queue ----------

            public void RunOnMain(Action a)
            {
                queue.Enqueue(new Work { Direct = a, DeadlineMs = long.MaxValue });
                NotifyMain();
            }

            private void NotifyMain()
            {
                if (Interlocked.CompareExchange(ref wakeArmed, 1, 0) != 0) return;
                var cb = OnMainWork;
                if (cb == null) return;
                try { cb(); } catch (Exception e) { Log("main wake failed: " + e.Message); }
            }

            /// <summary>One pump step for the plugin: drains within the budget; returns true when work remains (call again shortly).
            /// When it returns false the wake flag is re-armed, so the next enqueue calls OnMainWork again.</summary>
            public bool DrainStep(int maxItems, double budgetMs)
            {
                try { Drain(maxItems, budgetMs); }
                catch (Exception e) { Log("drain failed: " + e.Message); }
                if (!queue.IsEmpty) return true;
                Interlocked.Exchange(ref wakeArmed, 0);
                if (queue.IsEmpty) return false;
                return Interlocked.CompareExchange(ref wakeArmed, 1, 0) == 0;   // raced with an enqueue: keep pumping
            }

            /// <summary>Called by the main-thread timer. Budget: maxItems requests or budgetMs, whichever first.</summary>
            public int Drain(int maxItems, double budgetMs)
            {
                var sw = Stopwatch.StartNew();
                int n = 0;
                Work w;
                while (n < maxItems && queue.TryDequeue(out w))
                {
                    n++;
                    if (w.Direct != null)
                    {
                        try { w.Direct(); } catch (Exception e) { Log("main task failed: " + e.Message); }
                    }
                    else if (w.State == 0)
                    {
                        if (NowMs > w.DeadlineMs) Complete(w, null, Err("timeout", "game thread did not answer in time"));
                        else
                        {
                            try { Complete(w, w.Run(), null); }
                            catch (ProtoException e) { Complete(w, null, e); }
                            catch (Exception e)
                            {
                                Log("op " + w.Op + " failed: " + e);
                                Complete(w, null, Err("internal", e.GetType().Name + ": " + e.Message));
                            }
                        }
                    }
                    if (sw.Elapsed.TotalMilliseconds >= budgetMs) break;
                }
                return n;
            }

            private void Complete(Work w, object data, ProtoException err)
            {
                if (Interlocked.CompareExchange(ref w.State, 1, 0) != 0) return;
                byte b;
                pending.TryRemove(w, out b);
                Interlocked.Decrement(ref w.C.Inflight);
                if (w.Entry != null) { FinishRead(w.Entry, data, err, w); return; }
                if (err != null) w.C.SendError(w.Id, err.Code, err.Message);
                else w.C.SendOk(w.Id, data);
            }

            private void Respond(Work w, object data, ProtoException err, bool shared)
            {
                if (err != null) w.C.SendError(w.Id, err.Code, err.Message);
                else if (shared) w.C.Send(new SharedMap().Put("id", w.Id).Put("ok", true).Put("data", data ?? new Map()));
                else w.C.SendOk(w.Id, data);
            }

            private void FinishRead(ReadEntry e, object data, ProtoException err, Work leader)
            {
                List<Work> waiters;
                lock (readLock)
                {
                    e.Done = true; e.At = NowMs; e.Result = data; e.Error = err;
                    waiters = e.Waiters; e.Waiters = new List<Work>();
                    if (err != null) { ReadEntry cur; if (reads.TryGetValue(e.Key, out cur) && cur == e) reads.Remove(e.Key); }   // errors are never cached
                }
                bool shared = waiters.Count > 0 || err == null;   // later hits may share the tree
                Respond(leader, data, err, shared && Cfg.ReadCoalesceMs > 0);
                foreach (var f in waiters)
                {
                    if (Interlocked.CompareExchange(ref f.State, 1, 0) != 0) continue;
                    byte b; pending.TryRemove(f, out b);
                    Interlocked.Decrement(ref f.C.Inflight);
                    Respond(f, data, err, true);
                }
            }

            /// <summary>Drops cached reads for one player (and the roster-wide ones); null drops everything. Call on disconnect and around actions.</summary>
            public void InvalidateReads(string playerId)
            {
                lock (readLock)
                {
                    if (playerId == null) { reads.Clear(); return; }
                    List<string> drop = null;
                    foreach (var kv in reads)
                        if (kv.Value.Player == null || kv.Value.Player == playerId) { if (drop == null) drop = new List<string>(); drop.Add(kv.Key); }
                    if (drop != null) foreach (var k in drop) reads.Remove(k);
                }
            }

            private static string ReadKey(string op, Map args)
            {
                return op + "|" + Convert.ToBase64String(Protocol.Pack.Encode(args, true));
            }

            private void EnqueueRead(Conn c, long id, string op, int timeoutMs, Map args, string player, Func<object> run)
            {
                if (Cfg.ReadCoalesceMs <= 0) { Enqueue(c, id, op, timeoutMs, run); return; }
                string key = ReadKey(op, args);
                long now = NowMs;
                if (Interlocked.Increment(ref c.Inflight) > MaxInflight)
                {
                    Interlocked.Decrement(ref c.Inflight);
                    throw Err("busy", "too many requests in flight");
                }
                Work leader = null;
                Work follower = null;
                ReadEntry hit = null;
                lock (readLock)
                {
                    ReadEntry e;
                    if (reads.TryGetValue(key, out e) && (!e.Done || now - e.At < Cfg.ReadCoalesceMs))
                    {
                        ReadHits++;
                        if (e.Done) hit = e;
                        else { follower = new Work { C = c, Id = id, Op = op, DeadlineMs = now + timeoutMs }; e.Waiters.Add(follower); }
                    }
                    else
                    {
                        ReadMisses++;
                        e = new ReadEntry { Key = key, Player = player };
                        reads[key] = e;
                        if (reads.Count > 256) SweepReads(now);
                        leader = new Work { C = c, Id = id, Op = op, Run = run, DeadlineMs = now + timeoutMs, Entry = e };
                    }
                }
                if (hit != null) { Interlocked.Decrement(ref c.Inflight); Respond(new Work { C = c, Id = id }, hit.Result, null, true); return; }
                if (follower != null) { pending.TryAdd(follower, 0); signal.Set(); return; }
                pending.TryAdd(leader, 0);
                queue.Enqueue(leader);
                signal.Set();
                NotifyMain();
            }

            private void SweepReads(long now)
            {
                List<string> drop = null;
                foreach (var kv in reads)
                    if (kv.Value.Done && now - kv.Value.At >= Cfg.ReadCoalesceMs) { if (drop == null) drop = new List<string>(); drop.Add(kv.Key); }
                if (drop != null) foreach (var k in drop) reads.Remove(k);
            }

            private void Enqueue(Conn c, long id, string op, int timeoutMs, Func<object> run)
            {
                if (Interlocked.Increment(ref c.Inflight) > MaxInflight || queue.Count > 4096)
                {
                    Interlocked.Decrement(ref c.Inflight);
                    throw Err("busy", "too many requests in flight");
                }
                var w = new Work { C = c, Id = id, Op = op, Run = run, DeadlineMs = NowMs + timeoutMs };
                pending.TryAdd(w, 0);
                queue.Enqueue(w);
                signal.Set();
                NotifyMain();
            }

            // ---------- request handling (worker thread) ----------

            public static readonly string[] AllOps =
            {
                "server.info", "players.list", "player.get", "player.inventory", "item.get",
                "positions.subscribe", "positions.keyframe", "positions.unsubscribe",
                "events.subscribe", "events.unsubscribe", "action.run", "assets.manifest", "assets.get"
            };

            public void Handle(Conn c, Map req)
            {
                long id = 0;
                try
                {
                    object ido;
                    if (!req.TryGetValue("id", out ido)) throw Err("invalid_args", "missing 'id'");
                    id = Protocol.Check.Integer(ido, "id", 0, uint.MaxValue);
                    var op = req.ContainsKey("op") ? req["op"] as string : null;
                    if (string.IsNullOrEmpty(op)) throw Err("invalid_args", "missing 'op'");
                    Map args = new Map();
                    object ao;
                    if (req.TryGetValue("args", out ao) && ao != null)
                    {
                        args = ao as Map;
                        if (args == null) throw Err("invalid_args", "'args' must be a map");
                    }
                    Execute(c, id, op, args);
                }
                catch (ProtoException e)
                {
                    c.SendError(id, e.Code, e.Message);
                    if (e.Code == "unauthorized") c.CloseAfterFlush();
                }
                catch (Exception e)
                {
                    Log("request failed: " + e);
                    c.SendError(id, "internal", e.GetType().Name + ": " + e.Message);
                }
            }

            private static bool ConstTimeEq(string a, string b)
            {
                if (a == null || b == null || a.Length != b.Length) return false;
                int d = 0;
                for (int i = 0; i < a.Length; i++) d |= a[i] ^ b[i];
                return d == 0;
            }

            private void Execute(Conn c, long id, string op, Map args)
            {
                if (op == "hello") { DoHello(c, id, args); return; }
                if (!c.Authed)
                {
                    if (TcpMode) throw Err("unauthorized", "hello with a valid token is required first");
                    throw Err("invalid_args", "hello required");
                }
                if (Array.IndexOf(AllOps, op) < 0 || !Cfg.OpEnabled(op)) throw Err("unsupported", "operation not available: " + op);
                switch (op)
                {
                    case "server.info": EnqueueRead(c, id, op, 2000, args, null, () => Game.ServerInfo()); return;
                    case "players.list":
                        {
                            bool sens = Protocol.Check.Bool(args, "include_sensitive", false);
                            bool off = Protocol.Check.Bool(args, "include_offline", false);
                            EnqueueRead(c, id, op, 2000, args, null, () => Game.PlayersList(off, sens)); return;
                        }
                    case "player.get": DoPlayerGet(c, id, args); return;
                    case "player.inventory":
                        {
                            string pid = PlayerId(args, "id");
                            EnqueueRead(c, id, op, 3000, args, pid, () => Game.PlayerInventory(pid)); return;
                        }
                    case "item.get": DoItemGet(c, id, args); return;
                    case "action.run": DoAction(c, id, args); return;
                    case "positions.subscribe": DoPosSubscribe(c, id, args); return;
                    case "positions.keyframe":
                        {
                            int sid = (int)Protocol.Check.Integer(Req(args, "sub"), "sub", 1, int.MaxValue);
                            var s = c.Pos;
                            if (s == null || s.Id != sid) throw Err("not_found", "unknown subscription");
                            s.ForceKey = true; signal.Set();
                            c.SendOk(id, new Map()); return;
                        }
                    case "positions.unsubscribe":
                    case "events.unsubscribe": DoUnsubscribe(c, id, op, args); return;
                    case "events.subscribe": DoEvSubscribe(c, id, args); return;
                    case "assets.manifest": DoManifest(c, id, args); return;
                    case "assets.get": DoAssetsGet(c, id, args); return;
                }
                throw Err("unsupported", "operation not available: " + op);
            }

            private static object Req(Map args, string key)
            {
                object o;
                if (!args.TryGetValue(key, out o) || o == null) throw Err("invalid_args", "missing '" + key + "'");
                return o;
            }

            private static string PlayerId(Map args, string key)
            {
                var s = Req(args, key) as string;
                if (!Protocol.Check.IsSteamId(s)) throw Err("invalid_args", "'" + key + "' must be a 17-digit SteamID64 string");
                return s;
            }

            private void DoHello(Conn c, long id, Map args)
            {
                if (TcpMode)
                {
                    object t;
                    args.TryGetValue("token", out t);
                    if (!ConstTimeEq(t as string, Token)) throw Err("unauthorized", "missing or wrong token");
                }
                if (!(args.ContainsKey("panel_version") && args["panel_version"] is string)) throw Err("invalid_args", "'panel_version' is required");
                c.Authed = true;
                c.SendOk(id, HelloData());
            }

            // The hello body only depends on the Describe() result and the config, so it is built once per Describe() (not per hello).
            private sealed class HelloCache { public HelloInfo For; public Map Data; public HashSet<string> Valid; }
            private volatile HelloCache helloCache;
            private Map HelloData() { return HelloCacheFor().Data; }
            private HelloCache HelloCacheFor()
            {
                var h = hello;
                var cached = helloCache;
                if (cached != null && ReferenceEquals(cached.For, h)) return cached;
                var ops = new List<string>();
                foreach (var o in AllOps) if (Cfg.OpEnabled(o)) ops.Add(o);
                var actions = new List<Map>();
                foreach (var a in h.Actions) if (!Cfg.DisabledActions.Contains((string)a["id"])) actions.Add(a);
                var caps = new Map()
                    .Put("ops", ops)
                    .Put("player_fields", h.PlayerFields)
                    .Put("events", h.Events)
                    .Put("actions", actions)
                    .Put("features", h.Features);
                var d = new Map()
                    .Put("protocol", (long)ProtocolVersion)
                    .Put("game", "rust")
                    .Put("platform", h.Platform)
                    .Put("platform_version", h.PlatformVersion)
                    .Put("game_version", h.GameVersion)
                    .Put("bridge_version", BridgeVersion)
                    .Put("capabilities", caps)
                    .Put("limits", new Map().Put("max_frame", (long)MaxFrame).Put("positions_min_interval_ms", (long)Cfg.PositionsMinIntervalMs).Put("max_inflight", (long)MaxInflight));
                if (h.Integrations.Count > 0) d["integrations"] = h.Integrations;
                var v = new HashSet<string>(h.PlayerFields) { "id", "name", "online" };
                var hc = new HelloCache { For = h, Data = d, Valid = v };
                helloCache = hc;
                return hc;
            }

            private void DoPlayerGet(Conn c, long id, Map args)
            {
                string pid = PlayerId(args, "id");
                bool sens = Protocol.Check.Bool(args, "include_sensitive", false);
                HashSet<string> fields = null;
                object fo;
                if (args.TryGetValue("fields", out fo) && fo != null)
                {
                    var l = fo as IList;
                    if (l == null) throw Err("invalid_args", "'fields' must be an array of strings");
                    fields = new HashSet<string>();
                    var valid = HelloCacheFor().Valid;
                    foreach (var e in l)
                    {
                        var s = e as string;
                        bool dotted = s != null && s.StartsWith("game.", StringComparison.Ordinal) && s.Length > 5 && s.Length < 40 && s.Skip(5).All(ch => (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_');
                        if (s == null || !(valid.Contains(s) || dotted)) throw Err("invalid_args", "unknown field: " + Convert.ToString(e));
                        fields.Add(s);
                    }
                }
                EnqueueRead(c, id, "player.get", 3000, args, pid, () => Game.PlayerGet(pid, fields, sens));
            }

            private void DoItemGet(Conn c, long id, Map args)
            {
                string pid = PlayerId(args, "id");
                var container = Req(args, "container") as string;
                if (string.IsNullOrEmpty(container) || container.Length > 32) throw Err("invalid_args", "'container' must be a container id");
                int slot = Protocol.Check.Slot(Req(args, "slot"), "slot");
                int[] path = null;
                object po;
                if (args.TryGetValue("path", out po) && po != null)
                {
                    var l = po as IList;
                    if (l == null || l.Count > 4) throw Err("invalid_args", "'path' must be an array of at most 4 indices");
                    path = new int[l.Count];
                    for (int i = 0; i < l.Count; i++) path[i] = (int)Protocol.Check.Integer(l[i], "path", 0, 100000);
                }
                Enqueue(c, id, "item.get", 3000, () => Game.ItemGet(pid, container, slot, path));
            }

            // ----- action.run -----

            private void DoAction(Conn c, long id, Map args)
            {
                var action = Req(args, "action") as string;
                if (string.IsNullOrEmpty(action) || action.Length > 40) throw Err("invalid_args", "'action' must be a string");
                var spec = hello.Actions.FirstOrDefault(a => (string)a["id"] == action);
                if (spec == null || Cfg.DisabledActions.Contains(action)) throw Err("unsupported", "action not available: " + action);
                string target = null;
                if ((bool)spec["target"])
                {
                    target = Req(args, "target") as string;
                    if (!Protocol.Check.IsSteamId(target)) throw Err("invalid_args", "'target' must be a 17-digit SteamID64 string");
                }
                Map aargs = new Map();
                object ao;
                if (args.TryGetValue("args", out ao) && ao != null)
                {
                    aargs = ao as Map;
                    if (aargs == null) throw Err("invalid_args", "'args' must be a map");
                }
                aargs = ValidateActionArgs(spec, aargs);
                Map expect = null;
                object eo;
                if (args.TryGetValue("expect", out eo) && eo != null)
                {
                    expect = eo as Map;
                    if (expect == null) throw Err("invalid_args", "'expect' must be a map");
                    if (!expect.ContainsKey("container") || !expect.ContainsKey("slot")) throw Err("invalid_args", "'expect' needs container and slot");
                    if (!expect.ContainsKey("id") && !expect.ContainsKey("count") && !expect.ContainsKey("fingerprint")) throw Err("invalid_args", "'expect' needs id, count or fingerprint");
                }
                int timeout = (action == "save") ? 10000 : 5000;
                // Any action may change what cached reads show: drop them before and after it runs.
                InvalidateReads(target);
                Enqueue(c, id, "action.run", timeout, () => { try { return Game.RunAction(action, target, aargs, expect); } finally { InvalidateReads(target); } });
            }

            /// <summary>Validate action args against the advertised ActionSpec (kind/choices/min/max). Unknown keys are ignored (dropped).</summary>
            public static Map ValidateActionArgs(Map spec, Map args)
            {
                var clean = new Map();
                var fields = (List<Map>)spec["fields"];
                foreach (var f in fields)
                {
                    var key = (string)f["key"];
                    object v;
                    if (!args.TryGetValue(key, out v) || v == null) continue;
                    var kind = (string)f["kind"];
                    double? min = f["min"] == null ? (double?)null : Convert.ToDouble(f["min"]);
                    double? max = f["max"] == null ? (double?)null : Convert.ToDouble(f["max"]);
                    switch (kind)
                    {
                        case "text": clean[key] = Protocol.Check.Text(v, key, 256); break;
                        case "integer": clean[key] = Protocol.Check.Integer(v, key, min.HasValue ? (long?)min.Value : null, max.HasValue ? (long?)max.Value : null); break;
                        case "number": clean[key] = Protocol.Check.Number(v, key, min, max); break;
                        case "target": Protocol.Check.SteamId(v, key); clean[key] = v; break;
                        case "identifier":
                        case "item":
                            if (!Protocol.Check.IsShortname(v as string)) throw Err("invalid_args", "'" + key + "' is not a valid identifier");
                            clean[key] = v; break;
                        case "select":
                            {
                                var s = v as string;
                                if (s == null || !((List<string>)f["choices"]).Contains(s)) throw Err("invalid_args", "'" + key + "' is not one of the choices");
                                clean[key] = s; break;
                            }
                        default: throw Err("internal", "unknown field kind " + kind);
                    }
                }
                return clean;
            }

            // ----- assets (Rust serves none) -----

            private void DoManifest(Conn c, long id, Map args)
            {
                object since;
                if (args.TryGetValue("since_hash", out since) && since as string == ManifestHash)
                { c.SendOk(id, new Map().Put("unchanged", true).Put("manifest_hash", ManifestHash)); return; }
                c.SendOk(id, new Map().Put("entries", new List<object>()).Put("manifest_hash", ManifestHash));
            }

            private void DoAssetsGet(Conn c, long id, Map args)
            {
                var keys = Req(args, "keys") as IList;
                if (keys == null) throw Err("invalid_args", "'keys' must be an array");
                if (keys.Count > 64) throw Err("invalid_args", "at most 64 keys per request");
                var list = new List<object>();
                foreach (var k in keys)
                {
                    var ks = k as string;
                    if (ks == null) throw Err("invalid_args", "keys must be strings");
                    // Rust ids are shortnames without ':'; those are known items without art here. Anything else does not exist.
                    bool item = ks.StartsWith("item:", StringComparison.Ordinal) && ks.Length > 5 && ks.IndexOf(':', 5) < 0;
                    list.Add(new Map().Put("key", ks).Put("hash", "0000000000000000").Put("render", "unavailable").Put("unavailable_reason", item ? "no_art_on_server" : "not_found"));
                }
                c.SendOk(id, new Map().Put("assets", list).Put("more", false));
            }

            // ----- subscriptions -----

            private void DoPosSubscribe(Conn c, long id, Map args)
            {
                long iv = Protocol.Check.Integer(Req(args, "interval_ms"), "interval_ms", 1, null);
                iv = Math.Max(Cfg.PositionsMinIntervalMs, Math.Min(60000, iv));
                HashSet<string> worlds = null;
                object wo;
                if (args.TryGetValue("worlds", out wo) && wo != null)
                {
                    var l = wo as IList;
                    if (l == null) throw Err("invalid_args", "'worlds' must be an array of strings");
                    worlds = new HashSet<string>();
                    foreach (var w in l) { var s = w as string; if (s == null) throw Err("invalid_args", "'worlds' must be an array of strings"); worlds.Add(s); }
                }
                var sub = new PosSub { C = c, Id = Interlocked.Increment(ref nextSub), IntervalMs = (int)iv, Worlds = worlds };
                lock (subLock)
                {
                    if (c.Pos != null) throw Err("busy", "this connection already has a positions subscription");
                    c.Pos = sub;
                }
                c.SendOk(id, new Map().Put("sub", (long)sub.Id));
                sub.NextDueMs = 0; sub.LastFrameMs = NowMs;
                lock (subLock) posSubs = posSubs.Concat(new[] { sub }).ToArray();
                signal.Set();
            }

            private void DoEvSubscribe(Conn c, long id, Map args)
            {
                var l = Req(args, "types") as IList;
                if (l == null || l.Count == 0) throw Err("invalid_args", "'types' must be a non-empty array");
                var types = new HashSet<string>();
                var supported = hello.Events;
                foreach (var t in l)
                {
                    var s = t as string;
                    if (s == null) throw Err("invalid_args", "'types' must be strings");
                    if (!supported.Contains(s)) throw Err("unsupported", "event type not supported: " + s);
                    types.Add(s);
                }
                var sub = new EvSub { C = c, Id = Interlocked.Increment(ref nextSub), Types = types, LastFrameMs = NowMs };
                lock (subLock)
                {
                    if (c.Ev != null) throw Err("busy", "this connection already has an events subscription");
                    c.Ev = sub;
                }
                c.SendOk(id, new Map().Put("sub", (long)sub.Id));
                lock (subLock) evSubs = evSubs.Concat(new[] { sub }).ToArray();
                RecalcMask();
            }

            private void DoUnsubscribe(Conn c, long id, string op, Map args)
            {
                int sid = (int)Protocol.Check.Integer(Req(args, "sub"), "sub", 1, int.MaxValue);
                bool changed = false;
                lock (subLock)
                {
                    if (op == "positions.unsubscribe")
                    {
                        if (c.Pos == null || c.Pos.Id != sid) throw Err("not_found", "unknown subscription");
                        var p = c.Pos; c.Pos = null;
                        posSubs = posSubs.Where(s => s != p).ToArray();
                    }
                    else
                    {
                        if (c.Ev == null || c.Ev.Id != sid) throw Err("not_found", "unknown subscription");
                        var e = c.Ev; c.Ev = null;
                        evSubs = evSubs.Where(s => s != e).ToArray();
                        changed = true;
                    }
                }
                if (changed) RecalcMask();
                c.SendOk(id, new Map());
            }

            private void RecalcMask()
            {
                int m = 0;
                foreach (var s in evSubs) foreach (var t in s.Types) m |= EventBit(t);
                int old = Interlocked.Exchange(ref eventMask, m);
                if (old != m && EventDemandChanged != null)
                {
                    try { EventDemandChanged(m); } catch (Exception e) { Log("demand callback failed: " + e.Message); }
                }
            }

            /// <summary>Called from game hooks (main thread). Only enqueues; never blocks.</summary>
            public void Emit(string type, Map data)
            {
                var subs = evSubs;
                bool any = false;
                for (int i = 0; i < subs.Length; i++)
                    if (subs[i].Types.Contains(type)) { subs[i].Enqueue(data); any = true; }
                if (any) signal.Set();
            }

            // ---------- hub thread: sampling, deltas, event delivery, pings, deadlines, idle ----------

            private void HubLoop()
            {
                while (running)
                {
                    try
                    {
                        signal.WaitOne(NextWaitMs());
                        if (!running) break;
                        long now = NowMs;
                        SchedulePositionSample(now);
                        ProcessSample(now);
                        FlushEvents(now);
                        Housekeeping(now);
                    }
                    catch (Exception e) { Log("hub error: " + e); Thread.Sleep(100); }
                }
            }

            /// <summary>How long the hub may sleep: forever with no connection, nothing pending and no subscription (zero idle CPU);
            /// otherwise until the next position sample is due / a pending deadline needs checking / the 1 s housekeeping tick.</summary>
            private int NextWaitMs()
            {
                bool anyConn;
                lock (conns) anyConn = conns.Count > 0;
                var ps = posSubs;
                if (!anyConn && ps.Length == 0 && evSubs.Length == 0 && pending.IsEmpty) return Timeout.Infinite;
                int wait = 1000;
                if (!pending.IsEmpty) wait = 25;
                if (ps.Length > 0 && !sampleInFlight)
                {
                    long now = NowMs;
                    for (int i = 0; i < ps.Length; i++)
                    {
                        long d = ps[i].ForceKey ? 0 : ps[i].NextDueMs - now;
                        if (d < wait) wait = (int)Math.Max(0, d);
                    }
                }
                return wait;
            }

            private void SchedulePositionSample(long now)
            {
                if (sampleInFlight) return;
                var ps = posSubs;
                bool due = false;
                for (int i = 0; i < ps.Length; i++) if (ps[i].ForceKey || ps[i].NextDueMs <= now) { due = true; break; }
                if (!due) return;
                sampleInFlight = true;
                RunOnMain(() =>
                {
                    PosSample[] s = null;
                    try { s = Game.SamplePositions(); }
                    catch (Exception e) { Log("position sampling failed: " + e.Message); }
                    lock (sampleLock) newSample = s;
                    sampleInFlight = false;
                    signal.Set();
                });
            }

            private void ProcessSample(long now)
            {
                PosSample[] sample;
                lock (sampleLock) { sample = newSample; newSample = null; }
                if (sample == null) return;
                foreach (var s in posSubs)
                {
                    if (!(s.ForceKey || s.NextDueMs <= now) || s.C.IsClosed) continue;
                    s.NextDueMs = now + s.IntervalMs;
                    BuildPositionFrame(s, sample, now);
                }
            }

            public void BuildPositionFrame(PosSub s, PosSample[] sample, long now)
            {
                bool key = s.ForceKey || now - s.LastKeyMs >= 10000 || s.Seq == 0;
                var current = new Dictionary<string, PosEntry>(sample.Length);
                var changed = new List<object>();
                for (int i = 0; i < sample.Length; i++)
                {
                    var p = sample[i];
                    if (s.Worlds != null && !s.Worlds.Contains(p.World)) continue;
                    var e = new PosEntry { Name = p.Name, World = p.World, X = Protocol.Quant.Q16(p.X), Y = Protocol.Quant.Q16(p.Y), Z = Protocol.Quant.Q16(p.Z), State = p.State };
                    current[p.Id] = e;
                    PosEntry old;
                    if (key || !s.Last.TryGetValue(p.Id, out old) || !old.Same(e)) changed.Add(PosMap(p.Id, e));
                }
                List<object> gone = null;
                if (!key)
                    foreach (var k in s.Last.Keys)
                        if (!current.ContainsKey(k)) { if (gone == null) gone = new List<object>(); gone.Add(k); }
                s.Last = current;
                if (key) { s.LastKeyMs = now; s.ForceKey = false; }
                else if (changed.Count == 0 && gone == null) return;
                var d = new Map().Put("key", key).Put("players", changed);
                if (!key) d["gone"] = gone ?? new List<object>();
                s.C.Send(new Map().Put("sub", (long)s.Id).Put("seq", s.Seq++).Put("data", d));
                s.LastFrameMs = now;
            }

            private static Map PosMap(string id, PosEntry e)
            {
                return new Map().Put("id", id).Put("name", e.Name).Put("world", e.World)
                    .Put("x", (long)e.X).Put("y", (long)e.Y).Put("z", (long)e.Z)
                    .Put("state", e.State == 1 ? "dead" : (e.State == 2 ? "spectator" : "alive"));
            }

            private void FlushEvents(long now)
            {
                foreach (var s in evSubs)
                {
                    int drop;
                    var items = s.Take(out drop);
                    if (drop > 0) SendEv(s, new Map().Put("type", "overflow").Put("dropped", (long)drop), now);
                    if (items != null) foreach (var m in items) SendEv(s, m, now);
                }
            }

            private static void SendEv(EvSub s, Map data, long now)
            {
                s.C.Send(new Map().Put("sub", (long)s.Id).Put("seq", s.Seq++).Put("data", data));
                s.LastFrameMs = now;
            }

            private void Housekeeping(long now)
            {
                foreach (var w in pending.Keys)
                    if (w.State == 0 && now > w.DeadlineMs) Complete(w, null, Err("timeout", "game thread did not answer in time"));
                foreach (var s in posSubs)
                    if (now - s.LastFrameMs >= 60000) { s.C.Send(new Map().Put("sub", (long)s.Id).Put("seq", s.Seq++).Put("data", new Map().Put("type", "ping"))); s.LastFrameMs = now; }
                foreach (var s in evSubs)
                    if (now - s.LastFrameMs >= 60000) { SendEv(s, new Map().Put("type", "ping"), now); }
                List<Conn> cs;
                lock (conns) cs = new List<Conn>(conns);
                foreach (var c in cs)
                    if (now - c.LastActivityMs > 120000) { Log("closing idle connection " + c.Remote); c.Close(); }
            }
        }

        #endregion

        #region Transport

        /// <summary>Owns the .playermanager directory, bridge.json, token, config.json and the listening socket.</summary>
        public sealed class Transport
        {
            private readonly BridgeCore core;
            private readonly string dir;
            private readonly Action<string> log;
            private Socket listener;
            private Thread acceptThread;
            private volatile bool stopping;
            private string sockPath;
            private int connCounter;
            public string Mode = "none";             // unix | tcp | none
            public int Port;

            public Transport(BridgeCore core, string dir, Action<string> log)
            {
                this.core = core; this.dir = dir; this.log = log;
            }

            public static BridgeConfig LoadConfig(string dir, Action<string> log)
            {
                try
                {
                    Directory.CreateDirectory(dir);
                    var p = Path.Combine(dir, "config.json");
                    if (!File.Exists(p)) { File.WriteAllText(p, BridgeConfig.DefaultJson); return new BridgeConfig(); }
                    return BridgeConfig.Parse(File.ReadAllText(p));
                }
                catch (Exception e)
                {
                    log("config.json unreadable, using defaults: " + e.Message);
                    return new BridgeConfig();
                }
            }

            /// <summary>Binds the listener (Unix first, TCP fallback). Returns false when nothing could be bound. Never throws.</summary>
            public bool Start()
            {
                try
                {
                    Directory.CreateDirectory(dir);
                    Directory.CreateDirectory(Path.Combine(dir, "cache"));
                }
                catch (Exception e) { log("cannot create " + dir + ": " + e.Message); return false; }

                string mode = core.Cfg.Transport;
                if (mode != "tcp" && TryUnix()) return Finish();
                if (mode == "unix") { log("Unix socket required by config but unavailable; bridge disabled"); return false; }
                core.TcpMode = true;
                return StartTcp();
            }

            private bool StartTcp()
            {
                try
                {
                    Directory.CreateDirectory(dir);
                    int port = core.Cfg.TcpFallbackPort;
                    core.Token = ReadOrCreateToken(Path.Combine(dir, "token"), log);
                    var s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
                    s.Bind(new IPEndPoint(IPAddress.Any, port));
                    s.Listen(32);
                    listener = s; Mode = "tcp"; Port = port;
                    log("listening on TCP port " + port + " (token required)");
                    return Finish();
                }
                catch (Exception e) { log("TCP fallback failed: " + e.Message); return false; }
            }

            private bool TryUnix()
            {
                try
                {
                    string rel = Path.Combine(".playermanager", "bridge.sock");
                    string abs = Path.GetFullPath(Path.Combine(dir, "bridge.sock"));
                    string path = Encoding.UTF8.GetByteCount(abs) < 108 ? abs : rel;
                    if (Encoding.UTF8.GetByteCount(path) >= 108) { log("socket path is 108 bytes or longer: " + path); return false; }
                    try { if (File.Exists(path)) File.Delete(path); } catch { }
                    var s = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
                    s.Bind(new UnixDomainSocketEndPoint(path));
                    s.Listen(32);
                    listener = s; sockPath = path; Mode = "unix";
                    log("listening on Unix socket " + path);
                    return true;
                }
                catch (Exception e)
                {
                    log("Unix socket unavailable (" + e.GetType().Name + ": " + e.Message + ")");
                    return false;
                }
            }

            private bool Finish()
            {
                acceptThread = new Thread(AcceptLoop) { IsBackground = true, Name = "pm-accept" };
                acceptThread.Start();
                return true;
            }

            private void AcceptLoop()
            {
                while (!stopping)
                {
                    Socket c;
                    try { c = listener.Accept(); }
                    catch (Exception e)
                    {
                        if (stopping) return;
                        log("accept failed: " + e.Message);
                        Thread.Sleep(200);
                        continue;
                    }
                    try
                    {
                        c.SendTimeout = 15000;
                        if (Mode == "tcp") { try { c.NoDelay = true; } catch { } }
                        var conn = new Conn(core, c, Mode + "#" + Interlocked.Increment(ref connCounter));
                        bool ok = core.AddConnection(conn);
                        conn.Start();
                        if (!ok) { conn.SendError(0, "busy", "too many connections"); conn.CloseAfterFlush(); }
                    }
                    catch (Exception e) { log("connection setup failed: " + e.Message); try { c.Close(); } catch { } }
                }
            }

            public void Stop()
            {
                stopping = true;
                try { if (listener != null) listener.Close(); } catch { }
                try { if (sockPath != null && File.Exists(sockPath)) File.Delete(sockPath); } catch { }
                try { if (acceptThread != null) acceptThread.Join(500); } catch { }
            }

            // ---------- files ----------

            public void WriteBridgeJson(string bridgeVersion)
            {
                var h = core.Hello;
                var d = new Dictionary<string, object>();
                d["protocol"] = ProtocolVersion;
                d["transport"] = Mode;
                d["socket"] = Mode == "unix" ? ".playermanager/bridge.sock" : null;
                d["port"] = Mode == "tcp" ? (object)Port : null;
                d["platform"] = h.Platform;
                d["platform_version"] = h.PlatformVersion;
                d["game"] = "rust";
                d["game_version"] = h.GameVersion;
                d["bridge_version"] = bridgeVersion;
                d["pid"] = Process.GetCurrentProcess().Id;
                d["started_at"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
                AtomicWrite(Path.Combine(dir, "bridge.json"), JsonConvert.SerializeObject(d, Formatting.Indented));
            }

            public static void AtomicWrite(string path, string text)
            {
                string tmp = path + ".tmp";
                File.WriteAllText(tmp, text, new UTF8Encoding(false));
                if (File.Exists(path))
                {
                    try { File.Replace(tmp, path, null); return; }
                    catch { try { File.Delete(path); } catch { } }
                }
                File.Move(tmp, path);
            }

            public static string ReadOrCreateToken(string path, Action<string> log)
            {
                try
                {
                    if (File.Exists(path))
                    {
                        var t = File.ReadAllText(path).Trim();
                        if (t.Length == 64 && t.All(ch => (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return t;
                    }
                }
                catch { }
                var bytes = new byte[32];
                using (var rng = RandomNumberGenerator.Create()) rng.GetBytes(bytes);
                var token = Protocol.Canon.Hex(bytes, 64);
                File.WriteAllText(path, "");
                RestrictFile(path, log);                 // chmod 0600 while still empty
                File.WriteAllText(path, token);
                return token;
            }

            /// <summary>chmod 0600: File.SetUnixFileMode (newer runtimes) via reflection, else the chmod binary, else a warning.</summary>
            public static void RestrictFile(string path, Action<string> log)
            {
                try
                {
                    var ut = typeof(File).Assembly.GetType("System.IO.UnixFileMode");
                    var mi = ut == null ? null : typeof(File).GetMethod("SetUnixFileMode", new[] { typeof(string), ut });
                    if (mi != null) { mi.Invoke(null, new object[] { path, Enum.ToObject(ut, 384) }); return; }
                }
                catch (Exception) { }
                try
                {
                    var psi = new ProcessStartInfo("chmod", "600 \"" + path + "\"") { UseShellExecute = false, CreateNoWindow = true };
                    using (var p = Process.Start(psi))
                    {
                        if (p != null && p.WaitForExit(3000) && p.ExitCode == 0) return;
                    }
                }
                catch (Exception) { }
                log("could not set mode 0600 on " + path + "; restrict it manually");
            }
        }

        #endregion

        #region Provider

        private static readonly System.Text.RegularExpressions.Regex RichText = new System.Text.RegularExpressions.Regex("<[^>]*>", System.Text.RegularExpressions.RegexOptions.Compiled);
        private static string Plain(string s) { return s == null ? "" : RichText.Replace(s, ""); }
        private static double R3(float v) { return Math.Round((double)v, 3); }
        private static long UnixNow() { return DateTimeOffset.UtcNow.ToUnixTimeSeconds(); }
        private static ulong ToU64(object o)
        {
            ulong r;
            return o != null && ulong.TryParse(o.ToString(), out r) ? r : 0UL;
        }
        // Real Steam accounts only (NPCs and bots have small ids).
        private static bool IsRealId(ulong id) { return id >= 76561197960265729UL && id <= 76561202255233023UL; }
        private static bool IsReal(BasePlayer p) { if (p == null) return false; ulong id = p.userID; return IsRealId(id); }

        private static string StripPort(string addr)
        {
            if (string.IsNullOrEmpty(addr)) return addr;
            int i = addr.LastIndexOf(':');
            return (i > 0 && addr.IndexOf(':') == i) ? addr.Substring(0, i) : addr;
        }

        public sealed class RustProvider : IGameProvider
        {
            private readonly PlayerManagerRust p;
            private static readonly string[] GameKeys =
            {
                "net_id", "sleeping", "wounded", "crawling", "alive", "connected_seconds", "connected_at", "current_life_seconds",
                "metabolism", "team", "clan", "building_privilege", "blueprints_learned", "mounted_entity",
                "owner", "admin", "moderator", "banned"
            };

            public RustProvider(PlayerManagerRust plugin) { p = plugin; }

            // ---------- identity ----------

            public string WorldId()
            {
                string level = ConVar.Server.level ?? "";
                if (level.Length == 0 || string.Equals(level, "Procedural Map", StringComparison.OrdinalIgnoreCase))
                    return "procedural:" + ConVar.Server.worldsize + ":" + ConVar.Server.seed;
                return level;
            }

            private static BasePlayer FindOnline(ulong id) { return BasePlayer.FindByID(id); }
            private static BasePlayer FindAny(ulong id) { return BasePlayer.FindByID(id) ?? BasePlayer.FindSleeping(id); }

            // ---------- hello ----------

            public HelloInfo Describe()
            {
                var h = new HelloInfo();
#if CARBON
                h.Platform = "carbon";
                try
                {
                    var a = AppDomain.CurrentDomain.GetAssemblies().FirstOrDefault(x => x.GetName().Name == "Carbon");
                    h.PlatformVersion = a != null ? a.GetName().Version.ToString() : "unknown";
                }
                catch (Exception) { h.PlatformVersion = "unknown"; }
#else
                h.Platform = "oxide";
                try { h.PlatformVersion = OxideMod.Version.ToString(); } catch (Exception) { h.PlatformVersion = "unknown"; }
#endif
                try { h.GameVersion = Rust.Protocol.printable; } catch (Exception) { h.GameVersion = "unknown"; }
                h.PlayerFields.AddRange(new[] { "world", "position", "health", "max_health", "held_item", "ping", "game" });
                h.Events.AddRange(EventTypes);
                if (p.Backpacks != null) h.Integrations.Add("backpacks");
                if (p.Clans != null) h.Integrations.Add("clans");
                h.Features.AddRange(new[] { "positions.stream", "offline_players", "sensitive", "assets" });
                h.Actions = BuildActions();
                return h;
            }

            private static Map F(string key, string label, string kind, object min, object max)
            {
                return new Map().Put("key", key).Put("label", label).Put("kind", kind).Put("choices", new List<string>()).Put("min", min).Put("max", max);
            }

            private static Map Spec(string id, string label, bool target, bool destructive, bool offline, params Map[] fields)
            {
                return new Map().Put("id", id).Put("label", label).Put("target", target).Put("destructive", destructive).Put("offline", offline).Put("fields", new List<Map>(fields));
            }

            private List<Map> BuildActions()
            {
                double half = Math.Max(500, ConVar.Server.worldsize / 2.0);
                var reason = F("reason", "Reason", "text", null, null);
                return new List<Map>
                {
                    Spec("kick", "Kick", true, true, false, reason),
                    Spec("ban", "Ban", true, true, true, reason),
                    Spec("unban", "Unban", true, false, true),
                    Spec("kill", "Kill", true, true, true),
                    Spec("heal", "Heal", true, false, true),
                    Spec("hurt", "Hurt", true, false, true, F("amount", "Amount", "number", 0, 100)),
                    Spec("sethealth", "Set health", true, false, true, F("amount", "Amount", "number", 0, 100)),
                    Spec("set_calories", "Set calories", true, false, true, F("amount", "Amount", "number", 0, 500)),
                    Spec("set_hydration", "Set hydration", true, false, true, F("amount", "Amount", "number", 0, 250)),
                    Spec("give_item", "Give item", true, false, true, F("item", "Item", "identifier", null, null), F("amount", "Amount", "integer", 1, 64000)),
                    Spec("clear_inventory", "Clear inventory", true, true, true),
                    Spec("teleport", "Teleport", true, false, false,
                        F("x", "X", "number", -half, half), F("y", "Y", "number", MinY, MaxY), F("z", "Z", "number", -half, half),
                        F("to_player", "To player", "target", null, null), F("world", "World", "text", null, null)),
                    Spec("mute", "Mute", true, false, true),
                    Spec("unmute", "Unmute", true, false, true),
                    Spec("make_owner", "Make owner", true, true, true),
                    Spec("remove_owner", "Remove owner", true, false, true),
                    Spec("make_moderator", "Make moderator", true, true, true),
                    Spec("remove_moderator", "Remove moderator", true, false, true),
                    Spec("save", "Save world", false, false, false)
                };
            }

            public const double MinY = -100, MaxY = 1500;

            // ---------- server.info ----------

            public Map ServerInfo()
            {
                int online = BasePlayer.activePlayerList.Count;
                double fps = 0;
                try { fps = Convert.ToDouble(Performance.report.frameRate); } catch (Exception) { }
                var game = new Map()
                    .Put("hostname", ConVar.Server.hostname ?? "")
                    .Put("description", ConVar.Server.description ?? "")
                    .Put("url", ConVar.Server.url ?? "")
                    .Put("header_image", ConVar.Server.headerimage ?? "")
                    .Put("map_name", ConVar.Server.level ?? "")
                    .Put("world_size", (long)ConVar.Server.worldsize)
                    .Put("seed", (long)ConVar.Server.seed)
                    .Put("entity_count", EntityCount())
                    .Put("fps", Math.Round(fps, 1))
                    .Put("save_interval", (long)ConVar.Server.saveinterval)
                    .Put("backpacks_plugin", p.Backpacks != null);
                try
                {
                    var t = SaveRestore.SaveCreatedTime;
                    if (t.Year > 2000) game["wipe_time"] = new DateTimeOffset(DateTime.SpecifyKind(t, DateTimeKind.Utc)).ToUnixTimeSeconds();
                }
                catch (Exception) { }
                var mons = Monuments();
                if (mons != null) game["monuments"] = mons;
                var roles = new List<object>();
                var bans = new List<object>();
                FillRoles(roles);
                FillBans(bans);
                game["roles"] = roles;
                game["bans"] = bans;

                var d = new Map()
                    .Put("name", ConVar.Server.hostname ?? "")
                    .Put("motd", ConVar.Server.description ?? "")
                    .Put("max_players", (long)ConVar.Server.maxplayers)
                    .Put("online", (long)online)
                    .Put("uptime", Uptime());
                if (fps > 0) d["performance"] = new Map().Put("frame_ms", Math.Round(1000.0 / fps, 2));
                d["worlds"] = new List<object>
                {
                    new Map().Put("world", WorldId()).Put("name", ConVar.Server.level ?? "").Put("players", (long)online).Put("loaded", true)
                        .Put("size", (long)ConVar.Server.worldsize).Put("seed", ConVar.Server.seed.ToString())
                };
                d["extensions"] = Extensions();
                d["game"] = game;
                return d;
            }

            private static long Uptime()
            {
                try { return (long)(DateTime.Now - Process.GetCurrentProcess().StartTime).TotalSeconds; } catch (Exception) { return 0; }
            }

            private static long EntityCount()
            {
                try { return BaseNetworkable.serverEntities.Count; } catch (Exception) { return 0; }
            }

            private List<object> Monuments()
            {
                try
                {
                    var src = TerrainMeta.Path != null ? TerrainMeta.Path.Monuments : null;
                    if (src == null) return null;
                    var l = new List<object>();
                    foreach (var m in src)
                    {
                        if (m == null) continue;
                        var pos = m.transform.position;
                        l.Add(new Map().Put("name", m.name ?? "").Put("position", new Map().Put("x", R3(pos.x)).Put("y", R3(pos.y)).Put("z", R3(pos.z))));
                        if (l.Count >= 200) break;
                    }
                    return l;
                }
                catch (Exception) { return null; }
            }

            private List<object> Extensions()
            {
                var l = new List<object>();
                try
                {
                    var mgr = p.Manager;
                    if (mgr == null) return l;
                    foreach (var pl in mgr.GetPlugins())
                    {
                        if (pl == null || pl.IsCorePlugin) continue;
                        l.Add(new Map().Put("name", pl.Name ?? pl.Title ?? "").Put("version", pl.Version.ToString()).Put("kind", "plugin"));
                        if (l.Count >= 500) break;
                    }
                }
                catch (Exception) { }
                return l;
            }

            // ---------- roles and bans ----------

            private static string KnownName(ulong id)
            {
                foreach (var g in new[] { ServerUsers.UserGroup.Owner, ServerUsers.UserGroup.Moderator, ServerUsers.UserGroup.Banned })
                    foreach (var u in ServerUsers.GetAll(g))
                        if (u != null && u.steamid == id && !string.IsNullOrEmpty(u.username)) return u.username;
                return null;
            }

            private void FillRoles(List<object> roles)
            {
                var seen = new HashSet<string>();
                try
                {
                    foreach (var user in ServerUsers.GetAll(ServerUsers.UserGroup.Owner))
                        if (user != null && seen.Add(user.steamid.ToString()))
                            roles.Add(new Map().Put("id", user.steamid.ToString()).Put("name", string.IsNullOrEmpty(user.username) ? user.steamid.ToString() : user.username).Put("role", "owner"));
                }
                catch (Exception) { }
                GroupRoles(roles, seen, "admin", "admin");
                try
                {
                    foreach (var user in ServerUsers.GetAll(ServerUsers.UserGroup.Moderator))
                        if (user != null && seen.Add(user.steamid.ToString()))
                            roles.Add(new Map().Put("id", user.steamid.ToString()).Put("name", string.IsNullOrEmpty(user.username) ? user.steamid.ToString() : user.username).Put("role", "moderator"));
                }
                catch (Exception) { }
                GroupRoles(roles, seen, "moderator", "moderator");
            }

            private void GroupRoles(List<object> roles, HashSet<string> seen, string group, string role)
            {
                try
                {
                    var users = p.permission.GetUsersInGroup(group);
                    if (users == null) return;
                    foreach (var entry in users)
                    {
                        // entries look like "7656...(Name)" or "7656... (Name)"
                        int i = entry.IndexOf('(');
                        string id = (i > 0 ? entry.Substring(0, i) : entry).Trim();
                        string name = i > 0 ? entry.Substring(i + 1).TrimEnd(')', ' ') : id;
                        if (!Protocol.Check.IsSteamId(id) || !seen.Add(id)) continue;
                        roles.Add(new Map().Put("id", id).Put("name", string.IsNullOrEmpty(name) ? id : name).Put("role", role));
                    }
                }
                catch (Exception) { }
            }

            public bool ServerInfoRoles(List<object> roles, List<object> bans) { FillRoles(roles); FillBans(bans); return true; }

            private static void FillBans(List<object> bans)
            {
                try
                {
                    foreach (var user in ServerUsers.GetAll(ServerUsers.UserGroup.Banned))
                    {
                        if (user == null) continue;
                        bans.Add(new Map().Put("id", user.steamid.ToString()).Put("name", string.IsNullOrEmpty(user.username) ? user.steamid.ToString() : user.username).Put("reason", user.notes ?? ""));
                    }
                }
                catch (Exception) { }
            }

            // ---------- players.list ----------

            private bool IsOwner(ulong id) { return ServerUsers.Is(id, ServerUsers.UserGroup.Owner); }
            private bool IsModerator(ulong id, string sid) { return ServerUsers.Is(id, ServerUsers.UserGroup.Moderator) || SafeInGroup(sid, "moderator"); }
            private bool IsAdminRole(BasePlayer pl, ulong id, string sid) { return IsOwner(id) || SafeInGroup(sid, "admin") || (pl != null && pl.IsAdmin); }
            private bool SafeInGroup(string sid, string group) { try { return p.permission.UserHasGroup(sid, group); } catch (Exception) { return false; } }

            private List<string> Groups(BasePlayer pl, ulong id, string sid, bool sleeping)
            {
                var set = new List<string>();
                Action<string> add = g => { if (!set.Contains(g)) set.Add(g); };
                if (sleeping) add("sleeping");
                if (IsAdminRole(pl, id, sid)) add("operators");
                if (IsModerator(id, sid)) add("moderators");
                if (ServerUsers.Is(id, ServerUsers.UserGroup.Banned)) add("banned");
                try
                {
                    var gs = p.permission.GetUserGroups(sid);
                    if (gs != null) foreach (var g in gs) if (!string.IsNullOrEmpty(g)) add(g.ToLowerInvariant());
                }
                catch (Exception) { }
                return set;
            }

            public Map PlayersList(bool includeOffline, bool includeSensitive)
            {
                var rows = new List<object>();
                foreach (BasePlayer pl in BasePlayer.activePlayerList)
                {
                    if (!IsReal(pl)) continue;
                    if (rows.Count >= 5000) break;
                    rows.Add(Row(pl, true, includeSensitive));
                }
                if (includeOffline)
                foreach (BasePlayer pl in BasePlayer.sleepingPlayerList)
                {
                    if (!IsReal(pl) || pl.IsConnected) continue;
                    if (rows.Count >= 5000) break;
                    rows.Add(Row(pl, false, includeSensitive));
                }
                return new Map().Put("players", rows);
            }

            private Map Row(BasePlayer pl, bool online, bool sens)
            {
                string sid = pl.UserIDString;
                ulong id = pl.userID;
                var m = new Map()
                    .Put("id", sid).Put("name", pl.displayName ?? sid).Put("online", online)
                    .Put("groups", Groups(pl, id, sid, pl.IsSleeping() && !online))
                    .Put("admin", IsAdminRole(pl, id, sid));
                if (online)
                {
                    m["ping"] = Ping(pl);
                    m["world"] = WorldId();
                    long at;
                    if (p.connectedAt.TryGetValue(id, out at)) m["joined_at"] = at;
                    if (sens) { var ip = Address(pl); if (ip != null) m["ip"] = ip; }
                }
                return m;
            }

            private static object Ping(BasePlayer pl)
            {
                try { return (long)Network.Net.sv.GetAveragePing(pl.net.connection); } catch (Exception) { return null; }
            }

            private static string Address(BasePlayer pl)
            {
                try { return StripPort(pl.net.connection.ipaddress); } catch (Exception) { return null; }
            }

            // ---------- player.get ----------

            public Map PlayerGet(string sid, HashSet<string> fields, bool sens)
            {
                ulong id = ulong.Parse(sid);
                var pl = FindAny(id);
                if (pl == null) throw Err("not_found", "Player is not online or sleeping");
                bool online = FindOnline(id) != null;
                Func<string, bool> want = f => fields == null || fields.Contains(f);
                var m = new Map().Put("id", sid).Put("name", pl.displayName ?? sid).Put("online", online);
                if (want("world")) m["world"] = WorldId();
                if (want("position")) m["position"] = Pos(pl);
                if (want("health")) m["health"] = R3(pl.health);
                if (want("max_health")) m["max_health"] = R3(pl.MaxHealth());
                if (want("held_item") && online)
                {
                    var held = pl.GetActiveItem();
                    m["held_item"] = held != null ? BuildItem(held, 0, null) : null;
                }
                if (want("ping") && online) m["ping"] = Ping(pl);
                if (sens && online) { var ip = Address(pl); if (ip != null) m["ip"] = ip; }
                if (fields != null)
                    foreach (var f in fields)
                        if (f.StartsWith("game.", StringComparison.Ordinal) && Array.IndexOf(GameKeys, f.Substring(5)) < 0) throw Err("invalid_args", "unknown field: " + f);
                bool wholeGame = fields == null || fields.Contains("game");
                var g = GameSection(pl, id, sid, online, k => wholeGame || fields.Contains("game." + k));
                if (g.Count > 0 || wholeGame) m["game"] = g;
                return m;
            }

            private static Map Pos(Component pl)
            {
                var v = pl.transform.position;
                return new Map().Put("x", R3(v.x)).Put("y", R3(v.y)).Put("z", R3(v.z));
            }

            private Map GameSection(BasePlayer pl, ulong id, string sid, bool online, Func<string, bool> want)
            {
                var g = new Map();
                if (want("net_id")) { try { g["net_id"] = (long)ToU64(pl.net.ID); } catch (Exception) { } }
                if (want("sleeping")) g["sleeping"] = pl.IsSleeping();
                if (want("wounded")) g["wounded"] = pl.IsWounded();
                if (want("crawling")) { try { g["crawling"] = pl.IsCrawling(); } catch (Exception) { } }
                if (want("alive")) g["alive"] = pl.IsAlive();
                long at;
                bool haveAt = p.connectedAt.TryGetValue(id, out at);
                if (online && haveAt && want("connected_seconds")) g["connected_seconds"] = Math.Max(0, UnixNow() - at);
                if (online && haveAt && want("connected_at")) g["connected_at"] = at;
                if (want("current_life_seconds")) { try { g["current_life_seconds"] = pl.lifeStory != null ? (long)pl.lifeStory.secondsAlive : 0L; } catch (Exception) { } }
                if (want("metabolism")) { var mm = Metabolism(pl); if (mm != null) g["metabolism"] = mm; }
                if (want("team")) g["team"] = Team(pl);
                if (want("clan")) { object c; if (Clan(pl, sid, out c)) g["clan"] = c; }
                if (want("building_privilege")) { try { g["building_privilege"] = new Map().Put("authorized", pl.IsBuildingAuthed()); } catch (Exception) { } }
                if (want("blueprints_learned")) g["blueprints_learned"] = p.BlueprintCount(pl, 10000);
                if (want("mounted_entity")) g["mounted_entity"] = Mounted(pl);
                if (want("owner")) g["owner"] = IsOwner(id);
                if (want("admin")) g["admin"] = IsAdminRole(pl, id, sid);
                if (want("moderator")) g["moderator"] = IsModerator(id, sid);
                if (want("banned")) g["banned"] = ServerUsers.Is(id, ServerUsers.UserGroup.Banned);
                return g;
            }

            private static FieldInfo heartrateField;
            private static bool heartrateLooked;

            private static Map Metabolism(BasePlayer pl)
            {
                var mt = pl.metabolism;
                if (mt == null) return null;
                var m = new Map();
                Action<string, MetabolismAttribute> put = (k, a) => { if (a != null) m[k] = R3(a.value); };
                put("calories", mt.calories);
                put("hydration", mt.hydration);
                put("radiation_level", mt.radiation_level);
                put("radiation_poison", mt.radiation_poison);
                put("bleeding", mt.bleeding);
                put("temperature", mt.temperature);
                put("wetness", mt.wetness);
                put("oxygen", mt.oxygen);
                put("poison", mt.poison);
                put("comfort", mt.comfort);
                try
                {
                    if (!heartrateLooked) { heartrateLooked = true; heartrateField = mt.GetType().GetField("heartrate", BindingFlags.Public | BindingFlags.Instance); }
                    if (heartrateField != null) put("heartrate", heartrateField.GetValue(mt) as MetabolismAttribute);
                }
                catch (Exception) { }
                return m;
            }

            private static object Team(BasePlayer pl)
            {
                try
                {
                    if (pl.currentTeam == 0) return null;
                    var rm = RelationshipManager.ServerInstance;
                    var t = rm != null ? rm.FindTeam(pl.currentTeam) : null;
                    if (t == null) return null;
                    var members = new List<object>();
                    foreach (ulong mid in t.members)
                    {
                        var mp = BasePlayer.FindAwakeOrSleepingByID(mid);
                        members.Add(new Map().Put("id", mid.ToString()).Put("name", mp != null ? mp.displayName : mid.ToString()).Put("online", BasePlayer.FindByID(mid) != null));
                    }
                    return new Map().Put("id", (long)t.teamID).Put("leader", t.teamLeader.ToString()).Put("members", members);
                }
                catch (Exception) { return null; }
            }

            private bool Clan(BasePlayer pl, string sid, out object clan)
            {
                clan = null;
                try
                {
                    long cid = pl.clanId;
                    if (cid > 0) { clan = new Map().Put("id", cid); return true; }
                    if (p.Clans != null)
                    {
                        var tag = p.Clans.Call("GetClanOf", sid) as string;
                        clan = string.IsNullOrEmpty(tag) ? null : new Map().Put("tag", tag);
                        return true;
                    }
                }
                catch (Exception) { }
                return false;
            }

            private static object Mounted(BasePlayer pl)
            {
                try
                {
                    var mo = pl.GetMounted();
                    if (mo == null) return null;
                    return new Map().Put("shortname", mo.ShortPrefabName ?? "").Put("net_id", (long)ToU64(mo.net.ID)).Put("position", Pos(mo));
                }
                catch (Exception) { return null; }
            }

            // ---------- items and inventory ----------

            private static List<Item> Ordered(ItemContainer c)
            {
                var l = new List<Item>();
                if (c == null || c.itemList == null) return l;
                foreach (var i in c.itemList) if (i != null && i.info != null) l.Add(i);
                l.Sort((a, b) => a.position.CompareTo(b.position));
                return l;
            }

            /// <summary>Generic item record + Rust extras (D6). hash/fingerprint are added off-thread.</summary>
            public ItemMap BuildItem(Item item, int depth, int? slotInParent)
            {
                var info = item.info;
                var m = new ItemMap();
                m["id"] = info.shortname;
                m["count"] = (long)item.amount;
                if (slotInParent.HasValue) m["slot"] = (long)slotInParent.Value;
                if (!string.IsNullOrEmpty(item.name)) m["name"] = new Map().Put("plain", Plain(item.name));
                if (item.skin != 0) m["skin"] = item.skin;
                if (item.hasCondition)
                {
                    m["condition"] = R3(item.condition);
                    m["max_condition"] = R3(item.maxCondition);
                }
                try
                {
                    var proj = item.GetHeldEntity() as BaseProjectile;
                    if (proj != null && proj.primaryMagazine != null)
                    {
                        var am = new Map().Put("count", (long)proj.primaryMagazine.contents);
                        var at = proj.primaryMagazine.ammoType;
                        if (at != null) am["id"] = at.shortname;
                        m["ammo"] = am;
                    }
                }
                catch (Exception) { }
                var comps = new Map().Put("uid", ToU64(item.uid)).Put("definition_id", (long)info.itemid);
                try
                {
                    if (item.IsBlueprint() && item.blueprintTargetDef != null)
                    {
                        comps["blueprint_target"] = item.blueprintTargetDef.shortname;
                    }
                }
                catch (Exception) { }
                m["components"] = comps;
                if (depth < 4 && item.contents != null)
                {
                    var kids = Ordered(item.contents);
                    if (kids.Count > 0)
                    {
                        var l = new List<object>();
                        foreach (var k in kids) l.Add(BuildItem(k, depth + 1, k.position));
                        m["contents"] = l;
                    }
                }
                return m;
            }

            private ItemContainer BackpackOf(ulong id)
            {
                if (p.Backpacks == null) return null;
                try { return p.Backpacks.Call("API_GetExistingBackpack", id) as ItemContainer; } catch (Exception) { return null; }
            }

            private List<KeyValuePair<string, ItemContainer>> Containers(BasePlayer pl)
            {
                var l = new List<KeyValuePair<string, ItemContainer>>();
                var inv = pl.inventory;
                if (inv == null) return l;
                l.Add(new KeyValuePair<string, ItemContainer>("main", inv.containerMain));
                l.Add(new KeyValuePair<string, ItemContainer>("belt", inv.containerBelt));
                l.Add(new KeyValuePair<string, ItemContainer>("wear", inv.containerWear));
                ulong id = pl.userID;
                var bp = BackpackOf(id);
                if (bp != null) l.Add(new KeyValuePair<string, ItemContainer>("backpack", bp));
                return l;
            }

            public Map PlayerInventory(string sid)
            {
                var pl = FindAny(ulong.Parse(sid));
                if (pl == null) throw Err("not_found", "Player is not online or sleeping");
                var list = new List<object>();
                foreach (var kv in Containers(pl))
                {
                    if (kv.Value == null) continue;
                    var slots = new List<object>();
                    foreach (var it in Ordered(kv.Value)) slots.Add(new Map().Put("slot", (long)it.position).Put("item", BuildItem(it, 0, null)));
                    list.Add(new Map().Put("id", kv.Key).Put("size", (long)kv.Value.capacity).Put("slots", slots));
                }
                return new Map().Put("containers", list);
            }

            private Item FindItem(BasePlayer pl, string container, int slot, int[] path)
            {
                ItemContainer c = null;
                foreach (var kv in Containers(pl)) if (kv.Key == container) c = kv.Value;
                if (c == null) throw Err("not_found", "Unknown container: " + container);
                Item found = null;
                foreach (var it in Ordered(c)) if (it.position == slot) { found = it; break; }
                if (found == null) throw Err("not_found", "Slot is empty");
                if (path != null)
                    foreach (int idx in path)
                    {
                        var kids = found.contents != null ? Ordered(found.contents) : new List<Item>();
                        if (idx < 0 || idx >= kids.Count) throw Err("not_found", "No nested item at that path");
                        found = kids[idx];
                    }
                return found;
            }

            public Map ItemGet(string sid, string container, int slot, int[] path)
            {
                var pl = FindAny(ulong.Parse(sid));
                if (pl == null) throw Err("not_found", "Player is not online or sleeping");
                var it = FindItem(pl, container, slot, path);
                return new Map().Put("item", BuildItem(it, path == null ? 0 : path.Length, null));
            }

            // ---------- positions ----------

            public PosSample[] SamplePositions()
            {
                var list = BasePlayer.activePlayerList;
                var arr = new PosSample[list.Count];
                int n = 0;
                string world = WorldId();
                foreach (BasePlayer pl in list)
                {
                    if (pl == null || !IsReal(pl)) continue;
                    var v = pl.transform.position;
                    arr[n++] = new PosSample { Id = pl.UserIDString, Name = pl.displayName, World = world, X = v.x, Y = v.y, Z = v.z, State = pl.IsDead() ? (byte)1 : (pl.IsSpectating() ? (byte)2 : (byte)0) };
                }
                if (n != arr.Length) Array.Resize(ref arr, n);
                return arr;
            }

            // ---------- actions ----------

            private static Map Ok(string msg, object result)
            {
                var m = new Map().Put("ok", true);
                if (msg != null) m["message"] = msg;
                if (result != null) m["result"] = result;
                return m;
            }

            private static Map Refused(string msg) { return new Map().Put("ok", false).Put("message", msg); }

            private static double Amount(Map args, double min, double max)
            {
                object o;
                if (!args.TryGetValue("amount", out o)) throw Err("invalid_args", "'amount' is required");
                return Protocol.Check.Number(o, "amount", min, max);
            }

            private void CheckExpect(BasePlayer pl, Map expect)
            {
                string container = expect["container"] as string;
                if (container == null) throw Err("invalid_args", "expect.container must be a string");
                int slot = Protocol.Check.Slot(expect["slot"], "expect.slot");
                Item it;
                try { it = FindItem(pl, container, slot, null); }
                catch (ProtoException e) { if (e.Code == "not_found") throw Err("conflict", "Expected item is not in the slot"); throw; }
                var rec = BuildItem(it, 0, null);
                object o;
                if (expect.TryGetValue("id", out o) && !string.Equals(o as string, (string)rec["id"], StringComparison.Ordinal)) throw Err("conflict", "Item id changed");
                if (expect.TryGetValue("count", out o) && Protocol.Check.Integer(o, "expect.count", 0, null) != (long)rec["count"]) throw Err("conflict", "Item count changed");
                if (expect.TryGetValue("fingerprint", out o) && !string.Equals(o as string, Protocol.Canon.Fingerprint(rec), StringComparison.Ordinal)) throw Err("conflict", "Item changed");
            }

            public Map RunAction(string action, string target, Map args, Map expect)
            {
                if (action == "save") return DoSave();
                ulong id = ulong.Parse(target);
                BasePlayer pl;
                switch (action)
                {
                    // list/ban state only: the target need not exist in the world
                    case "ban": return DoBan(id, args);
                    case "unban": return DoUnban(id);
                    case "make_owner": return DoRole(id, ServerUsers.UserGroup.Owner, true);
                    case "remove_owner": return DoRole(id, ServerUsers.UserGroup.Owner, false);
                    case "make_moderator": return DoRole(id, ServerUsers.UserGroup.Moderator, true);
                    case "remove_moderator": return DoRole(id, ServerUsers.UserGroup.Moderator, false);
                }
                bool needsOnline = action == "kick" || action == "teleport";
                pl = needsOnline ? FindOnline(id) : FindAny(id);
                if (pl == null)
                {
                    if (needsOnline && FindAny(id) != null) throw Err("offline", "Player is sleeping, not connected");
                    throw Err("not_found", "Player is not online or sleeping");
                }
                if (expect != null) CheckExpect(pl, expect);
                switch (action)
                {
                    case "kick": return DoKick(pl, args);
                    case "kill": pl.Die(null); return Ok("Killed " + pl.displayName, null);
                    case "heal": return Heal(pl);
                    case "hurt": return Hurt(pl, (float)Amount(args, 0, 100));
                    case "sethealth": return SetHealth(pl, (float)Amount(args, 0, 100));
                    case "set_calories": return SetMeta(pl, true, (float)Amount(args, 0, 500));
                    case "set_hydration": return SetMeta(pl, false, (float)Amount(args, 0, 250));
                    case "give_item": return GiveItem(pl, args);
                    case "clear_inventory": pl.inventory.Strip(); pl.SendNetworkUpdateImmediate(); return Ok("Inventory cleared", null);
                    case "teleport": return Teleport(pl, args);
                    case "mute": pl.SetPlayerFlag(BasePlayer.PlayerFlags.ChatMute, true); pl.SendNetworkUpdateImmediate(); return Ok("Muted " + pl.displayName, null);
                    case "unmute": pl.SetPlayerFlag(BasePlayer.PlayerFlags.ChatMute, false); pl.SendNetworkUpdateImmediate(); return Ok("Unmuted " + pl.displayName, null);
                }
                throw Err("unsupported", "action not available: " + action);
            }

            public Map DoKick(BasePlayer pl, Map args)
            {
                string reason = "Kicked";
                object o;
                if (args.TryGetValue("reason", out o) && o is string && ((string)o).Length > 0) reason = (string)o;
                pl.Kick(reason);
                return Ok("Kicked " + pl.displayName, null);
            }

            public Map Heal(BasePlayer pl)
            {
                pl.Heal(pl.MaxHealth());
                pl.SendNetworkUpdateImmediate();
                return Ok("Healed " + pl.displayName, null);
            }

            public Map Hurt(BasePlayer pl, float amount)
            {
                pl.Hurt(amount);
                pl.SendNetworkUpdateImmediate();
                return Ok("Hurt " + pl.displayName, null);
            }

            public Map SetHealth(BasePlayer pl, float amount)
            {
                pl.SetHealth(Mathf.Clamp(amount, 0f, pl.MaxHealth()));
                if (pl.health <= 0f) pl.Die(null);
                pl.SendNetworkUpdateImmediate();
                return Ok("Health set", null);
            }

            public Map SetMeta(BasePlayer pl, bool calories, float amount)
            {
                var mt = pl.metabolism;
                if (mt == null) throw Err("internal", "Player has no metabolism");
                if (calories) mt.calories.SetValue(Mathf.Clamp(amount, 0f, 500f));
                else mt.hydration.SetValue(Mathf.Clamp(amount, 0f, 250f));
                pl.SendNetworkUpdateImmediate();
                return Ok(calories ? "Calories set" : "Hydration set", null);
            }

            public Map GiveItem(BasePlayer pl, Map args)
            {
                object so, ao;
                if (!args.TryGetValue("item", out so) || !(so is string)) throw Err("invalid_args", "'item' is required");
                if (!args.TryGetValue("amount", out ao)) throw Err("invalid_args", "'amount' is required");
                string shortname = (string)so;
                if (!Protocol.Check.IsShortname(shortname)) throw Err("invalid_args", "'item' is not a valid shortname");
                int amount = (int)Protocol.Check.Integer(ao, "amount", 1, 64000);
                var def = ItemManager.FindItemDefinition(shortname);
                if (def == null) throw Err("not_found", "Unknown item shortname: " + shortname);
                int stack = Math.Max(1, def.stackable);
                int stacks = (amount + stack - 1) / stack;
                if (stacks > 256) throw Err("invalid_args", "amount needs more than 256 stacks of " + shortname);
                int left = amount;
                while (left > 0)
                {
                    int n = Math.Min(left, stack);
                    var item = ItemManager.CreateByName(shortname, n);
                    if (item == null) throw Err("not_found", "Unknown item shortname: " + shortname);
                    pl.GiveItem(item);
                    left -= n;
                }
                pl.SendNetworkUpdateImmediate();
                return Ok("Gave " + amount + " x " + shortname, new Map().Put("given", (long)amount));
            }

            public Map Teleport(BasePlayer pl, Map args)
            {
                bool hasTo = args.ContainsKey("to_player");
                bool anyXyz = args.ContainsKey("x") || args.ContainsKey("y") || args.ContainsKey("z");
                bool allXyz = args.ContainsKey("x") && args.ContainsKey("y") && args.ContainsKey("z");
                if (hasTo && (anyXyz || args.ContainsKey("world"))) throw Err("invalid_args", "use either to_player or x/y/z, not both");
                if (!hasTo && !allXyz) throw Err("invalid_args", "teleport needs to_player or all of x, y, z");
                if (!pl.IsAlive()) throw Err("conflict", "Player is dead");
                float x, y, z;
                if (hasTo)
                {
                    ulong did = Protocol.Check.SteamId(args["to_player"], "to_player");
                    var dst = FindOnline(did);
                    if (dst == null) throw Err("not_found", "Destination player is not online");
                    if (dst == pl) throw Err("invalid_args", "Destination is the player itself");
                    var v = dst.transform.position;
                    x = v.x; y = v.y; z = v.z;
                }
                else
                {
                    object wo;
                    if (args.TryGetValue("world", out wo) && !string.Equals(wo as string, WorldId(), StringComparison.Ordinal)) throw Err("not_found", "Unknown world");
                    double half = ConVar.Server.worldsize / 2.0;
                    if (half <= 0) half = 4000;
                    x = (float)Protocol.Check.Number(args["x"], "x", -half, half);
                    y = (float)Protocol.Check.Number(args["y"], "y", MinY, MaxY);
                    z = (float)Protocol.Check.Number(args["z"], "z", -half, half);
                }
                object ip = pl.IPlayer;
                MethodInfo mi = null;
                if (ip != null)
                    foreach (var cand in ip.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance))
                    {
                        if (cand.Name != "Teleport") continue;
                        var ps = cand.GetParameters();
                        if (ps.Length == 3 && (ps[0].ParameterType == typeof(float) || ps[0].ParameterType == typeof(double))) { mi = cand; break; }
                    }
                if (mi == null) throw Err("unsupported", "Teleport is not available on this framework build");
                bool dbl = mi.GetParameters()[0].ParameterType == typeof(double);
                mi.Invoke(ip, dbl ? new object[] { (double)x, (double)y, (double)z } : new object[] { x, y, z });
                var after = pl.transform.position;
                return Ok("Teleported " + pl.displayName, new Map().Put("world", WorldId()).Put("x", R3(after.x)).Put("y", R3(after.y)).Put("z", R3(after.z)));
            }

            private Map DoBan(ulong id, Map args)
            {
                if (ServerUsers.Is(id, ServerUsers.UserGroup.Banned)) return Refused("Player is already banned");
                string reason = "Banned";
                object o;
                if (args.TryGetValue("reason", out o) && o is string && ((string)o).Length > 0) reason = (string)o;
                var pl = FindAny(id);
                string name = pl != null ? pl.displayName : (KnownName(id) ?? id.ToString());
                ServerUsers.Set(id, ServerUsers.UserGroup.Banned, name, reason);
                ServerUsers.Save();
                var online = FindOnline(id);
                if (online != null) online.Kick("Banned: " + reason);
                return Ok("Banned " + name, null);
            }

            private Map DoUnban(ulong id)
            {
                if (!ServerUsers.Is(id, ServerUsers.UserGroup.Banned)) return Refused("Player is not banned");
                ServerUsers.Remove(id);
                ServerUsers.Save();
                return Ok("Unbanned " + id, null);
            }

            private Map DoRole(ulong id, ServerUsers.UserGroup group, bool add)
            {
                string label = group == ServerUsers.UserGroup.Owner ? "owner" : "moderator";
                if (add)
                {
                    if (ServerUsers.Is(id, group)) return Refused("Player is already " + label);
                    var pl = FindAny(id);
                    string name = pl != null ? pl.displayName : (KnownName(id) ?? id.ToString());
                    ServerUsers.Set(id, group, name, "");
                }
                else
                {
                    if (!ServerUsers.Is(id, group)) return Refused("Player is not " + label);
                    ServerUsers.Remove(id);
                }
                ServerUsers.Save();
                try
                {
                    var on = FindOnline(id);
                    if (on != null && on.net != null && on.net.connection != null)
                        on.net.connection.authLevel = ServerUsers.Is(id, ServerUsers.UserGroup.Owner) ? 2u : (ServerUsers.Is(id, ServerUsers.UserGroup.Moderator) ? 1u : 0u);
                }
                catch (Exception) { }
                return Ok((add ? "Added " : "Removed ") + label, null);
            }

            private Map DoSave()
            {
                bool done = SaveRestore.Save(true);
                return done ? Ok("World saved", null) : Refused("Save did not complete");
            }
        }

        #endregion

        #region Plugin

        [PluginReference] private Plugin Backpacks;
        [PluginReference] private Plugin Clans;

        private readonly Dictionary<ulong, long> connectedAt = new Dictionary<ulong, long>();
        private BridgeConfig cfg = new BridgeConfig();
        private RustProvider provider;
        private BridgeCore bridge;
        private Transport transport;
        private bool started;
        private readonly List<Action> stoppers = new List<Action>();
        private readonly Dictionary<ulong, long[]> damageWindow = new Dictionary<ulong, long[]>();
        private int exportBusy;
        private bool subChat, subCommand, subDamage;
        private const string BridgeDir = ".playermanager";

        private void Log(string s) { Puts("{0}", "[PlayerManager] " + s); }

        private void Init()
        {
            // High-volume hooks are only subscribed while an events subscription asks for them.
            Unsubscribe(nameof(OnEntityTakeDamage));
            Unsubscribe(nameof(OnPlayerChat));
            Unsubscribe(nameof(OnPlayerCommand));
            Unsubscribe(nameof(OnServerCommand));
        }

        private void OnServerInitialized()
        {
            if (started) return;
            started = true;
            foreach (BasePlayer pl in BasePlayer.activePlayerList)
            {
                if (!IsReal(pl)) continue;
                ulong id = pl.userID;
                connectedAt[id] = UnixNow() - Math.Max(0, pl.secondsConnected);
            }
            try { StartBridge(); }
            catch (Exception e) { PrintError("bridge failed to start (file export continues): " + e); }
            var t1 = timer.Every(15f, ExportSnapshot);
            stoppers.Add(() => t1.Destroy());
            timer.Once(2f, ExportSnapshot);
        }

        private void StartBridge()
        {
            cfg = Transport.LoadConfig(BridgeDir, Log);
            provider = new RustProvider(this);
            bridge = new BridgeCore(provider, cfg, BridgeVersionDefault, false);
            bridge.Log = Log;
            bridge.SetHello(provider.Describe());
            bridge.EventDemandChanged = mask => bridge.RunOnMain(() => ApplyDemand(mask));
            transport = new Transport(bridge, BridgeDir, Log);
            // No timer runs while the bridge is idle: queued work wakes the pump (NextTick is thread-safe), the pump drains
            // within the budget and re-arms itself every 50 ms only while a backlog remains.
            bridge.OnMainWork = () => NextTick(PumpDrain);
            stoppers.Add(() => { pumping = false; if (pumpTimer != null) pumpTimer.Destroy(); });
            pumping = true;
            // hello facts are re-described every 30 s only while a client is connected, and once when the first one arrives.
            bridge.OnActive = active => bridge.RunOnMain(() => SetActive(active));
            stoppers.Add(() => { if (refreshTimer != null) refreshTimer.Destroy(); });
            var b = bridge; var tr = transport;
            // Binding and file work happen off the game thread.
            var th = new Thread(() =>
            {
                try
                {
                    b.Start();
                    if (!tr.Start()) { Log("no transport could be bound; bridge disabled, file export continues"); b.Stop(); return; }
                    tr.WriteBridgeJson(BridgeVersionDefault);
                    Log("bridge " + BridgeVersionDefault + " ready (" + tr.Mode + (tr.Mode == "tcp" ? ", port " + tr.Port : "") + ")");
                }
                catch (Exception e) { Log("bridge start failed: " + e); }
            }) { IsBackground = true, Name = "pm-start" };
            th.Start();
        }

        private Timer pumpTimer, refreshTimer;
        private volatile bool pumping;

        private void PumpDrain()
        {
            if (!pumping || bridge == null) return;
            if (bridge.DrainStep(20, 4.0)) pumpTimer = timer.Once(0.05f, PumpDrain);
        }

        private void RefreshHello()
        {
            try { bridge.SetHello(provider.Describe()); } catch (Exception e) { Log("describe failed: " + e.Message); }
        }

        private void SetActive(bool active)
        {
            if (refreshTimer != null) { refreshTimer.Destroy(); refreshTimer = null; }
            if (!active || !pumping) return;
            RefreshHello();
            refreshTimer = timer.Every(30f, RefreshHello);
        }

        private void Unload()
        {
            foreach (var s in stoppers) { try { s(); } catch (Exception) { } }
            stoppers.Clear();
            try { if (bridge != null) bridge.Stop(); } catch (Exception) { }
            try { if (transport != null) transport.Stop(); } catch (Exception) { }
            try { ExportSnapshotNow(); } catch (Exception) { }
        }

        private void ApplyDemand(int mask)
        {
            Toggle((mask & EventBit("chat")) != 0, ref subChat, "OnPlayerChat");
            Toggle((mask & EventBit("command")) != 0, ref subCommand, "OnPlayerCommand", "OnServerCommand");
            Toggle((mask & EventBit("damage")) != 0, ref subDamage, "OnEntityTakeDamage");
        }

        private void Toggle(bool want, ref bool current, params string[] hooks)
        {
            if (want == current) return;
            current = want;
            foreach (var h in hooks) { if (want) Subscribe(h); else Unsubscribe(h); }
        }

        // ---------- events (main thread; they only enqueue) ----------

        private Map Ev(string type, BasePlayer pl)
        {
            var m = new Map().Put("type", type).Put("time", UnixNow());
            if (pl != null) m["player"] = new Map().Put("id", pl.UserIDString).Put("name", pl.displayName ?? pl.UserIDString);
            return m;
        }

        private bool Wants(string type) { return bridge != null && bridge.Wants(type); }

        private void OnPlayerConnected(BasePlayer player)
        {
            if (!IsReal(player)) return;
            ulong id = player.userID;
            connectedAt[id] = UnixNow();
            if (bridge != null) bridge.InvalidateReads(null);
            if (Wants("join")) bridge.Emit("join", Ev("join", player).Put("world", provider.WorldId()));
            timer.Once(1f, ExportSnapshot);
        }

        private void OnPlayerDisconnected(BasePlayer player, string reason)
        {
            if (!IsReal(player)) return;
            ulong id = player.userID;
            connectedAt.Remove(id);
            damageWindow.Remove(id);
            blueprintCache.Remove(id);
            if (bridge != null) bridge.InvalidateReads(player.UserIDString);
            if (Wants("leave")) bridge.Emit("leave", Ev("leave", player).PutIf(!string.IsNullOrEmpty(reason), "reason", Plain(reason)));
            timer.Once(1f, ExportSnapshot);
        }

        private void OnPlayerDeath(BasePlayer player, HitInfo info)
        {
            if (!Wants("death") || !IsReal(player)) return;
            string name = player.displayName ?? player.UserIDString;
            var m = Ev("death", player);
            string cause = null;
            Map killer = null;
            try
            {
                if (info != null)
                {
                    cause = info.damageTypes.GetMajorityDamageType().ToString().ToLowerInvariant();
                    var ip = info.InitiatorPlayer;
                    if (ip != null && ip != player)
                        killer = new Map().Put("type", "player").Put("id", ip.UserIDString).Put("name", ip.displayName ?? ip.UserIDString);
                    else if (info.Initiator != null && info.Initiator != player)
                        killer = new Map().Put("type", info.Initiator.ShortPrefabName ?? "entity");
                }
            }
            catch (Exception) { }
            string msg = name + " died";
            if (killer != null && killer.ContainsKey("name")) msg = name + " was killed by " + killer["name"];
            else if (killer != null) msg = name + " was killed by " + killer["type"];
            else if (cause != null) msg = name + " died (" + cause + ")";
            m["message"] = msg;
            if (cause != null) m["cause"] = cause;
            if (killer != null) m["killer"] = killer;
            try { var v = player.transform.position; m["position"] = new Map().Put("x", R3(v.x)).Put("y", R3(v.y)).Put("z", R3(v.z)); } catch (Exception) { }
            m["world"] = provider.WorldId();
            bridge.Emit("death", m);
        }

        private void OnPlayerChat(BasePlayer player, string message, ConVar.Chat.ChatChannel channel)
        {
            if (!Wants("chat") || !IsReal(player)) return;
            bridge.Emit("chat", Ev("chat", player).Put("message", Plain(message)).Put("channel", channel.ToString().ToLowerInvariant()));
        }

        private string Redact(string command)
        {
            if (string.IsNullOrEmpty(command)) return "";
            string first = command.Trim().Split(' ')[0].TrimStart('/').ToLowerInvariant();
            foreach (var r in cfg.RedactCommands)
                if (first == r || (r.EndsWith(".") && first.StartsWith(r))) return first + " [redacted]";
            return command.Trim().TrimStart('/');
        }

        private void OnPlayerCommand(BasePlayer player, string command, string[] args)
        {
            if (!Wants("command") || !IsReal(player)) return;
            string line = (command ?? "").TrimStart('/') + (args != null && args.Length > 0 ? " " + string.Join(" ", args) : "");
            if (line.Length > 512) line = line.Substring(0, 512);
            bridge.Emit("command", Ev("command", player).Put("command", Redact(line)).Put("source", "player"));
        }

        private void OnServerCommand(ConsoleSystem.Arg arg)
        {
            if (!Wants("command") || arg == null || arg.Connection != null) return;   // client-originated commands are reported by OnPlayerCommand
            string line = arg.cmd != null ? arg.cmd.FullName : "";
            if (string.IsNullOrEmpty(line) || line.StartsWith("chat.")) return;
            var rest = arg.Args;
            if (rest != null && rest.Length > 0) line += " " + string.Join(" ", rest);
            if (line.Length > 512) line = line.Substring(0, 512);
            bridge.Emit("command", new Map().Put("type", "command").Put("time", UnixNow()).Put("command", Redact(line)).Put("source", "console"));
        }

        private void OnEntityTakeDamage(BaseCombatEntity entity, HitInfo info)
        {
            var pl = entity as BasePlayer;
            if (pl == null || info == null || !IsReal(pl)) return;
            ulong id = pl.userID;
            long sec = UnixNow();
            long[] w;
            if (!damageWindow.TryGetValue(id, out w)) { w = new long[2]; damageWindow[id] = w; }
            if (w[0] != sec) { w[0] = sec; w[1] = 0; }
            if (++w[1] > 4) return;                                   // at most 4 per player per second
            float amount = 0f;
            string cause = "unknown";
            try { amount = info.damageTypes.Total(); cause = info.damageTypes.GetMajorityDamageType().ToString().ToLowerInvariant(); } catch (Exception) { }
            var m = Ev("damage", pl).Put("amount", R3(amount)).Put("cause", cause).Put("health", R3(Math.Max(0f, pl.health - amount)));
            try
            {
                var ip = info.InitiatorPlayer;
                if (ip != null) m["attacker"] = new Map().Put("type", "player").Put("id", ip.UserIDString).Put("name", ip.displayName ?? ip.UserIDString);
                else if (info.Initiator != null) m["attacker"] = new Map().Put("type", info.Initiator.ShortPrefabName ?? "entity");
            }
            catch (Exception) { }
            bridge.Emit("damage", m);
        }

        private void OnPlayerKicked(BasePlayer player, string reason)
        {
            if (!Wants("kick") || !IsReal(player)) return;
            bridge.Emit("kick", Ev("kick", player).PutIf(!string.IsNullOrEmpty(reason), "reason", Plain(reason)));
        }

        private void OnServerUserSet(ulong steamId, ServerUsers.UserGroup group, string playerName, string reason, long expiry)
        {
            sleeperCache.Clear();   // bans/roles shown in the cached sleeper records may have changed
            if (bridge != null) bridge.InvalidateReads(null);
            if (group != ServerUsers.UserGroup.Banned || !Wants("ban")) return;
            var m = new Map().Put("type", "ban").Put("time", UnixNow())
                .Put("player", new Map().Put("id", steamId.ToString()).Put("name", string.IsNullOrEmpty(playerName) ? steamId.ToString() : playerName));
            if (!string.IsNullOrEmpty(reason)) m["reason"] = reason;
            if (expiry > 0) m["expires_at"] = expiry;
            bridge.Emit("ban", m);
        }

        private void OnServerSave() { ExportSnapshot(); }

        #endregion

        #region Legacy console commands (server console / RCON only; used when the bridge socket is not reachable)

        // Marker kept for the panel's ready-check: pm.hurt pm.heal pm.give pm.setmetabolism
        private static bool ServerOnly(ConsoleSystem.Arg arg)
        {
            if (arg.Connection != null) { arg.ReplyWith("Server console only."); return false; }
            return true;
        }

        private BasePlayer LegacyTarget(ConsoleSystem.Arg arg)
        {
            var a = arg.Args;
            if (a == null || a.Length < 1) { arg.ReplyWith("A Steam64 ID is required."); return null; }
            ulong id;
            if (!ulong.TryParse(a[0], out id)) { arg.ReplyWith("Player not found."); return null; }
            var pl = BasePlayer.FindByID(id) ?? BasePlayer.FindSleeping(id);
            if (pl == null) arg.ReplyWith("Player not found.");
            return pl;
        }

        [ConsoleCommand("pm.heal")]
        private void CmdHeal(ConsoleSystem.Arg arg)
        {
            if (!ServerOnly(arg)) return;
            var pl = LegacyTarget(arg); if (pl == null) return;
            new RustProvider(this).Heal(pl);
            arg.ReplyWith("Player Manager action completed for " + pl.UserIDString);
        }

        [ConsoleCommand("pm.hurt")]
        private void CmdHurt(ConsoleSystem.Arg arg)
        {
            if (!ServerOnly(arg)) return;
            var pl = LegacyTarget(arg); if (pl == null) return;
            var a = arg.Args;
            float amount;
            if (a.Length != 2 || !float.TryParse(a[1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out amount) || float.IsNaN(amount) || float.IsInfinity(amount) || amount < 0 || amount > 100)
            { arg.ReplyWith("Damage must be between 0 and 100."); return; }
            new RustProvider(this).Hurt(pl, amount);
            arg.ReplyWith("Player Manager action completed for " + pl.UserIDString);
        }

        [ConsoleCommand("pm.give")]
        private void CmdGive(ConsoleSystem.Arg arg)
        {
            if (!ServerOnly(arg)) return;
            var pl = LegacyTarget(arg); if (pl == null) return;
            var a = arg.Args;
            int count;
            if (a.Length != 3 || !int.TryParse(a[2], out count) || count < 1 || count > 64000 || !Protocol.Check.IsShortname(a[1]))
            { arg.ReplyWith("An item shortname and count (1-64000) are required."); return; }
            try { new RustProvider(this).GiveItem(pl, new Map().Put("item", a[1]).Put("amount", (long)count)); }
            catch (ProtoException e) { arg.ReplyWith(e.Code == "not_found" ? "Unknown item shortname." : e.Message); return; }
            arg.ReplyWith("Player Manager action completed for " + pl.UserIDString);
        }

        [ConsoleCommand("pm.setmetabolism")]
        private void CmdSetMetabolism(ConsoleSystem.Arg arg)
        {
            if (!ServerOnly(arg)) return;
            var a = arg.Args;
            if (a == null || a.Length < 3) { arg.ReplyWith("Usage: pm.setmetabolism <player> <type> <value>"); return; }
            var pl = LegacyTarget(arg); if (pl == null) return;
            string type = a[1].ToLowerInvariant();
            float value;
            if (!float.TryParse(a[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out value) || float.IsNaN(value) || float.IsInfinity(value)) { arg.ReplyWith("Invalid value."); return; }
            var prov = new RustProvider(this);
            switch (type)
            {
                case "health": prov.SetHealth(pl, value); break;
                case "calories": prov.SetMeta(pl, true, value); break;
                case "hydration": prov.SetMeta(pl, false, value); break;
                default: arg.ReplyWith("Unknown type: " + type + ". Use health, calories, or hydration."); return;
            }
            arg.ReplyWith("Set " + type + " to " + value + " for " + pl.displayName + ".");
        }

        #endregion

        #region Legacy file export (oxide|carbon/data/PlayerManagerRust.json, shapes unchanged except where rust.md section 12 says otherwise)

        private void ExportSnapshot()
        {
            try { ExportSnapshotNow(); }
            catch (Exception ex) { PrintError("Failed to export player snapshot: " + ex.Message); }
        }

        private const string StampPlaceholder = "@@PM-GENERATED-AT@@";
        private string lastExportHash;
        private long lastExportWriteMs;
        public int ExportsSkipped, ExportsWritten;

        private void ExportSnapshotNow()
        {
            // Cheapest check first: if the previous write is still in progress there is no point reading the game state.
            if (Interlocked.CompareExchange(ref exportBusy, 1, 0) != 0) return;
            RawSnapshot raw;
            try { raw = BuildSnapshot(); }
            catch (Exception) { Interlocked.Exchange(ref exportBusy, 0); throw; }
            string path = Path.Combine(Interface.Oxide.DataDirectory, "PlayerManagerRust.json");
            // The game thread only copies primitives. Sorting, projection, serialisation, hashing and the write run on a pool thread.
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    var snap = raw.Materialize();
                    snap.GeneratedAt = StampPlaceholder;
                    string json = JsonConvert.SerializeObject(snap);
                    string hash;
                    using (var sha = SHA256.Create()) hash = Convert.ToBase64String(sha.ComputeHash(Encoding.UTF8.GetBytes(json)));
                    long now = cacheClock.ElapsedMilliseconds;
                    // Unchanged content is not rewritten while the file is still fresh. The panel rejects a generated_at older than 30 s,
                    // so an unchanged snapshot is still re-stamped once the file is 12 s old (the 15 s schedule is kept).
                    if (hash == lastExportHash && now - lastExportWriteMs < 12000) { Interlocked.Increment(ref ExportsSkipped); return; }
                    json = json.Replace(StampPlaceholder, raw.GeneratedAt);
                    Transport.AtomicWrite(path, json);
                    lastExportHash = hash; lastExportWriteMs = now;
                    Interlocked.Increment(ref ExportsWritten);
                }
                catch (Exception e) { Log("export write failed: " + e.Message); }
                finally { Interlocked.Exchange(ref exportBusy, 0); }
            });
        }

        /// <summary>Game-thread copy of everything the export needs; projection into the file shape happens off-thread.</summary>
        private sealed class RawSnapshot
        {
            public string GeneratedAt;
            public ServerInfoRecord ServerInfo;
            public List<PlayerRecord> Players = new List<PlayerRecord>();
            public List<object> Roles = new List<object>(), Bans = new List<object>();
            public bool Backpacks;

            public Snapshot Materialize()
            {
                var online = new List<PlayerRecord>();
                var sleeping = new List<PlayerRecord>();
                foreach (var pr in Players) (pr.Online ? online : sleeping).Add(pr);
                var banned = new List<BannedRecord>();
                foreach (var o in Bans) { var b = (Map)o; banned.Add(new BannedRecord { Id = (string)b["id"], Name = (string)b["name"], Reason = (string)b["reason"] }); }
                var admins = new List<RoleRecord>();
                var mods = new List<RoleRecord>();
                foreach (var o in Roles)
                {
                    var r = (Map)o;
                    var rec = new RoleRecord { Id = (string)r["id"], Name = (string)r["name"], Role = (string)r["role"] };
                    if (rec.Role != "moderator") admins.Add(rec); else mods.Add(rec);
                }
                return new Snapshot
                {
                    GeneratedAt = GeneratedAt,
                    PluginVersion = BridgeVersionDefault,
                    ServerInfo = ServerInfo,
                    OnlinePlayers = online.OrderBy(x => x.Name).ToList(),
                    SleepingPlayers = sleeping.OrderBy(x => x.Name).ToList(),
                    AllPlayers = Players.OrderBy(x => x.Name).ToList(),
                    BannedPlayers = banned,
                    Admins = admins,
                    Moderators = mods,
                    BackpacksPluginDetected = Backpacks,
                };
            }
        }

        // Sleeping players do not move, change inventory or stats; their records are rebuilt at most every 45-65 s
        // (jittered per player so refreshes spread out) instead of at every export.
        private sealed class CachedRecord { public PlayerRecord Rec; public long At; }
        private readonly Dictionary<ulong, CachedRecord> sleeperCache = new Dictionary<ulong, CachedRecord>();
        public int SleeperBuilds, SleeperHits;

        private RawSnapshot BuildSnapshot()
        {
            var raw = new RawSnapshot();
            var seen = new HashSet<string>();
            var active = BasePlayer.activePlayerList;
            for (int i = 0; i < active.Count; i++)
            {
                var pl = active[i];
                if (pl == null) continue;
                seen.Add(pl.UserIDString);
                raw.Players.Add(SerializePlayer(pl, true));
            }
            long now = cacheClock.ElapsedMilliseconds;
            var sleepers = BasePlayer.sleepingPlayerList;
            var live = new HashSet<ulong>();
            for (int i = 0; i < sleepers.Count; i++)
            {
                var pl = sleepers[i];
                if (pl == null || seen.Contains(pl.UserIDString)) continue;
                seen.Add(pl.UserIDString);
                ulong uid = pl.userID;
                live.Add(uid);
                CachedRecord c;
                if (sleeperCache.TryGetValue(uid, out c) && now - c.At < 45000 + (long)(uid % 20) * 1000) { SleeperHits++; raw.Players.Add(c.Rec); continue; }
                SleeperBuilds++;
                var rec = SerializePlayer(pl, false);
                sleeperCache[uid] = new CachedRecord { Rec = rec, At = now };
                raw.Players.Add(rec);
            }
            if (sleeperCache.Count > live.Count)
            {
                List<ulong> gone = null;
                foreach (var k in sleeperCache.Keys) if (!live.Contains(k)) { if (gone == null) gone = new List<ulong>(); gone.Add(k); }
                if (gone != null) foreach (var k in gone) sleeperCache.Remove(k);
            }
            var prov = provider ?? new RustProvider(this);
            prov.ServerInfoRoles(raw.Roles, raw.Bans);
            raw.GeneratedAt = DateTime.UtcNow.ToString("o");
            raw.ServerInfo = new ServerInfoRecord
            {
                Hostname = ConVar.Server.hostname ?? "Rust Server",
                Description = ConVar.Server.description ?? "",
                MaxPlayers = ConVar.Server.maxplayers,
                MapName = ConVar.Server.level ?? "Procedural Map",
                WorldSize = ConVar.Server.worldsize,
                Seed = (int)ConVar.Server.seed,
                Url = ConVar.Server.url ?? "",
                HeaderImage = ConVar.Server.headerimage ?? "",
            };
            raw.Backpacks = Backpacks != null;
            return raw;
        }

        private PlayerRecord SerializePlayer(BasePlayer player, bool online)
        {
            ulong uid = player.userID;
            long at;
            var rec = new PlayerRecord
            {
                Id = player.UserIDString,
                Name = player.displayName ?? player.UserIDString,
                Online = online,
                IsSleeping = player.IsSleeping(),
                IsAlive = player.IsAlive(),
                IsWounded = player.IsWounded(),
                Ping = online ? ExportPing(player) : 0,
                Address = (online && cfg.ExportAddress) ? ExportAddress(player) : null,
                Position = new PositionRecord { X = player.transform.position.x, Y = player.transform.position.y, Z = player.transform.position.z },
                Stats = new PlayerStats
                {
                    Health = player.health,
                    MaxHealth = player.MaxHealth(),
                    Hydration = player.metabolism?.hydration?.value ?? 0f,
                    Calories = player.metabolism?.calories?.value ?? 0f,
                    Radiation = player.metabolism?.radiation_level?.value ?? 0f,
                    RadiationPoison = player.metabolism?.radiation_poison?.value ?? 0f,
                    Temperature = player.metabolism?.temperature?.value ?? 0f,
                    Comfort = player.metabolism?.comfort?.value ?? 0f,
                    Bleeding = player.metabolism?.bleeding?.value ?? 0f,
                    Oxygen = player.metabolism?.oxygen?.value ?? 0f,
                    Wetness = player.metabolism?.wetness?.value ?? 0f,
                    PlayTimeSeconds = (int)(player.lifeStory != null ? player.lifeStory.secondsAlive : 0f),
                },
                Inventory = new InventorySnapshot
                {
                    Main = SerializeContainer(player.inventory?.containerMain),
                    Belt = SerializeContainer(player.inventory?.containerBelt),
                    Wear = SerializeContainer(player.inventory?.containerWear),
                    Backpack = SerializeContainer(ExportBackpack(player)),
                },
                ActiveItem = ExportActiveItem(player),
                TeamId = player.currentTeam != 0 ? player.currentTeam.ToString() : null,
                TeamMembers = ExportTeamMembers(player),
                HasBuildingPrivilege = player.IsBuildingAuthed(),
                BlueprintsLearned = BlueprintCount(player, 120000),
                IsBanned = ServerUsers.Is(uid, ServerUsers.UserGroup.Banned),
                IsAdmin = player.IsAdmin,
                IsModerator = ServerUsers.Is(uid, ServerUsers.UserGroup.Moderator),
                ConnectedAt = (online && connectedAt.TryGetValue(uid, out at)) ? DateTimeOffset.FromUnixTimeSeconds(at).UtcDateTime.ToString("o") : null,
            };
            try
            {
                if (permission.UserHasGroup(player.UserIDString, "admin")) rec.IsAdmin = true;
                if (permission.UserHasGroup(player.UserIDString, "moderator")) rec.IsModerator = true;
            }
            catch (Exception) { }
            return rec;
        }

        private static string ExportActiveItem(BasePlayer player)
        {
            try { var held = player.GetActiveItem(); if (held != null && held.info != null) return held.info.shortname; } catch (Exception) { }
            return null;
        }

        private static List<string> ExportTeamMembers(BasePlayer player)
        {
            try
            {
                if (player.currentTeam == 0) return null;
                var team = RelationshipManager.ServerInstance?.FindTeam(player.currentTeam);
                if (team == null) return null;
                var names = new List<string>(team.members.Count);
                foreach (var id in team.members) { var p = BasePlayer.FindByID(id); names.Add(p != null ? p.displayName : id.ToString()); }
                return names;
            }
            catch (Exception) { return null; }
        }

        // Counting unlocked blueprints walks every item definition (~700 HasUnlocked calls per player), so the result is
        // cached per player: 10 s for player.get, 2 min for the legacy export. Main thread only.
        private readonly Dictionary<ulong, KeyValuePair<long, int>> blueprintCache = new Dictionary<ulong, KeyValuePair<long, int>>();
        private readonly Stopwatch cacheClock = Stopwatch.StartNew();

        private int BlueprintCount(BasePlayer player, long ttlMs)
        {
            ulong uid = player.userID;
            long now = cacheClock.ElapsedMilliseconds;
            KeyValuePair<long, int> hit;
            if (blueprintCache.TryGetValue(uid, out hit) && now - hit.Key < ttlMs) return hit.Value;
            int learned = 0;
            try
            {
                var bp = player.blueprints;
                if (bp != null)
                {
                    var defs = ItemManager.GetItemDefinitions();
                    for (int i = 0; i < defs.Count; i++) if (bp.HasUnlocked(defs[i])) learned++;
                }
            }
            catch (Exception) { learned = 0; }
            blueprintCache[uid] = new KeyValuePair<long, int>(now, learned);
            return learned;
        }

        private static int ExportPing(BasePlayer player)
        {
            try { return Network.Net.sv.GetAveragePing(player.net.connection); } catch (Exception) { return 0; }
        }

        private static string ExportAddress(BasePlayer player)
        {
            try { return StripPort(player.net.connection.ipaddress); } catch (Exception) { return null; }
        }

        private ItemContainer ExportBackpack(BasePlayer player)
        {
            if (Backpacks == null) return null;
            try { return Backpacks.Call("API_GetExistingBackpack", (ulong)player.userID) as ItemContainer; } catch (Exception) { return null; }
        }

        private List<ItemRecord> SerializeContainer(ItemContainer container)
        {
            var items = new List<ItemRecord>();
            if (container == null || container.itemList == null) return items;
            // stable order by slot without LINQ (insertion sort: at most a few dozen items)
            var ordered = new List<Item>(container.itemList.Count);
            foreach (var item in container.itemList)
            {
                int j = ordered.Count;
                while (j > 0 && ordered[j - 1] != null && item != null && ordered[j - 1].position > item.position) j--;
                ordered.Insert(j, item);
            }
            foreach (var item in ordered)
            {
                if (item == null || item.info == null) continue;
                items.Add(SerializeItem(item));
            }
            return items;
        }

        private List<ItemRecord> SerializeContents(Item item)
        {
            var l = new List<ItemRecord>();
            var src = item.contents != null ? item.contents.itemList : null;
            if (src != null) foreach (var c in src) l.Add(SerializeItem(c));
            return l;
        }

        private ItemRecord SerializeItem(Item item)
        {
            var maxCond = item.maxCondition > 0 ? item.maxCondition : 1f;
            int ammo = 0;
            try { var proj = item.GetHeldEntity() as BaseProjectile; if (proj != null && proj.primaryMagazine != null) ammo = proj.primaryMagazine.contents; } catch (Exception) { }
            return new ItemRecord
            {
                Id = item.info.shortname,
                Name = item.name ?? item.info.shortname,
                Count = item.amount,
                Slot = item.position,
                Condition = item.condition,
                MaxCondition = item.maxCondition,
                DurabilityPercent = item.hasCondition ? Math.Round(item.condition / maxCond * 100, 1) : 100,
                Skin = item.skin,
                Ammo = ammo,
                Contents = SerializeContents(item),
            };
        }

        #region Export data classes

        private class ServerInfoRecord
        {
            [JsonProperty("hostname")] public string Hostname { get; set; }
            [JsonProperty("description")] public string Description { get; set; }
            [JsonProperty("max_players")] public int MaxPlayers { get; set; }
            [JsonProperty("map_name")] public string MapName { get; set; }
            [JsonProperty("world_size")] public int WorldSize { get; set; }
            [JsonProperty("seed")] public int Seed { get; set; }
            [JsonProperty("url")] public string Url { get; set; }
            [JsonProperty("header_image")] public string HeaderImage { get; set; }
        }

        private class Snapshot
        {
            [JsonProperty("generated_at")] public string GeneratedAt { get; set; }
            [JsonProperty("plugin_version")] public string PluginVersion { get; set; }
            [JsonProperty("server_info")] public ServerInfoRecord ServerInfo { get; set; }
            [JsonProperty("online_players")] public List<PlayerRecord> OnlinePlayers { get; set; }
            [JsonProperty("sleeping_players")] public List<PlayerRecord> SleepingPlayers { get; set; }
            [JsonProperty("all_players")] public List<PlayerRecord> AllPlayers { get; set; }
            [JsonProperty("banned_players")] public List<BannedRecord> BannedPlayers { get; set; }
            [JsonProperty("admins")] public List<RoleRecord> Admins { get; set; }
            [JsonProperty("moderators")] public List<RoleRecord> Moderators { get; set; }
            [JsonProperty("backpacks_plugin_detected")] public bool BackpacksPluginDetected { get; set; }
        }

        private class PlayerRecord
        {
            [JsonProperty("id")] public string Id { get; set; }
            [JsonProperty("name")] public string Name { get; set; }
            [JsonProperty("online")] public bool Online { get; set; }
            [JsonProperty("is_sleeping")] public bool IsSleeping { get; set; }
            [JsonProperty("is_alive")] public bool IsAlive { get; set; }
            [JsonProperty("is_wounded")] public bool IsWounded { get; set; }
            [JsonProperty("ping")] public int Ping { get; set; }
            [JsonProperty("address")] public string Address { get; set; }
            [JsonProperty("position")] public PositionRecord Position { get; set; }
            [JsonProperty("stats")] public PlayerStats Stats { get; set; }
            [JsonProperty("inventory")] public InventorySnapshot Inventory { get; set; }
            [JsonProperty("active_item")] public string ActiveItem { get; set; }
            [JsonProperty("team_id")] public string TeamId { get; set; }
            [JsonProperty("team_members")] public List<string> TeamMembers { get; set; }
            [JsonProperty("has_building_privilege")] public bool HasBuildingPrivilege { get; set; }
            [JsonProperty("blueprints_learned")] public int BlueprintsLearned { get; set; }
            [JsonProperty("is_banned")] public bool IsBanned { get; set; }
            [JsonProperty("is_admin")] public bool IsAdmin { get; set; }
            [JsonProperty("is_moderator")] public bool IsModerator { get; set; }
            [JsonProperty("connected_at")] public string ConnectedAt { get; set; }
        }

        private class BannedRecord
        {
            [JsonProperty("id")] public string Id { get; set; }
            [JsonProperty("name")] public string Name { get; set; }
            [JsonProperty("reason")] public string Reason { get; set; }
        }

        private class RoleRecord
        {
            [JsonProperty("id")] public string Id { get; set; }
            [JsonProperty("name")] public string Name { get; set; }
            [JsonProperty("role")] public string Role { get; set; }
        }

        private class PositionRecord
        {
            [JsonProperty("x")] public float X { get; set; }
            [JsonProperty("y")] public float Y { get; set; }
            [JsonProperty("z")] public float Z { get; set; }
        }

        private class PlayerStats
        {
            [JsonProperty("health")] public float Health { get; set; }
            [JsonProperty("max_health")] public float MaxHealth { get; set; }
            [JsonProperty("hydration")] public float Hydration { get; set; }
            [JsonProperty("calories")] public float Calories { get; set; }
            [JsonProperty("radiation")] public float Radiation { get; set; }
            [JsonProperty("radiation_poison")] public float RadiationPoison { get; set; }
            [JsonProperty("temperature")] public float Temperature { get; set; }
            [JsonProperty("comfort")] public float Comfort { get; set; }
            [JsonProperty("bleeding")] public float Bleeding { get; set; }
            [JsonProperty("oxygen")] public float Oxygen { get; set; }
            [JsonProperty("wetness")] public float Wetness { get; set; }
            [JsonProperty("play_time_seconds")] public int PlayTimeSeconds { get; set; }
        }

        private class InventorySnapshot
        {
            [JsonProperty("main")] public List<ItemRecord> Main { get; set; }
            [JsonProperty("belt")] public List<ItemRecord> Belt { get; set; }
            [JsonProperty("wear")] public List<ItemRecord> Wear { get; set; }
            [JsonProperty("backpack")] public List<ItemRecord> Backpack { get; set; }
        }

        private class ItemRecord
        {
            [JsonProperty("id")] public string Id { get; set; }
            [JsonProperty("name")] public string Name { get; set; }
            [JsonProperty("count")] public int Count { get; set; }
            [JsonProperty("slot")] public int Slot { get; set; }
            [JsonProperty("condition")] public float Condition { get; set; }
            [JsonProperty("max_condition")] public float MaxCondition { get; set; }
            [JsonProperty("durability_percent")] public double DurabilityPercent { get; set; }
            [JsonProperty("skin")] public ulong Skin { get; set; }
            [JsonProperty("ammo")] public int Ammo { get; set; }
            [JsonProperty("contents")] public List<ItemRecord> Contents { get; set; }
        }

        #endregion

        #endregion
    }
}
