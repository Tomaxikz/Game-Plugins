// Chunked assets.get answers (items-and-assets.md section 6, NOTES.md D29/D30, bridge NOTES "Large layered assets are
// chunked"): reassembly of slices and the bookkeeping checks.
//
// A slice is an `assets` entry with `chunk: {index, total, layer?}`, `hash` (the hash of the WHOLE asset, on every
// slice) and `textures` holding one texture: `{icon: {png: <part>, png_offset, png_total, width, ...}}`,
// `{layers: [<layer>]}` with `chunk.layer` = index into the real layers array, and for an emissive overlay
// `{icon: {emissive: {png: <part>, png_offset, png_total, ...}}}` (resp. `{layers: [{emissive: ...}]}`).

import { isObj, isIntV, isBin, isHex } from './check.mjs';
import { num } from './msgpack.mjs';
import { assetHash } from './canonical.mjs';

const SLICE_ONLY_KEYS = ['png', 'png_offset', 'png_total'];

function concat(parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

/** Joins the `png` parts of one texture (base or overlay) in slice order; checks the offsets. Returns { png, meta }. */
function joinParts(desc, slices, problems) {
  let off = 0;
  let total = null;
  const parts = [];
  for (const s of slices) {
    const o = s.obj;
    let po = o.png_offset;
    let pt = o.png_total;
    if (po === undefined && pt === undefined) {
      if (slices.length > 1) problems.push(`${desc}: slice ${s.index} has no png_offset/png_total although the texture is sent in ${slices.length} slices`);
      po = 0;
      pt = o.png.length;
    } else if (!isIntV(po) || !isIntV(pt) || num(po) < 0 || num(pt) < 0) {
      problems.push(`${desc}: slice ${s.index} needs png_offset and png_total as non-negative ints, got ${JSON.stringify(po)} / ${JSON.stringify(pt)}`);
      continue;
    }
    po = num(po);
    pt = num(pt);
    if (po !== off) problems.push(`${desc}: slice ${s.index} has png_offset ${po}, expected ${off} (offsets must be contiguous from 0)`);
    if (total !== null && pt !== total) problems.push(`${desc}: slice ${s.index} has png_total ${pt}, the first slice said ${total}`);
    total ??= pt;
    off += o.png.length;
    parts.push(o.png);
  }
  if (total !== null && off !== total) problems.push(`${desc}: the slices add up to ${off} bytes, png_total is ${total}`);
  const first = slices[0].obj;
  const meta = {};
  for (const [k, v] of Object.entries(first)) if (!SLICE_ONLY_KEYS.includes(k)) meta[k] = v;
  return { png: concat(parts), meta };
}

/** Reassembles the slices (all with a `chunk`) of one key. Returns the whole asset, or null (problems recorded). */
function assembleKey(key, list, problems) {
  const p = (m) => problems.push(`${key}: ${m}`);
  const first = list[0];
  const total = isObj(first.chunk) ? num(first.chunk.total) : null;
  let bad = false;
  for (const e of list) {
    const c = e.chunk;
    if (!isObj(c) || !isIntV(c.index) || !isIntV(c.total) || num(c.total) < 1 || num(c.index) < 0) {
      p(`chunk must be {index, total} with ints, got ${JSON.stringify(c)}`);
      bad = true;
    } else if (num(c.total) !== total) {
      p(`chunk.total ${num(c.total)} differs from ${total} on another slice`);
      bad = true;
    }
    if (!isHex(e.hash, 16)) {
      p('every slice needs the 16-hex hash of the whole asset');
      bad = true;
    } else if (e.hash !== first.hash) {
      p(`hash differs between slices (${first.hash} vs ${e.hash}): it is the hash of the whole asset on every slice`);
      bad = true;
    }
    if (e.render !== first.render) {
      p(`render differs between slices (${first.render} vs ${e.render})`);
      bad = true;
    }
  }
  if (bad) return null;
  const sorted = [...list].sort((a, b) => num(a.chunk.index) - num(b.chunk.index));
  const idx = sorted.map((e) => num(e.chunk.index));
  if (idx.length !== total || idx.some((v, i) => v !== i)) {
    p(`chunk indices must be 0..${total - 1} once each, got [${idx.join(', ')}]`);
    return null;
  }

  // Sort the slices into texture parts: (key, layer, base | overlay).
  const before = problems.length;
  const groups = new Map();
  const note = (tkey, layer, kind, obj, index) => {
    const id = `${tkey}|${layer ?? ''}|${kind}`;
    if (!groups.has(id)) groups.set(id, { tkey, layer, kind, slices: [] });
    groups.get(id).slices.push({ obj, index });
  };
  const addTexture = (tkey, layer, t, index) => {
    if (!isObj(t)) return p(`slice ${index}: texture ${tkey} is not an object`);
    let any = false;
    if (isBin(t.png)) {
      note(tkey, layer, 'base', t, index);
      any = true;
    }
    if (isObj(t.emissive)) {
      if (!isBin(t.emissive.png)) p(`slice ${index}: emissive overlay of ${tkey} has no png`);
      else note(tkey, layer, 'overlay', t.emissive, index);
      any = true;
    }
    if (!any) p(`slice ${index}: texture ${tkey} carries neither png nor an emissive overlay`);
  };
  for (const e of sorted) {
    const index = num(e.chunk.index);
    if (!isObj(e.textures) || Object.keys(e.textures).length === 0) {
      p(`slice ${index} has no textures`);
      continue;
    }
    for (const [tkey, t] of Object.entries(e.textures)) {
      if (tkey === 'layers') {
        if (!Array.isArray(t)) {
          p(`slice ${index}: layers must be an array`);
          continue;
        }
        if (!isIntV(e.chunk.layer) || num(e.chunk.layer) < 0) {
          p(`slice ${index}: a slice with layers needs chunk.layer (its index in the real layers array)`);
          continue;
        }
        t.forEach((lt, j) => addTexture('layers', num(e.chunk.layer) + j, lt, index));
      } else {
        if (e.chunk.layer !== undefined) p(`slice ${index}: chunk.layer on a slice without layers`);
        addTexture(tkey, null, t, index);
      }
    }
  }

  // Slices of one part are consecutive; a texture's base slices come before its overlay slices.
  for (const g of groups.values()) {
    const ix = g.slices.map((s) => s.index);
    for (let i = 1; i < ix.length; i++) if (ix[i] !== ix[i - 1] + 1) p(`slices of ${g.tkey}${g.layer === null ? '' : `[${g.layer}]`} (${g.kind}) are not consecutive (${ix.join(', ')})`);
    if (g.kind === 'overlay') {
      const base = groups.get(`${g.tkey}|${g.layer ?? ''}|base`);
      if (base && Math.max(...base.slices.map((s) => s.index)) > Math.min(...ix)) p(`overlay slices of ${g.tkey}${g.layer === null ? '' : `[${g.layer}]`} come before its base slices`);
    }
  }
  const textures = {};
  const layers = [];
  for (const g of groups.values()) {
    const name = `${key} ${g.tkey}${g.layer === null ? '' : `[${g.layer}]`} ${g.kind}`;
    if (g.kind === 'overlay' && !groups.has(`${g.tkey}|${g.layer ?? ''}|base`)) p(`${name}: overlay without base texture slices`);
    const { png, meta } = joinParts(name, g.slices, problems);
    if (g.kind === 'base') {
      const t = { ...meta, png };
      if (isObj(t.emissive)) delete t.emissive;
      if (g.layer === null) textures[g.tkey] = { ...(textures[g.tkey] ?? {}), ...t };
      else layers[g.layer] = { ...(layers[g.layer] ?? {}), ...t };
    } else {
      const holder = g.layer === null ? (textures[g.tkey] ??= {}) : (layers[g.layer] ??= {});
      holder.emissive = { ...meta, png };
    }
  }
  if (layers.length > 0) {
    for (let i = 0; i < layers.length; i++) if (layers[i] === undefined) p(`layers[${i}] has no slices (chunk.layer must index the real layers array without gaps)`);
    textures.layers = layers;
  }
  if (problems.length > before) return null;

  const whole = {};
  for (const [k, v] of Object.entries(sorted[0])) if (k !== 'chunk' && k !== 'textures') whole[k] = v;
  whole.textures = textures;
  const h = assetHash(whole);
  if (h !== first.hash) {
    p(`hash ${first.hash} != recomputed ${h} over the reassembled asset (${total} slices)`);
    return null;
  }
  return whole;
}

/**
 * Takes the `assets` entries of all pages of one request and returns { assets, problems, sliced, slices }:
 * `assets` has one entry per key in order of first appearance (a reassembled whole asset for sliced keys, the entry
 * itself otherwise), `sliced` the number of reassembled assets, `slices` the number of slices joined.
 * `problems` lists bookkeeping errors; a key that could not be joined appears as { key, hash, render, broken: true }.
 */
export function assembleAssets(entries) {
  const problems = [];
  const order = [];
  const byKey = new Map();
  entries.forEach((e, i) => {
    if (!isObj(e) || typeof e.key !== 'string') {
      problems.push(`entry ${i} has no key`);
      return;
    }
    if (!byKey.has(e.key)) {
      byKey.set(e.key, []);
      order.push(e.key);
    }
    byKey.get(e.key).push(e);
  });
  const assets = [];
  let sliced = 0;
  let slices = 0;
  for (const key of order) {
    const list = byKey.get(key);
    const withChunk = list.filter((e) => e.chunk !== undefined);
    if (withChunk.length === 0) {
      if (list.length > 1) problems.push(`${key}: answered ${list.length} times without chunk`);
      assets.push(list[0]);
      continue;
    }
    if (withChunk.length !== list.length) problems.push(`${key}: sliced and whole entries mixed`);
    const whole = assembleKey(key, withChunk, problems);
    slices += withChunk.length;
    if (whole) {
      sliced++;
      assets.push({ ...whole, key });
    } else {
      assets.push({ key, hash: withChunk[0].hash, render: withChunk[0].render, broken: true });
    }
  }
  return { assets, problems, sliced, slices };
}
