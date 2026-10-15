// Canonical MessagePack, render hash, fingerprint, manifest hash and asset hash, per
// docs/protocol/items-and-assets.md sections 2, 3, 5 and 6.

import { createHash } from 'node:crypto';
import { encode } from './msgpack.mjs';

// items-and-assets.md section 2 test vector. The contract lists the bytes as
// `81 a2 69 64 ae 6d ... 65` with hash 027f2bef638386bd. The fixstr length byte for
// "minecraft:stone" (15 bytes) must be 0xaf (0xae would be 14 bytes), so the documented hex is not
// valid MessagePack. sha256 of the documented hex bytes is 027f2bef638386bd; sha256 of the
// correctly encoded bytes is 90d892ebcdb67d59. Both are kept here; see conformance/README.md.
export const TEST_VECTOR = {
  input: { id: 'minecraft:stone' },
  bytes: '81a26964af6d696e6563726166743a73746f6e65',
  hash: '90d892ebcdb67d59',
  documentedBytes: '81a26964ae6d696e6563726166743a73746f6e65',
  documentedHash: '027f2bef638386bd',
};

/** Canonical MessagePack bytes (sorted keys, no nil map values, integral floats as ints). */
export function canonicalBytes(value) {
  return encode(value, { canonical: true });
}

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest();
}

export function sha256Hex(bytes) {
  return sha256(bytes).toString('hex');
}

/** First 16 lowercase hex chars of SHA-256 over canonical bytes of `value`. */
export function hash16(value) {
  return sha256Hex(canonicalBytes(value)).slice(0, 16);
}

/** Render hash of a render subset map (items-and-assets.md section 2, step 3). */
export function renderHash(subset) {
  return hash16(subset);
}

// Render-relevant Minecraft components (section 2 table). Any of these present in an item
// makes its render subset unverifiable from the record alone (only the format is checked).
export const MC_RENDER_COMPONENTS = [
  'minecraft:profile',
  'minecraft:dyed_color',
  'minecraft:custom_model_data',
  'minecraft:item_model',
  'minecraft:trim',
  'minecraft:banner_patterns',
  'minecraft:base_color',
  'minecraft:potion_contents',
  'minecraft:map_color',
  'minecraft:firework_explosion',
  'minecraft:fireworks',
  'minecraft:instrument',
  'minecraft:charged_projectiles',
  'minecraft:bundle_contents',
  'minecraft:container',
  'minecraft:enchantment_glint_override',
  'minecraft:pot_decorations',
  'minecraft:painting/variant',
];

// Components whose render value is the list of nested item hashes (items-and-assets.md section 2).
const NESTED_HASH_COMPONENTS = ['minecraft:bundle_contents', 'minecraft:container'];

/**
 * Build the render subset of an encoded item record (items-and-assets.md section 2, step 1).
 * render_components is taken from record.components (MC 1.20.5+). Nested-hash components
 * (bundle_contents, container) use the `hash` of each record in `contents`.
 * Legacy `components.snbt` (pre-1.20.5) keeps its render tags inside a string, so the render hash
 * is not recomputable from the record: returned as verifiable: false (format only).
 * Returns { verifiable: true, subset } or { verifiable: false, reason }.
 */
export function renderSubsetOf(rec) {
  const comps = rec.components;
  if (comps && typeof comps === 'object' && !Array.isArray(comps) && 'snbt' in comps) {
    return { verifiable: false, reason: 'legacy snbt components' };
  }
  const subset = { id: rec.id };
  if (typeof rec.model === 'string' && rec.model !== '') subset.model = rec.model;
  if (Array.isArray(rec.tint) && rec.tint.length > 0) subset.tint = rec.tint;
  if (typeof rec.skin === 'number' && rec.skin !== 0) subset.skin = rec.skin;
  const renderComps = {};
  if (comps && typeof comps === 'object' && !Array.isArray(comps)) {
    for (const k of MC_RENDER_COMPONENTS) {
      if (!(k in comps)) continue;
      if (NESTED_HASH_COMPONENTS.includes(k) && Array.isArray(rec.contents)) {
        renderComps[k] = rec.contents.map((c) => c.hash);
      } else {
        renderComps[k] = comps[k];
      }
    }
  }
  if (Object.keys(renderComps).length > 0) subset.render_components = renderComps;
  const override = comps && typeof comps === 'object' ? comps['minecraft:enchantment_glint_override'] : undefined;
  if (typeof override === 'boolean') {
    if (override) subset.glint = true;
  } else if (Array.isArray(rec.enchantments) && rec.enchantments.length > 0) {
    subset.glint = true;
  }
  if (rec.variant !== undefined && rec.variant !== null) subset.variant = rec.variant;
  return { verifiable: true, subset };
}

function stripForFingerprint(rec) {
  const out = {};
  for (const [k, v] of Object.entries(rec)) {
    if (k === 'slot' || k === 'hash' || k === 'fingerprint' || k === 'truncated') continue;
    if (k === 'contents' && Array.isArray(v)) {
      out[k] = v.map((c) => (c && typeof c === 'object' ? stripForFingerprint(c) : c));
    } else {
      out[k] = v;
    }
  }
  return out;
}

/** Full 64-hex fingerprint of an item record as the bridge reports it (section 3). */
export function fingerprintOf(rec) {
  return sha256Hex(canonicalBytes(stripForFingerprint(rec)));
}

/** True when the record or any nested record is truncated (its fingerprint is not recomputable). */
export function subtreeTruncated(rec) {
  if (!rec || typeof rec !== 'object') return false;
  if (rec.truncated === true) return true;
  if (Array.isArray(rec.contents)) return rec.contents.some(subtreeTruncated);
  return false;
}

/** manifest_hash over the full sorted [key, hash] list (section 5). */
export function manifestHash(entries) {
  const pairs = entries.map((e) => [e.key, e.hash]);
  return hash16(pairs);
}

const TEXTURE_META_KEYS = ['width', 'height', 'frames', 'frametime', 'interpolate', 'frame_order', 'frame_times', 'tint_index'];

/** Hash form of one texture object (NOTES.md D29): png replaced by its SHA-256; emissive recursive. Null if malformed. */
function textureForm(t) {
  if (!t || typeof t !== 'object' || !(t.png instanceof Uint8Array)) return null;
  const meta = { sha: sha256Hex(t.png) };
  for (const mk of TEXTURE_META_KEYS) {
    if (t[mk] !== undefined) meta[mk] = t[mk];
  }
  if (t.emissive === true) meta.emissive = true;
  else if (t.emissive !== undefined) {
    const e = textureForm(t.emissive);
    if (e === null) return null;
    meta.emissive = e;
  }
  return meta;
}

/**
 * Asset hash from an assets.get result entry (section 6, extended by NOTES.md D29/D30 for layers, emissive, geo,
 * display, approximate and vanilla_textures). Only the SHA-256 of each png is hashed. Returns null for render "unavailable".
 */
export function assetHash(asset) {
  if (asset.render === 'unavailable') return null;
  const obj = { render: asset.render };
  for (const k of ['model', 'special', 'tints', 'geo', 'display', 'approximate', 'vanilla_textures']) {
    if (asset[k] !== undefined) obj[k] = asset[k];
  }
  const textures = {};
  for (const [k, t] of Object.entries(asset.textures ?? {})) {
    if (k === 'layers' && Array.isArray(t)) {
      const layers = [];
      for (const l of t) {
        const f = textureForm(l);
        if (f === null) return null;
        layers.push(f);
      }
      textures.layers = layers;
      continue;
    }
    const f = textureForm(t);
    if (f === null) return null;
    textures[k] = f;
  }
  obj.textures = textures;
  return hash16(obj);
}
