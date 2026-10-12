'use strict';
// Minimal MessagePack encoder/decoder (no dependencies).
// Float marker: wrap a number in Float to force float64 on the wire even when integral.

class Float {
  constructor(v) { this.value = Number(v); }
}

function f(v) { return new Float(v); }

function utf8Len(s) { return Buffer.byteLength(s, 'utf8'); }

function encodeInt(n, out) {
  if (n >= 0) {
    if (n < 0x80) out.push(n);
    else if (n <= 0xff) out.push(0xcc, n);
    else if (n <= 0xffff) { out.push(0xcd, (n >>> 8) & 0xff, n & 0xff); }
    else if (n <= 0xffffffff) { const b = Buffer.alloc(5); b[0] = 0xce; b.writeUInt32BE(n, 1); out.push(...b); }
    else { const b = Buffer.alloc(9); b[0] = 0xcf; b.writeBigUInt64BE(BigInt(n), 1); out.push(...b); }
  } else {
    if (n >= -32) out.push(n & 0xff);
    else if (n >= -0x80) out.push(0xd0, n & 0xff);
    else if (n >= -0x8000) { const b = Buffer.alloc(3); b[0] = 0xd1; b.writeInt16BE(n, 1); out.push(...b); }
    else if (n >= -0x80000000) { const b = Buffer.alloc(5); b[0] = 0xd2; b.writeInt32BE(n, 1); out.push(...b); }
    else { const b = Buffer.alloc(9); b[0] = 0xd3; b.writeBigInt64BE(BigInt(n), 1); out.push(...b); }
  }
}

function encodeFloat64(n, out) {
  const b = Buffer.alloc(9); b[0] = 0xcb; b.writeDoubleBE(n, 1); out.push(...b);
}

function encodeStr(s, out) {
  const len = utf8Len(s);
  if (len < 32) out.push(0xa0 | len);
  else if (len <= 0xff) out.push(0xd9, len);
  else if (len <= 0xffff) out.push(0xda, (len >>> 8) & 0xff, len & 0xff);
  else { const b = Buffer.alloc(5); b[0] = 0xdb; b.writeUInt32BE(len, 1); out.push(...b); }
  out.push(...Buffer.from(s, 'utf8'));
}

function encodeBin(buf, out) {
  const len = buf.length;
  if (len <= 0xff) out.push(0xc4, len);
  else if (len <= 0xffff) out.push(0xc5, (len >>> 8) & 0xff, len & 0xff);
  else { const b = Buffer.alloc(5); b[0] = 0xc6; b.writeUInt32BE(len, 1); out.push(...b); }
  for (const x of buf) out.push(x);
}

function encodeArrayHeader(len, out) {
  if (len < 16) out.push(0x90 | len);
  else if (len <= 0xffff) out.push(0xdc, (len >>> 8) & 0xff, len & 0xff);
  else { const b = Buffer.alloc(5); b[0] = 0xdd; b.writeUInt32BE(len, 1); out.push(...b); }
}

function encodeMapHeader(len, out) {
  if (len < 16) out.push(0x80 | len);
  else if (len <= 0xffff) out.push(0xde, (len >>> 8) & 0xff, len & 0xff);
  else { const b = Buffer.alloc(5); b[0] = 0xdf; b.writeUInt32BE(len, 1); out.push(...b); }
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v) && !(v instanceof Uint8Array) && !(v instanceof Float);
}

// Encode a value. opts.keySort: optional comparator for map keys (array of [key, value] -> sorted).
function encodeValue(v, out, opts) {
  if (v === null || v === undefined) { out.push(0xc0); return; }
  if (v === true) { out.push(0xc3); return; }
  if (v === false) { out.push(0xc2); return; }
  if (v instanceof Float) { encodeFloat64(v.value, out); return; }
  if (typeof v === 'number') {
    if (Number.isInteger(v)) {
      if (opts && opts.canonical) { encodeInt(v, out); return; }
      encodeInt(v, out); return;
    }
    if (opts && opts.canonical) { encodeFloat64(v, out); return; }
    encodeFloat64(v, out); return;
  }
  if (typeof v === 'bigint') { encodeInt(Number(v), out); return; }
  if (typeof v === 'string') { encodeStr(v, out); return; }
  if (v instanceof Uint8Array) { encodeBin(v, out); return; }
  if (Array.isArray(v)) {
    encodeArrayHeader(v.length, out);
    for (const x of v) encodeValue(x, out, opts);
    return;
  }
  if (isPlainObject(v)) {
    const keys = Object.keys(v).filter((k) => v[k] !== undefined);
    if (opts && opts.sortKeys) {
      keys.sort((a, b) => Buffer.compare(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8')));
    }
    encodeMapHeader(keys.length, out);
    for (const k of keys) { encodeStr(k, out); encodeValue(v[k], out, opts); }
    return;
  }
  throw new TypeError('unsupported value type for msgpack: ' + typeof v);
}

function encode(v, opts) {
  const out = [];
  encodeValue(v, out, opts || {});
  return Buffer.from(out);
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

module.exports = { encode, decode, Float, f };
