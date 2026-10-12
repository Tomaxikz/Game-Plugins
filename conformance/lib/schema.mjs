// Structural validators for protocol payloads. Each returns an array of human-readable
// violations (empty when valid). Used by the tests and the profiles.

import { isIntV, isNum, isObj, isStr, isBool, isStrArr, isHex } from './check.mjs';
import { num } from './msgpack.mjs';
import {
  ACTION_ID,
  BUILTIN_GROUPS,
  EXTENSION_KINDS,
  FIELD_KINDS,
  GROUP_NAME,
  ITEM_NULLABLE_KEYS,
  KNOWN_OPS,
  EVENT_TYPES,
  MAX_FRAME,
  PLATFORMS,
  GAMES,
  KNOWN_FEATURES,
  PLAYER_STATES,
} from './protocol.mjs';
import { fingerprintOf, renderSubsetOf, subtreeTruncated, hash16 } from './canonical.mjs';

const SLOT_NAME = /^[a-z_]+$/;

/** Validates a hello `data` object. */
export function helloErrors(d, bridge) {
  const e = [];
  if (!isObj(d)) return ['hello data is not an object'];
  if (!isIntV(d.protocol)) e.push('protocol must be an int');
  if (bridge && bridge.protocol !== undefined && num(d.protocol) !== bridge.protocol) {
    e.push(`protocol ${num(d.protocol)} != bridge.json protocol ${bridge.protocol}`);
  }
  if (!GAMES.includes(d.game)) e.push(`game ${JSON.stringify(d.game)} not in ${GAMES.join('|')}`);
  if (GAMES.includes(d.game) && !PLATFORMS[d.game].includes(d.platform)) {
    e.push(`platform ${JSON.stringify(d.platform)} not valid for game ${d.game}`);
  }
  for (const k of ['platform_version', 'game_version', 'bridge_version']) {
    if (!isStr(d[k])) e.push(`${k} must be a string`);
  }
  if (bridge && bridge.game !== undefined && d.game !== bridge.game) e.push(`game ${d.game} != bridge.json game ${bridge.game}`);
  if (bridge && bridge.platform !== undefined && d.platform !== bridge.platform) {
    e.push(`platform ${d.platform} != bridge.json platform ${bridge.platform}`);
  }
  const c = d.capabilities;
  if (!isObj(c)) {
    e.push('capabilities must be an object');
  } else {
    if (!isStrArr(c.ops)) e.push('capabilities.ops must be string[]');
    else {
      for (const op of c.ops) if (!KNOWN_OPS.includes(op)) e.push(`capabilities.ops has unknown op ${op}`);
      if (new Set(c.ops).size !== c.ops.length) e.push('capabilities.ops has duplicates');
    }
    if (!isStrArr(c.player_fields)) e.push('capabilities.player_fields must be string[]');
    if (!isStrArr(c.events)) e.push('capabilities.events must be string[]');
    else for (const ev of c.events) if (!EVENT_TYPES.includes(ev)) e.push(`capabilities.events has unknown type ${ev}`);
    if (!isStrArr(c.features)) e.push('capabilities.features must be string[]');
    if (!Array.isArray(c.actions)) e.push('capabilities.actions must be an array');
    else e.push(...actionSpecErrors(c.actions));
  }
  if (d.integrations !== undefined && !isStrArr(d.integrations)) e.push('integrations must be string[]');
  const l = d.limits;
  if (!isObj(l)) {
    e.push('limits must be an object');
  } else {
    if (!isIntV(l.max_frame) || num(l.max_frame) <= 0 || num(l.max_frame) > MAX_FRAME) {
      e.push(`limits.max_frame must be an int in 1..${MAX_FRAME}`);
    }
    if (!isIntV(l.positions_min_interval_ms)) e.push('limits.positions_min_interval_ms must be an int');
    if (!isIntV(l.max_inflight) || num(l.max_inflight) < 1) e.push('limits.max_inflight must be an int >= 1');
  }
  return e;
}

/** ActionSpec array validation (operations.md section 1). */
export function actionSpecErrors(actions) {
  const e = [];
  const seen = new Set();
  for (const [i, a] of actions.entries()) {
    const p = `actions[${i}]`;
    if (!isObj(a)) {
      e.push(`${p} is not an object`);
      continue;
    }
    if (!isStr(a.id) || !ACTION_ID.test(a.id)) e.push(`${p}.id must be snake_case`);
    else {
      if (seen.has(a.id)) e.push(`${p}.id ${a.id} duplicated`);
      seen.add(a.id);
    }
    if (!isStr(a.label)) e.push(`${p}.label must be a string`);
    for (const k of ['target', 'destructive', 'offline']) if (!isBool(a[k])) e.push(`${p}.${k} must be a bool`);
    if (!Array.isArray(a.fields)) {
      e.push(`${p}.fields must be an array`);
      continue;
    }
    for (const [j, f] of a.fields.entries()) {
      const fp = `${p}.fields[${j}]`;
      if (!isObj(f)) {
        e.push(`${fp} is not an object`);
        continue;
      }
      if (!isStr(f.key)) e.push(`${fp}.key must be a string`);
      if (!isStr(f.label)) e.push(`${fp}.label must be a string`);
      if (!FIELD_KINDS.includes(f.kind)) e.push(`${fp}.kind ${JSON.stringify(f.kind)} not in allowed vocabulary`);
      if (!isStrArr(f.choices)) e.push(`${fp}.choices must always be present as string[]`);
      else if (f.kind !== 'select' && f.choices.length > 0) e.push(`${fp}.choices must be empty unless kind is select`);
      for (const k of ['min', 'max']) {
        if (!(k in f)) e.push(`${fp}.${k} must be present (null allowed)`);
        else if (!(f[k] === null || isNum(f[k]))) e.push(`${fp}.${k} must be a number or null`);
      }
    }
  }
  return e;
}

/** server.info data (operations.md section 2). */
export function serverInfoErrors(d) {
  const e = [];
  if (!isObj(d)) return ['server.info data is not an object'];
  if (!isStr(d.name)) e.push('name must be a string');
  if (d.motd !== undefined && !isStr(d.motd)) e.push('motd must be a string');
  if (!isIntV(d.max_players) || num(d.max_players) < 0) e.push('max_players must be an int >= 0');
  if (!isIntV(d.online) || num(d.online) < 0) e.push('online must be an int >= 0');
  if (!isIntV(d.uptime) || num(d.uptime) < 0) e.push('uptime must be an int >= 0');
  if (d.performance !== undefined) {
    if (!isObj(d.performance)) e.push('performance must be an object');
    else {
      for (const k of ['tps', 'mspt', 'frame_ms']) {
        if (d.performance[k] !== undefined && !isNum(d.performance[k])) e.push(`performance.${k} must be a number`);
      }
    }
  }
  if (!Array.isArray(d.worlds)) e.push('worlds must be an array');
  else {
    for (const [i, w] of d.worlds.entries()) {
      if (!isObj(w)) {
        e.push(`worlds[${i}] is not an object`);
        continue;
      }
      if (!isStr(w.world)) e.push(`worlds[${i}].world must be a string`);
      if (w.dimension !== undefined && !isStr(w.dimension)) e.push(`worlds[${i}].dimension must be a string`);
      if (w.name !== undefined && !isStr(w.name)) e.push(`worlds[${i}].name must be a string`);
      if (w.environment !== undefined && !isStr(w.environment)) e.push(`worlds[${i}].environment must be a string`);
      if (w.players !== undefined && !isIntV(w.players)) e.push(`worlds[${i}].players must be an int`);
      if (w.loaded !== undefined && !isBool(w.loaded)) e.push(`worlds[${i}].loaded must be a bool`);
      if (w.size !== undefined && !isIntV(w.size)) e.push(`worlds[${i}].size must be an int`);
      if (w.seed !== undefined && !isStr(w.seed)) e.push(`worlds[${i}].seed must be a string`);
    }
  }
  if (d.extensions !== undefined) {
    if (!Array.isArray(d.extensions)) e.push('extensions must be an array');
    else {
      for (const [i, x] of d.extensions.entries()) {
        if (!isObj(x) || !isStr(x.name) || !isStr(x.version) || !EXTENSION_KINDS.includes(x.kind)) {
          e.push(`extensions[${i}] needs name, version and kind (plugin|mod|resource)`);
        }
        if (x && x.state !== undefined && !isStr(x.state)) e.push(`extensions[${i}].state must be a string`);
      }
    }
  }
  if (d.game !== undefined && !isObj(d.game)) e.push('server.info.game must be an object');
  return e;
}

/**
 * players.list data (operations.md section 3). `opts`: { includeSensitive, features, includeOffline }.
 */
export function playersListErrors(d, opts = {}) {
  const e = [];
  if (!isObj(d) || !Array.isArray(d.players)) return ['players must be an array'];
  const list = d.players;
  if (list.length > 5000) e.push(`players has ${list.length} entries (max 5000)`);
  const ids = new Set();
  let sawOffline = false;
  for (const [i, p] of list.entries()) {
    const pp = `players[${i}]`;
    if (!isObj(p)) {
      e.push(`${pp} is not an object`);
      continue;
    }
    if (!isStr(p.id) || p.id === '') e.push(`${pp}.id must be a non-empty string`);
    else if (ids.has(p.id)) e.push(`${pp}.id ${p.id} duplicated`);
    else ids.add(p.id);
    if (!isStr(p.name)) e.push(`${pp}.name must be a string`);
    if (!isBool(p.online)) e.push(`${pp}.online must be a bool`);
    if (p.online === true) {
      if (p.ping !== null && p.ping !== undefined && !isIntV(p.ping)) e.push(`${pp}.ping must be an int or null when online`);
      if (sawOffline) e.push('online entries must come before offline entries');
    } else if (p.online === false) {
      sawOffline = true;
      if ('ping' in p) e.push(`${pp}.ping must be omitted for offline players`);
      if (opts.features && !opts.features.includes('offline_players')) e.push(`${pp} is offline but feature offline_players is not advertised`);
    }
    if (!Array.isArray(p.groups)) e.push(`${pp}.groups must always be an array`);
    else {
      for (const g of p.groups) {
        if (typeof g !== 'string' || !(BUILTIN_GROUPS.includes(g) || GROUP_NAME.test(g))) {
          e.push(`${pp}.groups has invalid value ${JSON.stringify(g)}`);
        }
      }
    }
    if (p.admin !== undefined && !isBool(p.admin)) e.push(`${pp}.admin must be a bool`);
    if (p.afk_seconds !== undefined && !(isIntV(p.afk_seconds) && num(p.afk_seconds) >= 0)) e.push(`${pp}.afk_seconds must be an int >= 0`);
    if (p.joined_at !== undefined && !isIntV(p.joined_at)) e.push(`${pp}.joined_at must be an int`);
    if (p.last_seen !== undefined && !isIntV(p.last_seen)) e.push(`${pp}.last_seen must be an int`);
    if (p.reason !== undefined && !isStr(p.reason)) e.push(`${pp}.reason must be a string`);
    if (p.world !== undefined && !isStr(p.world)) e.push(`${pp}.world must be a string`);
    if (p.dimension !== undefined && !isStr(p.dimension)) e.push(`${pp}.dimension must be a string`);
    if (p.server !== undefined && p.server !== null && !isStr(p.server)) e.push(`${pp}.server must be a string`);
    if (p.client !== undefined) {
      if (!isObj(p.client)) e.push(`${pp}.client must be an object`);
      else {
        for (const k of ['brand', 'version', 'locale']) {
          if (p.client[k] !== undefined && !isStr(p.client[k])) e.push(`${pp}.client.${k} must be a string`);
        }
      }
    }
    if (p.game !== undefined && !isObj(p.game)) e.push(`${pp}.game must be an object`);
    if ('ip' in p) {
      if (!opts.includeSensitive) e.push(`${pp}.ip present without include_sensitive`);
      else if (!isStr(p.ip)) e.push(`${pp}.ip must be a string`);
    }
  }
  return e;
}

/** A single encoded item record (items-and-assets.md section 1). */
export function itemErrors(rec, path = 'item', { nested = false, depth = 0 } = {}) {
  const e = [];
  const err = (m) => e.push(`${path}: ${m}`);
  if (!isObj(rec)) {
    err('item must be an object');
    return e;
  }
  if (depth > 4) err('contents nested deeper than 4 levels');
  if (!isStr(rec.id) || rec.id === '') err('id must be a non-empty string');
  if (!isIntV(rec.count) || num(rec.count) < 1) err('count must be an int >= 1');
  if (!nested && 'slot' in rec) err('top-level item must not carry slot (the slot lives in the container entry)');
  if (nested && 'slot' in rec && !(isIntV(rec.slot) || isStr(rec.slot))) err('contents slot must be an int or a name');
  for (const [k, v] of Object.entries(rec)) {
    if (v === null && !ITEM_NULLABLE_KEYS.includes(k)) err(`key ${k} is null (omit unknown keys instead)`);
  }
  if (rec.name !== undefined && rec.name !== null) {
    if (!isObj(rec.name) || !isStr(rec.name.plain)) err('name must be {plain: string, json?}');
  }
  if (rec.lore !== undefined && rec.lore !== null) {
    if (!Array.isArray(rec.lore) || !rec.lore.every((l) => isObj(l) && isStr(l.plain))) err('lore must be [{plain}]');
  }
  for (const k of ['damage', 'max_damage']) {
    if (rec[k] !== undefined && !(isIntV(rec[k]) && num(rec[k]) >= 0)) err(`${k} must be an int >= 0`);
  }
  if (rec.enchantments !== undefined) {
    if (!Array.isArray(rec.enchantments) || !rec.enchantments.every((x) => isObj(x) && isStr(x.id) && isIntV(x.level) && num(x.level) >= 1)) {
      err('enchantments must be [{id, level>=1}]');
    }
  }
  if (rec.components !== undefined && !isObj(rec.components)) err('components must be a map');
  if (rec.encoding !== undefined && !['components', 'nbt', 'bukkit_meta'].includes(rec.encoding)) err('encoding must be components|nbt|bukkit_meta');
  if (rec.truncated !== undefined && !isBool(rec.truncated)) err('truncated must be a bool');
  if (rec.model !== undefined && rec.model !== null && !isStr(rec.model)) err('model must be a string');
  if (rec.tint !== undefined && !(Array.isArray(rec.tint) && rec.tint.every(isIntV))) err('tint must be [int]');
  if (rec.skin !== undefined && !(isIntV(rec.skin) && num(rec.skin) >= 0)) err('skin must be an int >= 0');
  for (const k of ['condition', 'max_condition']) {
    if (rec[k] !== undefined && !isNum(rec[k])) err(`${k} must be a number`);
  }
  if (rec.ammo !== undefined) {
    if (!isObj(rec.ammo) || !isIntV(rec.ammo.count) || (rec.ammo.id !== undefined && !isStr(rec.ammo.id))) err('ammo must be {id?, count int}');
  }
  if (rec.contents !== undefined) {
    if (!Array.isArray(rec.contents)) err('contents must be an array');
    else rec.contents.forEach((c, i) => e.push(...itemErrors(c, `${path}.contents[${i}]`, { nested: true, depth: depth + 1 })));
  }
  if (!isHex(rec.hash, 16)) err('hash must be 16 lowercase hex chars');
  if (!isHex(rec.fingerprint, 64)) err('fingerprint must be 64 lowercase hex chars');
  return e;
}

/**
 * Recomputes fingerprint (exact) and hash (when the render subset is verifiable) for a record
 * tree. Records whose subtree was truncated are skipped for the fingerprint.
 * Returns { errors, checkedFingerprint, checkedHash, skippedHash }.
 */
export function digestErrors(rec, path = 'item', acc = { errors: [], fp: 0, hash: 0, skippedHash: 0 }) {
  if (!isObj(rec)) return acc;
  if (!subtreeTruncated(rec) && isHex(rec.fingerprint, 64)) {
    acc.fp += 1;
    if (fingerprintOf(rec) !== rec.fingerprint) acc.errors.push(`${path}: fingerprint does not match the record`);
  }
  if (isHex(rec.hash, 16)) {
    const r = renderSubsetOf(rec);
    if (r.verifiable) {
      acc.hash += 1;
      if (hash16(r.subset) !== rec.hash) acc.errors.push(`${path}: hash ${rec.hash} != recomputed ${hash16(r.subset)}`);
    } else {
      acc.skippedHash += 1;
    }
  }
  if (Array.isArray(rec.contents)) rec.contents.forEach((c, i) => digestErrors(c, `${path}.contents[${i}]`, acc));
  return acc;
}

/** containers array (operations.md section 5). `opts.named`: { containerId: [allowed names] }. */
export function containersErrors(containers, opts = {}) {
  const e = [];
  if (!Array.isArray(containers)) return ['containers must be an array'];
  const ids = new Set();
  for (const [i, c] of containers.entries()) {
    const p = `containers[${i}]`;
    if (!isObj(c)) {
      e.push(`${p} is not an object`);
      continue;
    }
    if (!isStr(c.id) || c.id === '') e.push(`${p}.id must be a non-empty string`);
    else if (ids.has(c.id)) e.push(`${p}.id ${c.id} duplicated`);
    else ids.add(c.id);
    if (!isIntV(c.size) || num(c.size) < 0) e.push(`${p}.size must be an int >= 0`);
    if (!Array.isArray(c.slots)) {
      e.push(`${p}.slots must be an array`);
      continue;
    }
    const size = isIntV(c.size) ? num(c.size) : Infinity;
    const used = new Set();
    const named = opts.named?.[c.id];
    for (const [j, s] of c.slots.entries()) {
      const sp = `${p}.slots[${j}]`;
      if (!isObj(s)) {
        e.push(`${sp} is not an object`);
        continue;
      }
      if (typeof s.slot === 'string') {
        if (!SLOT_NAME.test(s.slot)) e.push(`${sp}.slot name ${JSON.stringify(s.slot)} is not a valid name`);
        if (named && !named.includes(s.slot)) e.push(`${sp}.slot ${s.slot} is not a named slot of ${c.id}`);
      } else if (!isIntV(s.slot)) {
        e.push(`${sp}.slot must be an int (or a name)`);
      } else if (num(s.slot) < 0 || num(s.slot) >= size) {
        e.push(`${sp}.slot ${num(s.slot)} outside 0..${size - 1}`);
      }
      const key = String(s.slot);
      if (used.has(key)) e.push(`${sp}.slot ${key} appears twice in ${c.id}`);
      used.add(key);
      if (!isObj(s.item)) e.push(`${sp}.item must be an encoded item (empty slots are omitted)`);
      else e.push(...itemErrors(s.item, `${sp}.item`));
    }
  }
  return e;
}

/** A player.get response body (operations.md section 4), without game-specific checks. */
export function playerGetErrors(d, id) {
  const e = [];
  if (!isObj(d)) return ['player.get data must be an object'];
  if (d.id !== id) e.push(`id ${JSON.stringify(d.id)} != requested ${id}`);
  if (!isStr(d.name)) e.push('name must be a string');
  if (!isBool(d.online)) e.push('online must be a bool');
  if (d.world !== undefined && d.world !== null && !isStr(d.world)) e.push('world must be a string');
  if (d.dimension !== undefined && d.dimension !== null && !isStr(d.dimension)) e.push('dimension must be a string');
  if (d.position !== undefined && d.position !== null) {
    const p = d.position;
    if (!isObj(p) || !isNum(p.x) || !isNum(p.y) || !isNum(p.z)) e.push('position must be {x,y,z} numbers');
    else {
      if (p.yaw !== undefined && !isNum(p.yaw)) e.push('position.yaw must be a number');
      if (p.pitch !== undefined && !isNum(p.pitch)) e.push('position.pitch must be a number');
    }
  }
  for (const k of ['health', 'max_health', 'armor', 'food', 'saturation']) {
    if (d[k] !== undefined && d[k] !== null && !isNum(d[k])) e.push(`${k} must be a number`);
  }
  if (d.held_item !== undefined && d.held_item !== null) e.push(...itemErrors(d.held_item, 'held_item'));
  if (d.vehicle !== undefined && d.vehicle !== null && !(isObj(d.vehicle) && isStr(d.vehicle.type))) e.push('vehicle must be null or {type}');
  if (d.game_mode !== undefined && d.game_mode !== null && !isStr(d.game_mode)) e.push('game_mode must be a string');
  if (d.experience !== undefined && d.experience !== null) {
    const x = d.experience;
    if (!isObj(x) || !isIntV(x.level) || !isNum(x.progress) || !isIntV(x.total)) e.push('experience must be {level int, progress number, total int}');
    else if (num(x.progress) < 0 || num(x.progress) > 1) e.push('experience.progress must be within 0..1');
  }
  if (d.ping !== undefined && d.ping !== null && !isIntV(d.ping)) e.push('ping must be an int');
  if (d.last_seen !== undefined && !isIntV(d.last_seen)) e.push('last_seen must be an int');
  if (d.game !== undefined && !isObj(d.game)) e.push('game must be an object');
  return e;
}

export function eventsErrors(f) {
  if (!isObj(f) || !EVENT_TYPES.includes(f.type)) return [`event type ${JSON.stringify(f && f.type)} unknown`];
  return [];
}

export { KNOWN_OPS, PLAYER_STATES, KNOWN_FEATURES };
