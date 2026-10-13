// Map chunk surfaces (operations.md 6b / 7.3, games/minecraft.md 6a, NOTES.md D23): vocabularies,
// the hash, binary array helpers, a surface builder (used by the mock) and a validator (used by
// the tests).

import { hash16 } from './canonical.mjs';
import { isObj, isStr, isBin, isIntV, isHex } from './check.mjs';
import { num } from './msgpack.mjs';

export const AIR_IDS = ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:light', 'minecraft:structure_void', 'minecraft:barrier'];
export const WATER_IDS = ['minecraft:water', 'minecraft:bubble_column', 'minecraft:kelp', 'minecraft:kelp_plant', 'minecraft:seagrass', 'minecraft:tall_seagrass'];

export const NO_FLOOR = 0xffff;
export const MAX_CHUNKS_REQUEST = 256;
export const MAX_CHUNKS_FRAME = 64;
export const COOLDOWN_MS = 2000;

const ID_RE = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const SURFACE_KEYS = ['cx', 'cz', 'world', 'dimension', 'palette', 'biomes', 'top', 'y', 'water_depth', 'floor', 'biome', 'hash', 'void'];
const VOID_KEYS = ['cx', 'cz', 'world', 'dimension', 'hash', 'void'];

// ------------------------------------------------------------------ binary arrays

export function packU16LE(values) {
  const out = new Uint8Array(values.length * 2);
  values.forEach((v, i) => {
    out[2 * i] = v & 0xff;
    out[2 * i + 1] = (v >> 8) & 0xff;
  });
  return out;
}

export function packI16LE(values) {
  return packU16LE(values.map((v) => v & 0xffff));
}

export function packU8(values) {
  return Uint8Array.from(values);
}

export function unpackU16LE(bin) {
  const out = [];
  for (let i = 0; i + 1 < bin.length; i += 2) out.push(bin[i] | (bin[i + 1] << 8));
  return out;
}

export function unpackI16LE(bin) {
  return unpackU16LE(bin).map((v) => (v >= 0x8000 ? v - 0x10000 : v));
}

// ------------------------------------------------------------------ hash (D23 a)

export const VOID_HASH_INPUT = { void: true };

/** Hash of a non-void surface: canonical msgpack of {biome, biomes, floor, palette, top, water_depth, y}. */
export function surfaceHash(s) {
  return hash16({ biome: s.biome, biomes: s.biomes, floor: s.floor, palette: s.palette, top: s.top, water_depth: s.water_depth, y: s.y });
}

export function voidHash() {
  return hash16(VOID_HASH_INPUT);
}

// ------------------------------------------------------------------ builder (D23 b, e)

/**
 * Builds a ChunkSurface from 256 columns (index z*16+x), each
 * { top: id, y, depth, floor: id | null (null = 0xFFFF), biome: id }.
 * A chunk whose columns are all the "all air" case becomes the void form.
 */
export function buildSurface({ cx, cz, world, dimension, columns }) {
  if (columns.length !== 256) throw new Error('need 256 columns');
  if (columns.every((c) => c.top === 'minecraft:air')) {
    return { cx, cz, world, dimension, hash: voidHash(), void: true };
  }
  const palette = [];
  const biomes = [];
  const idx = (list, id) => {
    let i = list.indexOf(id);
    if (i < 0) {
      list.push(id);
      i = list.length - 1;
    }
    return i;
  };
  const top = [];
  const y = [];
  const depth = [];
  const floor = [];
  const biome = [];
  for (const c of columns) {
    top.push(idx(palette, c.top));
    y.push(c.y);
    depth.push(Math.min(255, c.depth));
    floor.push(c.floor === null || c.floor === undefined ? NO_FLOOR : idx(palette, c.floor));
    biome.push(idx(biomes, c.biome));
  }
  const s = { cx, cz, world, dimension, palette, biomes, top: packU16LE(top), y: packI16LE(y), water_depth: packU8(depth), floor: packU16LE(floor), biome: packU16LE(biome) };
  s.hash = surfaceHash(s);
  return s;
}

// ------------------------------------------------------------------ validator

/**
 * Validates one ChunkSurface. Returns a list of problems. `want` may carry { world, dimension }.
 */
export function chunkSurfaceErrors(s, want = {}) {
  const e = [];
  if (!isObj(s)) return ['chunk is not an object'];
  const at = isIntV(s.cx) && isIntV(s.cz) ? `chunk [${num(s.cx)},${num(s.cz)}]` : 'chunk';
  const p = (m) => e.push(`${at}: ${m}`);
  if (!isIntV(s.cx)) p('cx must be an int');
  if (!isIntV(s.cz)) p('cz must be an int');
  if (!isStr(s.world)) p('world must be a string');
  else if (want.world !== undefined && s.world !== want.world) p(`world ${s.world} != requested ${want.world}`);
  if (!isStr(s.dimension)) p('dimension must be a string');
  else if (want.dimension !== undefined && s.dimension !== want.dimension) p(`dimension ${s.dimension} != requested ${want.dimension}`);
  if (!isHex(s.hash, 16)) p('hash must be 16 lowercase hex chars');
  for (const k of Object.keys(s)) if (!SURFACE_KEYS.includes(k)) p(`unexpected key ${k}`);

  if (s.void !== undefined && typeof s.void !== 'boolean') {
    p('void must be a bool');
    return e;
  }
  if (s.void === true) {
    for (const k of Object.keys(s)) if (!VOID_KEYS.includes(k)) p(`void chunk must not carry ${k}`);
    if (isHex(s.hash, 16) && s.hash !== voidHash()) p(`void chunk hash ${s.hash} != ${voidHash()} (hash of {void:true}, D23)`);
    return e;
  }

  const strList = (name) => {
    const v = s[name];
    if (!Array.isArray(v) || !v.every(isStr)) {
      p(`${name} must be string[]`);
      return null;
    }
    for (const id of v) if (!ID_RE.test(id)) p(`${name} entry ${JSON.stringify(id)} is not a namespaced id`);
    return v;
  };
  const palette = strList('palette');
  const biomes = strList('biomes');
  if (palette) for (const id of palette) if (id.includes('[')) p(`palette entry ${id} carries block states (D23 e: ids only)`);

  const sizes = { top: 512, y: 512, water_depth: 256, floor: 512, biome: 512 };
  let binsOk = true;
  for (const [k, n] of Object.entries(sizes)) {
    if (!isBin(s[k])) {
      p(`${k} must be bin`);
      binsOk = false;
    } else if (s[k].length !== n) {
      p(`${k} must be exactly ${n} bytes, got ${s[k].length}`);
      binsOk = false;
    }
  }
  if (!binsOk || !palette || !biomes) return e;

  const top = unpackU16LE(s.top);
  const floor = unpackU16LE(s.floor);
  const biome = unpackU16LE(s.biome);
  const y = unpackI16LE(s.y);
  const depth = s.water_depth;
  const bad = { top: 0, floor: 0, biome: 0, floorRule: 0, waterTop: 0, air: 0, waterFloor: 0 };
  let allAir = true;
  for (let i = 0; i < 256; i++) {
    const col = `column ${i} (x=${i & 15}, z=${i >> 4})`;
    if (top[i] >= palette.length) {
      if (!bad.top++) p(`${col}: top index ${top[i]} >= palette size ${palette.length}`);
      allAir = false;
      continue;
    }
    if (biome[i] >= biomes.length && !bad.biome++) p(`${col}: biome index ${biome[i]} >= biomes size ${biomes.length}`);
    const topId = palette[top[i]];
    const airCase = topId === 'minecraft:air';
    if (!airCase) allAir = false;
    if (floor[i] !== NO_FLOOR && floor[i] >= palette.length && !bad.floor++) p(`${col}: floor index ${floor[i]} >= palette size ${palette.length}`);
    if (depth[i] === 0 && floor[i] !== NO_FLOOR && !bad.floorRule++) p(`${col}: floor must be 0xFFFF when water_depth is 0`);
    if (depth[i] > 0 && WATER_IDS.indexOf(topId) < 0 && !bad.waterTop++) p(`${col}: water_depth ${depth[i]} but top ${topId} is not a water block (waterlogged blocks are surface blocks, not water)`);
    if (depth[i] > 0 && floor[i] < palette.length && WATER_IDS.includes(palette[floor[i]]) && !bad.waterFloor++) p(`${col}: floor ${palette[floor[i]]} is a water block (floor is the first non-water block)`);
    if (AIR_IDS.includes(topId)) {
      if (!airCase) {
        if (!bad.air++) p(`${col}: top ${topId} is an air block (air is never a surface block)`);
      } else if (depth[i] !== 0 || floor[i] !== NO_FLOOR || y[i] % 16 !== 0) {
        if (!bad.air++) p(`${col}: all-air column must have water_depth 0, floor 0xFFFF and y = world minimum Y (D23 b)`);
      }
    }
  }
  if (allAir) p('every column is all-air: must be sent as void:true (D23 b)');
  if (e.length === 0 || e.every((m) => !/hash/.test(m))) {
    const h = surfaceHash(s);
    if (isHex(s.hash, 16) && s.hash !== h) p(`hash ${s.hash} != recomputed ${h} (D23 a)`);
  }
  return e;
}

/** `data.chunks` of a frame or response: list of problems. */
export function chunkListErrors(list, want) {
  if (!Array.isArray(list)) return ['chunks must be an array'];
  return list.flatMap((c) => chunkSurfaceErrors(c, want));
}
