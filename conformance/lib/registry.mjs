// biomes.list and blocks.list (operations.md 6d, 6e; games/minecraft.md 6b, 6c): the blocks hash and the
// validators.

import { hash16 } from './canonical.mjs';
import { isObj, isStr, isIntV, isNum, isHex } from './check.mjs';
import { num } from './msgpack.mjs';
import { bytewise } from './volume.mjs';

export const BLOCK_FLAGS = {
  air: 1,
  liquid: 2,
  can_occlude: 4,
  full_cube: 8,
  no_collision: 16,
  replaceable: 32,
  emits_light: 64,
  render_invisible: 128,
  block_entity: 256,
  waterloggable: 512,
};
export const KNOWN_FLAG_MASK = 1023;
export const GRASS_MODIFIERS = ['none', 'dark_forest', 'swamp'];

const ID_RE = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const PROP_RE = /^[a-z0-9_]+=[^\s,=[\]]+$/;

/** blocks.list `hash`: first 16 hex of SHA-256 over canonical MessagePack of the full `blocks` array as sent (6e). */
export function blocksHash(blocks) {
  return hash16(blocks);
}

const isColor = (v) => isIntV(v) && num(v) >= 0 && num(v) <= 0xffffff;

// ------------------------------------------------------------------ biomes.list

/** Problems of a `biomes` array (6d). `data` keys other than `biomes` are ignored by the caller. */
export function biomeListErrors(list) {
  if (!Array.isArray(list)) return ['biomes must be an array'];
  const e = [];
  const seen = new Set();
  let prev = null;
  list.forEach((b, i) => {
    const at = isObj(b) && isStr(b.id) ? `biome ${b.id}` : `biomes[${i}]`;
    const p = (m) => e.push(`${at}: ${m}`);
    if (!isObj(b)) return p('not an object');
    if (!isStr(b.id) || !ID_RE.test(b.id)) p('id must be a namespaced id');
    else {
      if (seen.has(b.id)) p('id appears twice');
      seen.add(b.id);
      if (prev !== null && bytewise(prev, b.id) >= 0) p(`not sorted by id (bytewise): ${prev} before ${b.id}`);
      prev = b.id;
    }
    for (const k of ['temperature', 'downfall']) if (!isNum(b[k])) p(`${k} must be a number`);
    for (const k of ['grass_color', 'foliage_color', 'dry_foliage_color']) {
      // "int | null": null (no override) is sent as null, the key is not omitted (README: absent = not supported).
      if (b[k] === undefined) p(`${k} must be present (a 24-bit int or null)`);
      else if (b[k] !== null && !isColor(b[k])) p(`${k} must be a 24-bit int (0xRRGGBB) or null, got ${JSON.stringify(b[k])}`);
    }
    if (!isColor(b.water_color)) p(`water_color must be a 24-bit int (0xRRGGBB), always present, got ${JSON.stringify(b.water_color)}`);
    if (!GRASS_MODIFIERS.includes(b.grass_color_modifier)) p(`grass_color_modifier ${JSON.stringify(b.grass_color_modifier)} not in ${GRASS_MODIFIERS.join('|')}`);
    for (const k of Object.keys(b)) {
      if (!['id', 'temperature', 'downfall', 'grass_color', 'foliage_color', 'dry_foliage_color', 'water_color', 'grass_color_modifier'].includes(k)) p(`unexpected key ${k}`);
    }
  });
  return e;
}

// ------------------------------------------------------------------ blocks.list

/** "k=v,k=v" properties string: pairs well formed, keys sorted bytewise and unique. */
export function propertiesErrors(s) {
  if (!isStr(s)) return ['properties must be a string'];
  if (s === '') return [];
  const e = [];
  let prev = null;
  for (const pair of s.split(',')) {
    if (!PROP_RE.test(pair)) {
      e.push(`${JSON.stringify(pair)} is not key=value`);
      continue;
    }
    const k = pair.slice(0, pair.indexOf('='));
    if (prev !== null) {
      const c = bytewise(prev, k);
      if (c === 0) e.push(`property ${k} twice`);
      else if (c > 0) e.push(`keys not sorted bytewise: ${prev} before ${k}`);
    }
    prev = k;
  }
  return e;
}

const colorOk = (v) => v === null || isColor(v);

/**
 * Problems of the `blocks` entries of one page, or of all pages joined (6e): shape, sorted unique ids, flag
 * values, state rules. Returns { errors, unknownBits } where unknownBits counts entries with flag bits above
 * the documented ten (allowed: later revisions may add bits).
 */
export function blocksListErrors(blocks) {
  if (!Array.isArray(blocks)) return { errors: ['blocks must be an array'], unknownBits: 0 };
  const e = [];
  let unknownBits = 0;
  const seen = new Set();
  let prev = null;
  blocks.forEach((b, i) => {
    const at = isObj(b) && isStr(b.id) ? `block ${b.id}` : `blocks[${i}]`;
    const p = (m) => e.push(`${at}: ${m}`);
    if (!isObj(b)) return p('not an object');
    if (!isStr(b.id) || !ID_RE.test(b.id)) p('id must be a namespaced id');
    else {
      if (seen.has(b.id)) p('id appears twice');
      seen.add(b.id);
      if (prev !== null && bytewise(prev, b.id) >= 0) p(`not sorted by id (bytewise): ${prev} before ${b.id}`);
      prev = b.id;
    }
    // map_color: int | null. The canonical hash drops a null, so the key may be missing only if it is null.
    if (b.map_color === undefined) p('map_color must be present (an int or null)');
    else if (!colorOk(b.map_color)) p(`map_color must be a 24-bit int or null, got ${JSON.stringify(b.map_color)}`);
    if (!isIntV(b.flags) || num(b.flags) < 0) p(`flags must be a non-negative int, got ${JSON.stringify(b.flags)}`);
    else if ((num(b.flags) & ~KNOWN_FLAG_MASK) !== 0) unknownBits++;
    if (!isIntV(b.light) || num(b.light) < 0 || num(b.light) > 15) p(`light must be an int 0..15, got ${JSON.stringify(b.light)}`);
    else if (isIntV(b.flags) && ((num(b.flags) & BLOCK_FLAGS.emits_light) !== 0) !== (num(b.light) > 0)) {
      p(`emits_light flag ${(num(b.flags) & BLOCK_FLAGS.emits_light) !== 0} disagrees with light ${num(b.light)} (the default state's)`);
    }
    for (const k of Object.keys(b)) if (!['id', 'map_color', 'flags', 'light', 'states'].includes(k)) p(`unexpected key ${k}`);
    if (b.states === undefined) return;
    if (!Array.isArray(b.states) || b.states.length === 0) return p('states must be a non-empty array when present (omit it when no state differs)');
    let prevProps = null;
    b.states.forEach((s, j) => {
      const sp = (m) => p(`states[${j}]: ${m}`);
      if (!isObj(s)) return sp('not an object');
      for (const m of propertiesErrors(s.properties)) sp(m);
      if (isStr(s.properties)) {
        if (prevProps !== null && bytewise(prevProps, s.properties) >= 0) sp(`not sorted by properties (bytewise) or duplicate: ${JSON.stringify(prevProps)} before ${JSON.stringify(s.properties)}`);
        prevProps = s.properties;
      }
      if (!isIntV(s.flags) || num(s.flags) < 0) sp(`flags must be a non-negative int (always present), got ${JSON.stringify(s.flags)}`);
      else if ((num(s.flags) & ~KNOWN_FLAG_MASK) !== 0) unknownBits++;
      if (s.map_color !== undefined && !colorOk(s.map_color)) sp(`map_color must be a 24-bit int or null, got ${JSON.stringify(s.map_color)}`);
      for (const k of Object.keys(s)) if (!['properties', 'map_color', 'flags'].includes(k)) sp(`unexpected key ${k}`);
      // The state must differ from the block default in flags or map_color (an absent map_color means "same").
      const sameFlags = isIntV(s.flags) && isIntV(b.flags) && num(s.flags) === num(b.flags);
      const sameColor = s.map_color === undefined || s.map_color === b.map_color || (s.map_color === null && b.map_color === undefined);
      if (sameFlags && sameColor) sp(`state ${JSON.stringify(s.properties)} does not differ from the block default (flags and map_color equal): it must not be listed`);
      if (s.map_color !== undefined && s.map_color === b.map_color) sp('map_color is present but equals the block map_color: omit it');
    });
  });
  return { errors: e, unknownBits };
}

export const hashOk = (h) => isHex(h, 16);
