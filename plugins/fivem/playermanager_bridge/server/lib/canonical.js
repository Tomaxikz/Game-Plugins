'use strict';
// Canonical MessagePack + render hash / fingerprint (docs/protocol/items-and-assets.md section 2-3).
const crypto = require('crypto');
const { encode, Float } = require('./msgpack');

// For hash/fingerprint input: integral numbers become ints, non-integral floats float64.
function canonicalize(v) {
  if (v instanceof Float) v = v.value;
  if (v === null || v === undefined) return undefined;
  if (Array.isArray(v)) return v.map((x) => { const c = canonicalize(x); return c === undefined ? null : c; });
  if (v instanceof Uint8Array) return v;
  if (typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) {
      const c = canonicalize(v[k]);
      if (c !== undefined) o[k] = c;
    }
    return o;
  }
  return v;
}

function canonicalBytes(v) {
  return encode(canonicalize(v), { sortKeys: true, canonical: true });
}

function renderHash(renderSubset) {
  return crypto.createHash('sha256').update(canonicalBytes(renderSubset)).digest('hex').slice(0, 16);
}

function fingerprint(fullRecord) {
  return crypto.createHash('sha256').update(canonicalBytes(fullRecord)).digest('hex');
}

module.exports = { canonicalBytes, renderHash, fingerprint, canonicalize };
