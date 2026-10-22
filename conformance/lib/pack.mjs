// pack.manifest / pack.get (operations.md 10a, 10b; items-and-assets.md 11): the path rule, file and listing
// hashes, and the listing validator.

import { hash16, sha256Hex } from './canonical.mjs';
import { isObj, isStr, isIntV, isHex } from './check.mjs';
import { num } from './msgpack.mjs';
import { bytewise } from './volume.mjs';

export const PACK_MAX_PATHS = 64;
export const PACK_TOO_LARGE = 3 * 1024 * 1024; // pack.get: a single file above this never fits in one frame
export const PACK_MAX_PATH_BYTES = 512;
const SEGMENT_RE = /^[a-z0-9_.-]+$/;
const NAMESPACE_RE = /^[a-z0-9_.-]+$/;

/**
 * The 10a path rule. Returns null when `p` is a valid pack path, else the reason.
 * `assets/<namespace>/<kind>/<path>`, kind blockstates | models | textures, path = non-empty `/` segments of
 * [a-z0-9_.-] (no `.`/`..`), <= 512 bytes, ends in .json (blockstates, models) or .png / .png.mcmeta (textures).
 */
export function packPathError(p) {
  if (!isStr(p)) return 'not a string';
  if (Buffer.byteLength(p, 'utf8') > PACK_MAX_PATH_BYTES) return `longer than ${PACK_MAX_PATH_BYTES} bytes`;
  const parts = p.split('/');
  if (parts.length < 4) return 'not assets/<namespace>/<kind>/<path>';
  if (parts[0] !== 'assets') return 'does not start with assets/';
  const [, ns, kind, ...rest] = parts;
  // The contract does not exclude `.`/`..` as a namespace (it matches [a-z0-9_.-]+); the suite does, as a traversal risk.
  if (!NAMESPACE_RE.test(ns) || ns === '.' || ns === '..') return `namespace ${JSON.stringify(ns)} is not [a-z0-9_.-]+`;
  if (!['blockstates', 'models', 'textures'].includes(kind)) return `kind ${JSON.stringify(kind)} is not blockstates, models or textures`;
  for (const seg of rest) {
    if (seg === '' || !SEGMENT_RE.test(seg)) return `segment ${JSON.stringify(seg)} is empty or not [a-z0-9_.-]+`;
    if (seg === '.' || seg === '..') return `segment ${JSON.stringify(seg)} is not allowed`;
  }
  const last = rest[rest.length - 1];
  if (kind === 'textures') {
    if (!(p.endsWith('.png') || p.endsWith('.png.mcmeta'))) return 'textures must end in .png or .png.mcmeta';
  } else if (!p.endsWith('.json')) return `${kind} must end in .json`;
  if (last === undefined) return 'no file name';
  return null;
}

/** File hash: first 16 lowercase hex of SHA-256 over the bytes. */
export function fileHash(bytes) {
  return sha256Hex(bytes).slice(0, 16);
}

/** Listing hash: canonical MessagePack of the sorted [[path, hash], ...] list. */
export function listingHash(files) {
  return hash16(files.map((f) => [f.path, f.hash]));
}

/** Problems of the `files` entries of a listing (all pages joined): shape, path rule, sorted unique paths. */
export function manifestFileErrors(files) {
  if (!Array.isArray(files)) return ['files must be an array'];
  const e = [];
  const seen = new Set();
  let prev = null;
  files.forEach((f, i) => {
    const at = isObj(f) && isStr(f.path) ? f.path : `files[${i}]`;
    const p = (m) => e.push(`${at}: ${m}`);
    if (!isObj(f)) return p('not an object');
    const bad = packPathError(f.path);
    if (bad) p(`path breaks the 10a path rule (${bad})`);
    if (isStr(f.path)) {
      if (seen.has(f.path)) p('path appears twice');
      seen.add(f.path);
      if (prev !== null && bytewise(prev, f.path) >= 0) p(`not sorted by path (bytewise): ${prev} before ${f.path}`);
      prev = f.path;
    }
    if (!isHex(f.hash, 16)) p('hash must be 16 lowercase hex chars');
    if (!isIntV(f.size) || num(f.size) < 0) p(`size must be a non-negative int, got ${JSON.stringify(f.size)}`);
    for (const k of Object.keys(f)) if (!['path', 'hash', 'size'].includes(k)) p(`unexpected key ${k}`);
  });
  return e;
}
