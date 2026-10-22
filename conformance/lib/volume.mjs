// map.volume (operations.md 6c, games/minecraft.md 6b): ChunkVolume validator, palette state rules, and the
// helpers the band cross-check against map.chunks uses.

import { isObj, isStr, isBin, isIntV } from './check.mjs';
import { num } from './msgpack.mjs';
import { unpackU16LE, unpackI16LE, WATER_IDS } from './chunks.mjs';

export const VOLUME_AIR_IDS = ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'];
export const AIR_INDEX = 0xffff;
export const MAX_VOLUME_CHUNKS = 64;
export const MAX_VOLUME_HEIGHT = 160;
export const MAX_PALETTE = 65535;

const ID_RE = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const PROP_KEY_RE = /^[a-z0-9_]+$/;
const PROP_VALUE_BAD = /[\s,=[\]]/;
const VOLUME_KEYS = ['cx', 'cz', 'y0', 'height', 'palette', 'blocks', 'biome_ids', 'biome'];

export function bytewise(a, b) {
  return Buffer.compare(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

/** `id` or `id[key=value,...]` with keys sorted bytewise. `props` is an object or a list of [key, value]. */
export function normaliseState(id, props) {
  const pairs = Array.isArray(props) ? [...props] : Object.entries(props ?? {});
  if (pairs.length === 0) return id;
  pairs.sort((x, y) => bytewise(x[0], y[0]));
  return `${id}[${pairs.map(([k, v]) => `${k}=${v}`).join(',')}]`;
}

/** Splits a palette string. Returns { id, props: [[k, v], ...] } (props empty without brackets). */
export function splitState(s) {
  const b = s.indexOf('[');
  if (b < 0) return { id: s, props: [] };
  const inner = s.endsWith(']') ? s.slice(b + 1, -1) : s.slice(b + 1);
  return { id: s.slice(0, b), props: inner === '' ? [] : inner.split(',').map((p) => [p.slice(0, Math.max(p.indexOf('='), 0)), p.slice(p.indexOf('=') + 1)]) };
}

/** Problems of one palette string: id shape, bracket form, property pairs sorted bytewise and unique. */
export function stateErrors(s) {
  if (!isStr(s)) return ['entry is not a string'];
  const e = [];
  const q = JSON.stringify(s);
  const b = s.indexOf('[');
  const id = b < 0 ? s : s.slice(0, b);
  if (!ID_RE.test(id)) e.push(`${q}: id is not a namespaced id`);
  if (b < 0) return e;
  if (!s.endsWith(']')) return [...e, `${q}: bracket part must end with ]`];
  const inner = s.slice(b + 1, -1);
  if (inner === '') return [...e, `${q}: empty property list (a state without properties is the bare id)`];
  let prev = null;
  for (const pair of inner.split(',')) {
    const i = pair.indexOf('=');
    if (i <= 0 || i === pair.length - 1) {
      e.push(`${q}: ${JSON.stringify(pair)} is not key=value`);
      continue;
    }
    const k = pair.slice(0, i);
    const v = pair.slice(i + 1);
    if (!PROP_KEY_RE.test(k)) e.push(`${q}: property key ${JSON.stringify(k)} is not [a-z0-9_]+`);
    if (PROP_VALUE_BAD.test(v)) e.push(`${q}: property value ${JSON.stringify(v)} contains a separator or whitespace`);
    if (prev !== null) {
      const c = bytewise(prev, k);
      if (c === 0) e.push(`${q}: property ${k} twice`);
      else if (c > 0) e.push(`${q}: properties not sorted by key (bytewise): ${prev} before ${k}`);
    }
    prev = k;
  }
  return e;
}

/** Palette base id (without the bracket part). */
export const stateId = (s) => (s.includes('[') ? s.slice(0, s.indexOf('[')) : s);

/** Reads a u16 LE bin into a Uint16Array (copy; the bin may sit at an odd offset). */
export function u16Array(bin) {
  const out = new Uint16Array(bin.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = bin[2 * i] | (bin[2 * i + 1] << 8);
  return out;
}

/**
 * Validates one ChunkVolume (shape only, no cross-chunk rules). Returns a list of problems.
 */
export function volumeChunkErrors(c) {
  const e = [];
  if (!isObj(c)) return ['chunk is not an object'];
  const at = isIntV(c.cx) && isIntV(c.cz) ? `chunk [${num(c.cx)},${num(c.cz)}]` : 'chunk';
  const p = (m) => e.push(`${at}: ${m}`);
  for (const k of ['cx', 'cz', 'y0', 'height']) if (!isIntV(c[k])) p(`${k} must be an int`);
  for (const k of Object.keys(c)) if (!VOLUME_KEYS.includes(k)) p(`unexpected key ${k}`);
  const height = isIntV(c.height) ? num(c.height) : null;
  if (height !== null && (height < 0 || height > MAX_VOLUME_HEIGHT)) p(`height ${height} outside 0..${MAX_VOLUME_HEIGHT}`);

  const strList = (name) => {
    const v = c[name];
    if (!Array.isArray(v) || !v.every(isStr)) {
      p(`${name} must be string[]`);
      return null;
    }
    return v;
  };
  const palette = strList('palette');
  const biomeIds = strList('biome_ids');
  if (palette) {
    if (palette.length > MAX_PALETTE) p(`palette has ${palette.length} entries (max ${MAX_PALETTE})`);
    const seen = new Set();
    let reported = 0;
    for (const s of palette) {
      for (const m of stateErrors(s)) if (reported++ < 3) p(`palette ${m}`);
      if (seen.has(s)) {
        if (reported++ < 3) p(`palette entry ${JSON.stringify(s)} appears twice`);
      }
      seen.add(s);
      if (isStr(s) && VOLUME_AIR_IDS.includes(stateId(s))) {
        if (reported++ < 3) p(`palette contains air ${JSON.stringify(s)}: air is index 65535, never a palette entry`);
      }
    }
  }
  if (biomeIds) for (const id of biomeIds) if (!ID_RE.test(id)) p(`biome_ids entry ${JSON.stringify(id)} is not a namespaced id`);

  let blocksOk = true;
  if (!isBin(c.blocks)) {
    p('blocks must be bin');
    blocksOk = false;
  } else if (height !== null && c.blocks.length !== height * 512) {
    p(`blocks must be height x 256 x 2 = ${height * 512} bytes, got ${c.blocks.length}`);
    blocksOk = false;
  }
  if (!isBin(c.biome)) p('biome must be bin');
  else if (c.biome.length !== 512) p(`biome must be exactly 512 bytes, got ${c.biome.length}`);
  else if (biomeIds) {
    const b = u16Array(c.biome);
    let bad = 0;
    for (let i = 0; i < 256; i++) if (b[i] >= biomeIds.length && !bad++) p(`biome index ${b[i]} in column ${i} >= biome_ids size ${biomeIds.length}`);
  }
  if (height === 0 && palette && palette.length !== 0) p('height 0 needs an empty palette');
  if (blocksOk && palette && height !== null) {
    const b = u16Array(c.blocks);
    let bad = 0;
    for (let i = 0; i < b.length; i++) {
      if (b[i] !== AIR_INDEX && b[i] >= palette.length && !bad++) p(`blocks[${i}] = ${b[i]} is neither a palette index (< ${palette.length}) nor 0xFFFF (air)`);
    }
  }
  return e;
}

/** Cell at (x, y, z) of a structurally valid volume: a palette string, or null for air. y outside the band is air. */
export function cellState(c, blocks, x, y, z) {
  const level = y - num(c.y0);
  if (level < 0 || level >= num(c.height)) return null;
  const v = blocks[(level * 16 + z) * 16 + x];
  return v === AIR_INDEX ? null : c.palette[v];
}

/** Y of the highest level that holds any non-air cell, or null when none. */
export function highestNonAir(c, blocks) {
  for (let level = num(c.height) - 1; level >= 0; level--) {
    for (let i = level * 256; i < level * 256 + 256; i++) if (blocks[i] !== AIR_INDEX) return num(c.y0) + level;
  }
  return null;
}

/** Biome id of every column (256) of a structurally valid volume. */
export function volumeBiomes(c) {
  const b = u16Array(c.biome);
  return Array.from(b, (i) => c.biome_ids[i]);
}

/**
 * Columns of a map.chunks ChunkSurface: 256 x { surface: bool, y, id, depth, floorId | null, biome }.
 * A void chunk has no surface column; an all-air column (palette air as top, D23 b) has no surface block.
 */
export function surfaceColumns(s) {
  if (s.void === true) return Array.from({ length: 256 }, () => ({ surface: false, y: null, id: 'minecraft:air', depth: 0, floorId: null, biome: null }));
  const top = unpackU16LE(s.top);
  const y = unpackI16LE(s.y);
  const floor = unpackU16LE(s.floor);
  const biome = unpackU16LE(s.biome);
  return Array.from({ length: 256 }, (_, i) => {
    const id = s.palette[top[i]];
    return {
      surface: id !== 'minecraft:air',
      y: y[i],
      id,
      depth: s.water_depth[i],
      floorId: floor[i] === 0xffff ? null : s.palette[floor[i]],
      biome: s.biomes[biome[i]],
    };
  });
}

export const isWaterId = (id) => WATER_IDS.includes(id);
