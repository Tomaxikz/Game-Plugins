'use strict';
// Minimal MessagePack encoder/decoder (no dependencies).
// Float marker: wrap a number in Float to force float64 on the wire even when integral.

class Float {
  constructor(v) { this.value = Number(v); }
}

function f(v) { return new Float(v); }

// ---- encoder: one reusable growable buffer, no per-byte array pushes ---------------------------------------
// The scratch buffer is module-level and the encoder is not re-entrant (no callbacks run while encoding).
let buf = Buffer.allocUnsafe(64 * 1024);
let pos = 0;
const MAX_KEEP = 1024 * 1024; // do not hold on to a buffer larger than this between calls

function ensure(n) {
  if (pos + n <= buf.length) return;
  let size = buf.length * 2;
  while (size < pos + n) size *= 2;
  const nb = Buffer.allocUnsafe(size);
  buf.copy(nb, 0, 0, pos);
  buf = nb;
}

function wInt(n) {
  ensure(9);
  if (n >= 0) {
    if (n < 0x80) buf[pos++] = n;
    else if (n <= 0xff) { buf[pos++] = 0xcc; buf[pos++] = n; }
    else if (n <= 0xffff) { buf[pos++] = 0xcd; buf[pos++] = n >>> 8; buf[pos++] = n & 0xff; }
    else if (n <= 0xffffffff) { buf[pos++] = 0xce; buf.writeUInt32BE(n, pos); pos += 4; }
    else { buf[pos++] = 0xcf; buf.writeBigUInt64BE(BigInt(n), pos); pos += 8; }
  } else if (n >= -32) buf[pos++] = n & 0xff;
  else if (n >= -0x80) { buf[pos++] = 0xd0; buf[pos++] = n & 0xff; }
  else if (n >= -0x8000) { buf[pos++] = 0xd1; buf.writeInt16BE(n, pos); pos += 2; }
  else if (n >= -0x80000000) { buf[pos++] = 0xd2; buf.writeInt32BE(n, pos); pos += 4; }
  else { buf[pos++] = 0xd3; buf.writeBigInt64BE(BigInt(n), pos); pos += 8; }
}

function wFloat64(n) {
  ensure(9);
  buf[pos++] = 0xcb;
  buf.writeDoubleBE(n, pos);
  pos += 8;
}

function wStr(s) {
  const len = Buffer.byteLength(s, 'utf8');
  ensure(len + 5);
  if (len < 32) buf[pos++] = 0xa0 | len;
  else if (len <= 0xff) { buf[pos++] = 0xd9; buf[pos++] = len; }
  else if (len <= 0xffff) { buf[pos++] = 0xda; buf[pos++] = len >>> 8; buf[pos++] = len & 0xff; }
  else { buf[pos++] = 0xdb; buf.writeUInt32BE(len, pos); pos += 4; }
  if (len === s.length) { for (let i = 0; i < len; i++) buf[pos + i] = s.charCodeAt(i); } // ASCII fast path
  else buf.write(s, pos, len, 'utf8');
  pos += len;
}

function wBin(b) {
  const len = b.length;
  ensure(len + 5);
  if (len <= 0xff) { buf[pos++] = 0xc4; buf[pos++] = len; }
  else if (len <= 0xffff) { buf[pos++] = 0xc5; buf[pos++] = len >>> 8; buf[pos++] = len & 0xff; }
  else { buf[pos++] = 0xc6; buf.writeUInt32BE(len, pos); pos += 4; }
  buf.set(b, pos);
  pos += len;
}

function wArrayHeader(len) {
  ensure(5);
  if (len < 16) buf[pos++] = 0x90 | len;
  else if (len <= 0xffff) { buf[pos++] = 0xdc; buf[pos++] = len >>> 8; buf[pos++] = len & 0xff; }
  else { buf[pos++] = 0xdd; buf.writeUInt32BE(len, pos); pos += 4; }
}

function wMapHeader(len) {
  ensure(5);
  if (len < 16) buf[pos++] = 0x80 | len;
  else if (len <= 0xffff) { buf[pos++] = 0xde; buf[pos++] = len >>> 8; buf[pos++] = len & 0xff; }
  else { buf[pos++] = 0xdf; buf.writeUInt32BE(len, pos); pos += 4; }
}

const HIGH_UNITS = /[\ud800-￿]/; // UTF-16 code-unit order == UTF-8 byte order only below U+D800
function compareKeys(a, b) { return Buffer.compare(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8')); }
function sortUtf8(keys) {
  for (let i = 0; i < keys.length; i++) if (HIGH_UNITS.test(keys[i])) return keys.sort(compareKeys);
  return keys.sort();
}

function encodeValue(v, sortKeys) {
  if (v === null || v === undefined) { ensure(1); buf[pos++] = 0xc0; return; }
  switch (typeof v) {
    case 'boolean': ensure(1); buf[pos++] = v ? 0xc3 : 0xc2; return;
    case 'number':
      if (Number.isInteger(v)) wInt(v); else wFloat64(v);
      return;
    case 'bigint': wInt(Number(v)); return;
    case 'string': wStr(v); return;
    default: break;
  }
  if (v instanceof Float) { wFloat64(v.value); return; }
  if (v instanceof Uint8Array) { wBin(v); return; }
  if (Array.isArray(v)) {
    wArrayHeader(v.length);
    for (let i = 0; i < v.length; i++) encodeValue(v[i], sortKeys);
    return;
  }
  if (typeof v === 'object') {
    let keys = Object.keys(v);
    let n = 0;
    for (let i = 0; i < keys.length; i++) if (v[keys[i]] !== undefined) keys[n++] = keys[i];
    keys.length = n;
    if (sortKeys) keys = sortUtf8(keys);
    wMapHeader(n);
    for (let i = 0; i < n; i++) { const k = keys[i]; wStr(k); encodeValue(v[k], sortKeys); }
    return;
  }
  throw new TypeError('unsupported value type for msgpack: ' + typeof v);
}

// opts.sortKeys: emit map keys in UTF-8 byte order (canonical form). Returns a fresh Buffer (a copy of the scratch).
function encode(v, opts) {
  pos = 0;
  try {
    encodeValue(v, !!(opts && opts.sortKeys));
    const out = Buffer.allocUnsafe(pos);
    buf.copy(out, 0, 0, pos);
    return out;
  } finally {
    if (buf.length > MAX_KEEP) buf = Buffer.allocUnsafe(64 * 1024);
  }
}

// Same as encode() but returns a length-prefixed frame (4-byte big-endian length + body) in one allocation.
function encodeFramed(v, maxFrame) {
  pos = 4;
  try {
    encodeValue(v, false);
    const len = pos - 4;
    if (maxFrame !== undefined && len > maxFrame) throw Object.assign(new Error('frame too large'), { code: 'internal' });
    const out = Buffer.allocUnsafe(pos);
    buf.writeUInt32BE(len, 0);
    buf.copy(out, 0, 0, pos);
    return out;
  } finally {
    if (buf.length > MAX_KEEP) buf = Buffer.allocUnsafe(64 * 1024);
  }
}

// Decoder: returns the decoded value; throws on malformed or trailing bytes.
function decode(buf) {
  let pos = 0;
  const need = (n) => { if (pos + n > buf.length) throw new Error('msgpack: truncated'); };
  function read() {
    need(1);
    const t = buf[pos++];
    if (t <= 0x7f) return t;
    if (t >= 0xe0) return t - 0x100;
    if (t >= 0x80 && t <= 0x8f) return readMap(t & 0x0f);
    if (t >= 0x90 && t <= 0x9f) return readArr(t & 0x0f);
    if (t >= 0xa0 && t <= 0xbf) return readStr(t & 0x1f);
    switch (t) {
      case 0xc0: return null;
      case 0xc2: return false;
      case 0xc3: return true;
      case 0xc4: { need(1); const n = buf[pos++]; return readBin(n); }
      case 0xc5: { need(2); const n = buf.readUInt16BE(pos); pos += 2; return readBin(n); }
      case 0xc6: { need(4); const n = buf.readUInt32BE(pos); pos += 4; return readBin(n); }
      case 0xca: { need(4); const v = buf.readFloatBE(pos); pos += 4; return v; }
      case 0xcb: { need(8); const v = buf.readDoubleBE(pos); pos += 8; return v; }
      case 0xcc: need(1); return buf[pos++];
      case 0xcd: { need(2); const v = buf.readUInt16BE(pos); pos += 2; return v; }
      case 0xce: { need(4); const v = buf.readUInt32BE(pos); pos += 4; return v; }
      case 0xcf: { need(8); const v = buf.readBigUInt64BE(pos); pos += 8; return Number(v); }
      case 0xd0: need(1); return buf.readInt8(pos++);
      case 0xd1: { need(2); const v = buf.readInt16BE(pos); pos += 2; return v; }
      case 0xd2: { need(4); const v = buf.readInt32BE(pos); pos += 4; return v; }
      case 0xd3: { need(8); const v = buf.readBigInt64BE(pos); pos += 8; return Number(v); }
      case 0xd9: { need(1); const n = buf[pos++]; return readStr(n); }
      case 0xda: { need(2); const n = buf.readUInt16BE(pos); pos += 2; return readStr(n); }
      case 0xdb: { need(4); const n = buf.readUInt32BE(pos); pos += 4; return readStr(n); }
      case 0xdc: { need(2); const n = buf.readUInt16BE(pos); pos += 2; return readArr(n); }
      case 0xdd: { need(4); const n = buf.readUInt32BE(pos); pos += 4; return readArr(n); }
      case 0xde: { need(2); const n = buf.readUInt16BE(pos); pos += 2; return readMap(n); }
      case 0xdf: { need(4); const n = buf.readUInt32BE(pos); pos += 4; return readMap(n); }
      default: throw new Error('msgpack: unsupported type 0x' + t.toString(16));
    }
  }
  function readStr(n) { need(n); const s = buf.toString('utf8', pos, pos + n); pos += n; return s; }
  function readBin(n) { need(n); const b = Buffer.from(buf.subarray(pos, pos + n)); pos += n; return b; }
  function readArr(n) { const a = []; for (let i = 0; i < n; i++) a.push(read()); return a; }
  function readMap(n) {
    const o = {};
    for (let i = 0; i < n; i++) {
      const k = read();
      if (typeof k !== 'string') throw new Error('msgpack: non-string map key');
      o[k] = read();
    }
    return o;
  }
  const v = read();
  if (pos !== buf.length) throw new Error('msgpack: trailing bytes');
  return v;
}

module.exports = { encode, encodeFramed, decode, Float, f };
