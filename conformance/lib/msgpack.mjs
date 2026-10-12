// Zero-dependency MessagePack encoder/decoder (subset used by the Player Manager bridge protocol).
//
// Encoding:
//   - nil, bool, all int widths (fixint, uint8-64, int8-64), float64, str8/16/32, bin8/16/32,
//     fixarray/array16/32, fixmap/map16/32. Ext types are not produced and not accepted.
//   - Non-integral numbers (and non-finite) are encoded as float64.
//   - Integers use the smallest encoding. Values beyond 2^53 may be given as BigInt.
//   - canonical mode (used for hashes): map keys sorted by UTF-8 byte order at every level,
//     undefined/null-valued map entries dropped, integral numbers encoded as ints, PmFloat
//     values treated as numbers.
//
// Decoding:
//   - bin -> Uint8Array (never Buffer).
//   - int and float are distinguishable: a float32/float64 whose value is integral is returned
//     as a PmFloat instance (valueOf() gives the number). Non-integral floats are plain numbers.
//     Use isInt()/isFloat()/num() from this module instead of typeof checks.
//   - maps decode to plain objects with string keys (defined via defineProperty, so "__proto__"
//     is an ordinary key).

export class PmFloat {
  constructor(value) {
    this.value = value;
  }
  valueOf() {
    return this.value;
  }
  toString() {
    return String(this.value);
  }
  toJSON() {
    return this.value;
  }
}

export function isInt(v) {
  return (typeof v === 'number' && Number.isInteger(v)) || typeof v === 'bigint';
}

export function isFloat(v) {
  return v instanceof PmFloat || (typeof v === 'number' && !Number.isInteger(v));
}

/** Plain JS number for a decoded value (PmFloat/BigInt-safe). */
export function num(v) {
  if (v instanceof PmFloat) return v.value;
  if (typeof v === 'bigint') return Number(v);
  return v;
}

const utf8Encoder = new TextEncoder();
const utf8Decoder = new TextDecoder('utf-8', { fatal: true });
const MAX_DEPTH = 512;

// ---------------------------------------------------------------- encoding

class Writer {
  constructor() {
    this.chunks = [];
    this.size = 0;
  }
  bytes(u8) {
    this.chunks.push(u8);
    this.size += u8.length;
  }
  byte(b) {
    const u = new Uint8Array(1);
    u[0] = b;
    this.bytes(u);
  }
  view(n, fn) {
    const dv = new DataView(new ArrayBuffer(n));
    fn(dv);
    this.bytes(new Uint8Array(dv.buffer));
  }
  finish() {
    const out = new Uint8Array(this.size);
    let off = 0;
    for (const c of this.chunks) {
      out.set(c, off);
      off += c.length;
    }
    return out;
  }
}

function writeInt(w, v) {
  // v: integer number (safe) or BigInt
  if (typeof v === 'bigint') {
    if (v >= 0n) {
      if (v <= 0xffffffffffffffffn) return w.view(9, (dv) => { dv.setUint8(0, 0xcf); dv.setBigUint64(1, v); });
      throw new RangeError('integer out of msgpack range');
    }
    if (v >= -0x8000000000000000n) return w.view(9, (dv) => { dv.setUint8(0, 0xd3); dv.setBigInt64(1, v); });
    throw new RangeError('integer out of msgpack range');
  }
  if (v >= 0) {
    if (v < 128) return w.byte(v);
    if (v <= 0xff) return w.view(2, (dv) => { dv.setUint8(0, 0xcc); dv.setUint8(1, v); });
    if (v <= 0xffff) return w.view(3, (dv) => { dv.setUint8(0, 0xcd); dv.setUint16(1, v); });
    if (v <= 0xffffffff) return w.view(5, (dv) => { dv.setUint8(0, 0xce); dv.setUint32(1, v); });
    if (Number.isSafeInteger(v)) return w.view(9, (dv) => { dv.setUint8(0, 0xcf); dv.setBigUint64(1, BigInt(v)); });
    return writeInt(w, BigInt(v));
  }
  if (v >= -32) return w.byte(v & 0xff);
  if (v >= -128) return w.view(2, (dv) => { dv.setUint8(0, 0xd0); dv.setInt8(1, v); });
  if (v >= -32768) return w.view(3, (dv) => { dv.setUint8(0, 0xd1); dv.setInt16(1, v); });
  if (v >= -2147483648) return w.view(5, (dv) => { dv.setUint8(0, 0xd2); dv.setInt32(1, v); });
  if (Number.isSafeInteger(v)) return w.view(9, (dv) => { dv.setUint8(0, 0xd3); dv.setBigInt64(1, BigInt(v)); });
  return writeInt(w, BigInt(v));
}

function writeFloat64(w, v) {
  w.view(9, (dv) => {
    dv.setUint8(0, 0xcb);
    dv.setFloat64(1, v);
  });
}

function writeStr(w, s) {
  const b = utf8Encoder.encode(s);
  const n = b.length;
  if (n < 32) w.byte(0xa0 | n);
  else if (n <= 0xff) w.view(2, (dv) => { dv.setUint8(0, 0xd9); dv.setUint8(1, n); });
  else if (n <= 0xffff) w.view(3, (dv) => { dv.setUint8(0, 0xda); dv.setUint16(1, n); });
  else w.view(5, (dv) => { dv.setUint8(0, 0xdb); dv.setUint32(1, n); });
  w.bytes(b);
}

function writeBin(w, u8) {
  const n = u8.length;
  if (n <= 0xff) w.view(2, (dv) => { dv.setUint8(0, 0xc4); dv.setUint8(1, n); });
  else if (n <= 0xffff) w.view(3, (dv) => { dv.setUint8(0, 0xc5); dv.setUint16(1, n); });
  else w.view(5, (dv) => { dv.setUint8(0, 0xc6); dv.setUint32(1, n); });
  w.bytes(u8 instanceof Uint8Array ? u8 : new Uint8Array(u8));
}

function writeArrayHeader(w, n) {
  if (n < 16) w.byte(0x90 | n);
  else if (n <= 0xffff) w.view(3, (dv) => { dv.setUint8(0, 0xdc); dv.setUint16(1, n); });
  else w.view(5, (dv) => { dv.setUint8(0, 0xdd); dv.setUint32(1, n); });
}

function writeMapHeader(w, n) {
  if (n < 16) w.byte(0x80 | n);
  else if (n <= 0xffff) w.view(3, (dv) => { dv.setUint8(0, 0xde); dv.setUint16(1, n); });
  else w.view(5, (dv) => { dv.setUint8(0, 0xdf); dv.setUint32(1, n); });
}

function writeNumber(w, v, canonical) {
  if (v instanceof PmFloat) {
    if (canonical) return writeNumber(w, v.value, true);
    return writeFloat64(w, v.value);
  }
  if (typeof v === 'bigint') return writeInt(w, v);
  if (Number.isInteger(v)) return writeInt(w, v);
  return writeFloat64(w, v);
}

function utf8Compare(a, b) {
  const x = utf8Encoder.encode(a);
  const y = utf8Encoder.encode(b);
  const n = Math.min(x.length, y.length);
  for (let i = 0; i < n; i++) {
    if (x[i] !== y[i]) return x[i] - y[i];
  }
  return x.length - y.length;
}

function encodeValue(w, v, canonical, depth) {
  if (depth > MAX_DEPTH) throw new RangeError('msgpack encode: depth limit');
  if (v === null || v === undefined) return w.byte(0xc0);
  switch (typeof v) {
    case 'boolean':
      return w.byte(v ? 0xc3 : 0xc2);
    case 'number':
    case 'bigint':
      return writeNumber(w, v, canonical);
    case 'string':
      return writeStr(w, v);
    case 'object':
      break;
    default:
      throw new TypeError(`msgpack encode: unsupported type ${typeof v}`);
  }
  if (v instanceof PmFloat) return writeNumber(w, v, canonical);
  if (v instanceof Uint8Array) return writeBin(w, v);
  if (Array.isArray(v)) {
    writeArrayHeader(w, v.length);
    for (const item of v) encodeValue(w, item, canonical, depth + 1);
    return undefined;
  }
  if (v instanceof Map) {
    return encodeEntries(w, [...v.entries()], canonical, depth);
  }
  return encodeEntries(w, Object.entries(v), canonical, depth);
}

function encodeEntries(w, entries, canonical, depth) {
  let list = entries;
  if (canonical) {
    list = entries.filter(([, val]) => val !== null && val !== undefined);
    list.sort((a, b) => utf8Compare(String(a[0]), String(b[0])));
  }
  writeMapHeader(w, list.length);
  for (const [k, val] of list) {
    writeStr(w, String(k));
    encodeValue(w, val, canonical, depth + 1);
  }
  return undefined;
}

/**
 * Encode a JS value to MessagePack bytes.
 * @param {unknown} value
 * @param {{canonical?: boolean}} [opts]
 * @returns {Uint8Array}
 */
export function encode(value, opts = {}) {
  const w = new Writer();
  encodeValue(w, value, Boolean(opts.canonical), 0);
  return w.finish();
}

// ---------------------------------------------------------------- decoding

class Reader {
  constructor(buf) {
    this.buf = buf;
    this.dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    this.pos = 0;
  }
  need(n) {
    if (this.pos + n > this.buf.length) throw new RangeError('msgpack decode: truncated input');
  }
  u8() {
    this.need(1);
    return this.buf[this.pos++];
  }
  take(n) {
    this.need(n);
    const out = this.buf.subarray(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }
  read(size, getter) {
    this.need(size);
    const v = getter(this.dv, this.pos);
    this.pos += size;
    return v;
  }
}

function decodeStr(r, n) {
  const bytes = r.take(n);
  try {
    return utf8Decoder.decode(bytes);
  } catch {
    throw new TypeError('msgpack decode: invalid UTF-8 in str');
  }
}

function decodeIntU64(v) {
  return v <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(v) : v;
}

function decodeIntI64(v) {
  return v >= BigInt(Number.MIN_SAFE_INTEGER) && v <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(v) : v;
}

function decodeFloat(v) {
  return Number.isInteger(v) ? new PmFloat(v) : v;
}

function decodeMapEntries(r, n, depth) {
  const out = {};
  for (let i = 0; i < n; i++) {
    const k = decodeValue(r, depth + 1);
    if (typeof k !== 'string') {
      throw new TypeError(`msgpack decode: non-string map key (${typeof k})`);
    }
    const val = decodeValue(r, depth + 1);
    Object.defineProperty(out, k, { value: val, enumerable: true, writable: true, configurable: true });
  }
  return out;
}

function decodeArray(r, n, depth) {
  const out = new Array(n);
  for (let i = 0; i < n; i++) out[i] = decodeValue(r, depth + 1);
  return out;
}

function decodeValue(r, depth) {
  if (depth > MAX_DEPTH) throw new RangeError('msgpack decode: depth limit');
  const t = r.u8();
  if (t <= 0x7f) return t;
  if (t >= 0xe0) return t - 0x100;
  if (t >= 0x80 && t <= 0x8f) return decodeMapEntries(r, t & 0x0f, depth);
  if (t >= 0x90 && t <= 0x9f) return decodeArray(r, t & 0x0f, depth);
  if (t >= 0xa0 && t <= 0xbf) return decodeStr(r, t & 0x1f);
  switch (t) {
    case 0xc0: return null;
    case 0xc2: return false;
    case 0xc3: return true;
    case 0xc4: return new Uint8Array(r.take(r.u8()));
    case 0xc5: return new Uint8Array(r.take(r.read(2, (dv, p) => dv.getUint16(p))));
    case 0xc6: return new Uint8Array(r.take(r.read(4, (dv, p) => dv.getUint32(p))));
    case 0xca: return decodeFloat(r.read(4, (dv, p) => dv.getFloat32(p)));
    case 0xcb: return decodeFloat(r.read(8, (dv, p) => dv.getFloat64(p)));
    case 0xcc: return r.u8();
    case 0xcd: return r.read(2, (dv, p) => dv.getUint16(p));
    case 0xce: return r.read(4, (dv, p) => dv.getUint32(p));
    case 0xcf: return decodeIntU64(r.read(8, (dv, p) => dv.getBigUint64(p)));
    case 0xd0: return r.read(1, (dv, p) => dv.getInt8(p));
    case 0xd1: return r.read(2, (dv, p) => dv.getInt16(p));
    case 0xd2: return r.read(4, (dv, p) => dv.getInt32(p));
    case 0xd3: return decodeIntI64(r.read(8, (dv, p) => dv.getBigInt64(p)));
    case 0xd9: return decodeStr(r, r.u8());
    case 0xda: return decodeStr(r, r.read(2, (dv, p) => dv.getUint16(p)));
    case 0xdb: return decodeStr(r, r.read(4, (dv, p) => dv.getUint32(p)));
    case 0xdc: return decodeArray(r, r.read(2, (dv, p) => dv.getUint16(p)), depth);
    case 0xdd: return decodeArray(r, r.read(4, (dv, p) => dv.getUint32(p)), depth);
    case 0xde: return decodeMapEntries(r, r.read(2, (dv, p) => dv.getUint16(p)), depth);
    case 0xdf: return decodeMapEntries(r, r.read(4, (dv, p) => dv.getUint32(p)), depth);
    default:
      throw new TypeError(`msgpack decode: unsupported type byte 0x${t.toString(16).padStart(2, '0')}`);
  }
}

/**
 * Decode one MessagePack value. Throws if bytes remain after the value (strict mode).
 * @param {Uint8Array} bytes
 * @returns {unknown}
 */
export function decode(bytes) {
  const buf = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const r = new Reader(buf);
  const v = decodeValue(r, 0);
  if (r.pos !== buf.length) throw new RangeError(`msgpack decode: ${buf.length - r.pos} trailing bytes`);
  return v;
}

export function toHex(bytes) {
  return Buffer.from(bytes).toString('hex');
}
