'use strict';
// FiveM / RedM game adapter. The ONLY module that touches FXServer globals (natives, exports, JS helpers).
// Every method here must be called on the game (main) thread. Returns plain copies, never live objects.
const path = require('path');
const { joaat, hashName, weaponId, UNARMED } = require('./weapons');
const { finishRecord } = require('./items');
const { fingerprint } = require('./canonical');

const ID_TYPES = new Set(['license', 'steam', 'discord', 'fivem', 'xbl', 'live']);
const OX_IMAGE_RE = /^[A-Za-z0-9_]{1,64}\.png$/;

function num(v, def = null) { return typeof v === 'number' && Number.isFinite(v) ? v : def; }

function vec(v) {
  if (Array.isArray(v) && v.length >= 3) return { x: Number(v[0]), y: Number(v[1]), z: Number(v[2]) };
  if (v && typeof v === 'object' && 'x' in v) return { x: Number(v.x), y: Number(v.y), z: Number(v.z) };
  return null;
}

function createFiveMGame({ g = globalThis, fs, resourceName, platform = 'fivem', log = () => {} }) {
  const world = platform === 'redm' ? 'rdr3' : 'gta5';

  function call(name, ...args) {
    const fn = g[name];
    if (typeof fn !== 'function') return undefined;
    return fn(...args);
  }
  function safe(name, ...args) {
    try { return call(name, ...args); } catch (e) { log('native ' + name + ' failed: ' + (e && e.message)); return undefined; }
  }
  function exp(resource) {
    try { return g.exports ? g.exports[resource] : undefined; } catch (e) { return undefined; }
  }
  function resState(name) { return safe('GetResourceState', name) || 'missing'; }
  const started = (name) => resState(name) === 'started';

  // ---- roster -------------------------------------------------------------
  function identifiersOf(src) {
    let ids = [];
    try {
      if (typeof g.getPlayerIdentifiers === 'function') ids = g.getPlayerIdentifiers(src) || [];
      else {
        const n = num(safe('GetNumPlayerIdentifiers', src), 0);
        for (let i = 0; i < n; i++) ids.push(safe('GetPlayerIdentifier', src, i));
      }
    } catch (e) { ids = []; }
    return ids.filter((s) => typeof s === 'string' && ID_TYPES.has(s.split(':')[0]));
  }

  // Within one drain pass (beginBatch..endBatch) every request sees the same GetPlayers snapshot: N requests = 1 call.
  let batchActive = false;
  let batchOnline = null;
  function beginBatch() { batchActive = true; batchOnline = null; }
  function endBatch() { batchActive = false; batchOnline = null; }
  function onlineSources() {
    if (batchActive && batchOnline) return batchOnline;
    const out = readOnline();
    if (batchActive) batchOnline = out;
    return out;
  }
  function readOnline() {
    let list = [];
    try {
      if (typeof g.getPlayers === 'function') list = g.getPlayers() || [];
      else list = safe('GetPlayers') || [];
    } catch (e) { list = []; }
    const out = [];
    for (let i = 0; i < list.length; i++) {
      const n = Number(list[i]);
      if (Number.isInteger(n) && n > 0 && n <= 65535) out.push(n);
    }
    return out;
  }

  // Identity cache. Identifiers never change while a source is connected and reading them costs 1 + 2n natives per
  // player, so they are read once per connection (dropped on playerDropped via forget(), and pruned whenever a source
  // is absent from the online list). Names can change, so they are re-read at most every NAME_TTL_MS by the sampler
  // (on-demand ops such as players.list always re-read). Players without a license are re-checked every RETRY_MS.
  const NAME_TTL_MS = 5000;
  const RETRY_MS = 5000;
  const identities = new Map(); // source -> { id|null, identifiers, name, nameAt, at }
  const slots = new Map();      // source -> reusable position sample record
  function entryFor(src, now, freshName) {
    let e = identities.get(src);
    if (!e || (e.id === null && now - e.at >= RETRY_MS)) {
      const identifiers = Object.freeze(identifiersOf(src));
      const id = identifiers.find((s) => s.startsWith('license:')) || null;
      if (!id && !e) log('player ' + src + ' has no license identifier; not listed');
      e = { id, identifiers, name: '', nameAt: -Infinity, at: now };
      identities.set(src, e);
    }
    if (e.id && (freshName || now - e.nameAt >= NAME_TTL_MS)) { e.name = String(safe('GetPlayerName', src) ?? ''); e.nameAt = now; }
    return e;
  }
  function prune(online) {
    if (identities.size <= online.length && slots.size <= online.length) return;
    const live = new Set(online);
    for (const k of identities.keys()) if (!live.has(k)) identities.delete(k);
    for (const k of slots.keys()) if (!live.has(k)) slots.delete(k);
  }
  function peekIdentity(src) { const e = identities.get(src); return e && e.id ? { source: src, id: e.id, name: e.name, identifiers: e.identifiers } : null; }
  function forget(src) { identities.delete(src); slots.delete(src); }

  // Roster entries: { source, id (first license), name, identifiers }. Players without license are skipped.
  function roster() {
    const out = [];
    const online = onlineSources();
    prune(online);
    const now = Date.now();
    for (const src of online) {
      const e = entryFor(src, now, true);
      if (!e.id) continue;
      out.push({ source: src, id: e.id, name: e.name, identifiers: e.identifiers });
    }
    return out;
  }

  // Looks one player up by license id without re-reading every player (identifiers come from the cache).
  function findOnline(id) {
    const online = onlineSources();
    prune(online);
    const now = Date.now();
    for (const src of online) {
      const e = entryFor(src, now, false);
      if (e.id !== id) continue;
      const fresh = entryFor(src, now, true);
      return { source: src, id: fresh.id, name: fresh.name, identifiers: fresh.identifiers };
    }
    return null;
  }

  function onlineCount() {
    const online = onlineSources();
    prune(online);
    const now = Date.now();
    let n = 0;
    for (const src of online) if (entryFor(src, now, false).id) n++;
    return n;
  }

  function pingOf(src) {
    const v = num(safe('GetPlayerPing', src), -1);
    return v >= 0 ? v : null;
  }

  function identityOf(src) {
    forget(src);
    const e = entryFor(src, Date.now(), true);
    return e.id ? { source: src, id: e.id, name: e.name, identifiers: e.identifiers } : null;
  }

  // ---- per-player reads (main thread) -------------------------------------
  function readPlayer(src, { withVehicle = true } = {}) {
    const ped = num(safe('GetPlayerPed', src), 0);
    const pingRaw = num(safe('GetPlayerPing', src), -1);
    const out = {
      source: src,
      ping: pingRaw >= 0 ? pingRaw : null,
      routing_bucket: num(safe('GetPlayerRoutingBucket', src), 0),
      ped: ped > 0 ? ped : null,
    };
    if (!out.ped) return out;
    out.coords = vec(safe('GetEntityCoords', ped));
    out.heading = num(safe('GetEntityHeading', ped), 0);
    out.health = num(safe('GetEntityHealth', ped), 0);
    out.max_health = num(safe('GetEntityMaxHealth', ped), 0);
    out.armour = num(safe('GetPedArmour', ped), 0);
    out.weapon_hash = num(safe('GetSelectedPedWeapon', ped), 0) >>> 0;
    out.ped_model = num(safe('GetEntityModel', ped), 0) >>> 0;
    if (withVehicle) {
      const veh = num(safe('GetVehiclePedIsIn', ped, false), 0);
      if (veh > 0) {
        out.vehicle = {
          handle: veh,
          model: num(safe('GetEntityModel', veh), 0) >>> 0,
          plate: String(safe('GetVehicleNumberPlateText', veh) ?? '').trim(),
          speed: num(safe('GetEntitySpeed', veh), 0),
        };
      } else out.vehicle = null;
    }
    return out;
  }

  // Position sampler: primitive copies into reusable per-player slots (no per-sample allocation beyond what the
  // natives return). Per player: GetPlayerPed, GetEntityCoords, GetEntityHeading, GetEntityHealth (4 natives);
  // the roster itself costs one GetPlayers. The returned batch object and its slots are reused across calls and
  // must be consumed synchronously.
  const batch = { list: [], n: 0 };
  function positionSamples() {
    const online = onlineSources();
    prune(online);
    const now = Date.now();
    let n = 0;
    for (let i = 0; i < online.length; i++) {
      const src = online[i];
      const e = entryFor(src, now, false);
      if (!e.id) continue;
      let slot = slots.get(src);
      if (!slot || slot.id !== e.id) {
        slot = { id: e.id, name: '', source: src, hasCoords: false, x: 0, y: 0, z: 0, heading: 0, health: 0, qx: 0, qy: 0, qz: 0, qyaw: 0, alive: false };
        slots.set(src, slot);
      }
      slot.name = e.name;
      const ped = num(safe('GetPlayerPed', src), 0);
      slot.hasCoords = false; slot.heading = 0; slot.health = 0;
      if (ped > 0) {
        const c = safe('GetEntityCoords', ped);
        if (c && (Array.isArray(c) ? c.length >= 3 : 'x' in c)) {
          if (Array.isArray(c)) { slot.x = Number(c[0]); slot.y = Number(c[1]); slot.z = Number(c[2]); }
          else { slot.x = Number(c.x); slot.y = Number(c.y); slot.z = Number(c.z); }
          slot.hasCoords = true;
        }
        slot.heading = num(safe('GetEntityHeading', ped), 0);
        slot.health = num(safe('GetEntityHealth', ped), 0);
      }
      batch.list[n++] = slot;
    }
    batch.list.length = n;
    batch.n = n;
    return batch;
  }

  function serverInfo() {
    const resources = [];
    const n = Math.min(500, num(safe('GetNumResources'), 0));
    for (let i = 0; i < n; i++) {
      const name = safe('GetResourceByFindIndex', i);
      if (!name) continue;
      resources.push({ name: String(name), kind: 'resource', state: resState(name) });
    }
    return {
      name: String(safe('GetConvar', 'sv_hostname', '') ?? ''),
      max_players: num(safe('GetConvarInt', 'sv_maxclients', 32), 32),
      resources,
      game: {
        hostname: String(safe('GetConvar', 'sv_hostname', '') ?? ''),
        sv_projectName: String(safe('GetConvar', 'sv_projectName', '') ?? ''),
        gametype: String(safe('GetConvar', 'gametype', '') ?? ''),
        mapname: String(safe('GetConvar', 'mapname', '') ?? ''),
        onesync: String(safe('GetConvar', 'onesync', 'off') ?? 'off'),
        game_build: String(num(safe('GetGameBuildNumber'), '') ?? ''),
        server_version: String(safe('GetConvar', 'version', '') ?? ''),
        txadmin_present: started('monitor'), // verify: txAdmin resource name is 'monitor'
      },
    };
  }

  function oneSyncEnabled() {
    const v = String(safe('GetConvar', 'onesync', 'off') ?? 'off').toLowerCase();
    return v === 'on' || v === 'true';
  }

  function gameName() { return String(safe('GetGameName') ?? world); }

  // ---- frameworks (games/fivem.md section 6) -----------------------------
  function detectFramework() {
    if (started('qbx_core')) return { name: 'qbox', resource: 'qbx_core' };
    if (started('qb-core')) return { name: 'qbcore', resource: 'qb-core' };
    if (started('es_extended')) return { name: 'esx', resource: 'es_extended' };
    return { name: 'none', resource: null };
  }

  function frameworkPlayer(fw, src) {
    try {
      if (fw.name === 'qbox') return exp('qbx_core').GetPlayer(src) || null;
      if (fw.name === 'qbcore') return exp('qb-core').GetCoreObject().Functions.GetPlayer(src) || null;
    } catch (e) { log('framework read failed: ' + (e && e.message)); }
    return null;
  }

  function readFramework(src) {
    const fw = detectFramework();
    const base = { name: fw.name, resource: fw.resource };
    if (fw.name === 'none') return { ...base, job: null, money: null, metadata: null };
    try {
      if (fw.name === 'esx') {
        const ESX = exp('es_extended').getSharedObject();
        const x = ESX.GetPlayerFromId(src);
        if (!x) return { ...base, job: null, money: null, metadata: null };
        const job = x.getJob();
        const accounts = x.getAccounts(true) || {};
        const money = {};
        for (const k of Object.keys(accounts)) money[k] = num(accounts[k], 0);
        return { ...base,
          job: job ? { name: String(job.name), grade: num(job.grade, 0), label: String(job.label ?? job.name), grade_label: job.grade_label ? String(job.grade_label) : undefined } : null,
          money, metadata: null };
      }
      const p = frameworkPlayer(fw, src);
      if (!p || !p.PlayerData) return { ...base, job: null, money: null, metadata: null };
      const pd = p.PlayerData;
      const out = { ...base,
        job: pd.job ? { name: String(pd.job.name), grade: num(pd.job.grade && pd.job.grade.level, 0), label: String(pd.job.label ?? pd.job.name) } : null,
        money: pd.money ? Object.fromEntries(Object.entries(pd.money).map(([k, v]) => [k, num(v, 0)])) : null,
        metadata: pd.metadata ?? null,
      };
      if (fw.name === 'qbcore' || fw.name === 'qbox') {
        out.gang = pd.gang ? { name: String(pd.gang.name), grade: num(pd.gang.grade && pd.gang.grade.level, 0), label: String(pd.gang.label ?? pd.gang.name) } : null;
      }
      return out;
    } catch (e) {
      log('framework ' + fw.name + ' read failed: ' + (e && e.message));
      return { ...base, job: null, money: null, metadata: null };
    }
  }

  // ---- inventory (games/fivem.md section 7) ------------------------------
  function inventorySource() {
    if (started('ox_inventory')) return 'ox_inventory';
    const fw = detectFramework();
    // Fallback read path verified for QBCore/Qbox PlayerData.items only.
    if (fw.name === 'qbcore' || fw.name === 'qbox') return fw.name;
    return null;
  }

  function oxItems(src) {
    const inv = exp('ox_inventory').GetInventory(src);
    if (!inv) return null;
    const items = [];
    for (const [k, it] of Object.entries(inv.items || {})) {
      const slot = Number(k);
      if (!it || !Number.isInteger(slot)) continue;
      items.push({ slot, name: String(it.name), count: num(it.count, 0), label: it.label ? String(it.label) : null,
        weight: num(it.weight, null), metadata: it.metadata && typeof it.metadata === 'object' && Object.keys(it.metadata).length ? it.metadata : null });
    }
    return { source: 'ox_inventory', weight: num(inv.weight, 0), max_weight: num(inv.maxWeight, null), slots: num(inv.slots, 0), items };
  }

  function frameworkItems(fw, src) {
    const p = frameworkPlayer({ name: fw }, src);
    if (!p || !p.PlayerData || !Array.isArray(p.PlayerData.items)) return null;
    const items = p.PlayerData.items.map((it, i) => ({
      slot: num(it.slot, i + 1), name: String(it.name), count: num(it.amount ?? it.count, 0),
      label: it.label ? String(it.label) : null, weight: num(it.weight, null), metadata: it.info && typeof it.info === 'object' && Object.keys(it.info).length ? it.info : null,
    }));
    return { source: fw, weight: null, max_weight: null, slots: 0, items };
  }

  function readInventory(src) {
    const s = inventorySource();
    if (!s) return null;
    if (s === 'ox_inventory') return oxItems(src);
    return frameworkItems(s, src);
  }

  // FiveM platform data lives under components {label, weight, metadata} (lead contract update).
  function itemRecord(it, withSlot = false) {
    const rec = { id: it.name, count: it.count };
    if (withSlot) rec.slot = it.slot;
    const comp = {};
    if (it.label) comp.label = it.label;
    if (it.weight !== null && it.weight !== undefined) comp.weight = it.weight;
    if (it.metadata) comp.metadata = it.metadata;
    if (Object.keys(comp).length) rec.components = comp;
    if (it.metadata && typeof it.metadata.label === 'string' && it.metadata.label) rec.name = { plain: it.metadata.label.replace(/\^[0-9]/g, '') };
    return rec;
  }

  function finishItem(it) { const { slot, ...rec } = itemRecord(it, true); return finishRecord(rec); }

  function weaponRecord(hash) {
    const id = weaponId(hash);
    return finishRecord({ id, count: 1 });
  }

  function itemExists(name) {
    if (!started('ox_inventory')) return false;
    try { return !!exp('ox_inventory').Items(name); } catch (e) { return false; }
  }

  function itemCount(src, name) {
    return num(exp('ox_inventory').GetItemCount(src, name), 0);
  }

  // ---- asset images (items-and-assets.md section 8) -----------------------
  // Read-only access to the image folders of started inventory resources. Reads are absolute paths (not gated
  // by the write policy in FilesystemPermissions.cpp).
  const ASSET_SOURCES = [
    ['ox_inventory', ['web', 'images']],
    ['qb-inventory', ['html', 'images']],
    ['qs-inventory', ['html', 'images']],
  ];
  function assetDirs() {
    const out = [];
    for (const [name, parts] of ASSET_SOURCES) {
      if (!started(name)) continue;
      const p = safe('GetResourcePath', name);
      if (p) out.push(path.join(String(p), ...parts));
    }
    return out;
  }
  function hasAssets() { return assetDirs().length > 0; }

  function listImages() {
    const names = new Set();
    for (const dir of assetDirs()) {
      try { for (const f of fs.readdirSync(dir)) if (OX_IMAGE_RE.test(f)) names.add(f); } catch (e) { /* folder missing */ }
    }
    return [...names].sort();
  }

  function readImage(file) {
    if (!OX_IMAGE_RE.test(file)) return null;
    for (const dir of assetDirs()) {
      try { return fs.readFileSync(path.join(dir, file)); } catch (e) { /* try next source */ }
    }
    return null;
  }

  // ---- actions ------------------------------------------------------------
  function emitClient(event, src, payload) {
    call('emitNet', event, src, payload);
  }
  function dropPlayer(src, reason) { batchOnline = null; call('DropPlayer', src, reason); }
  function setRoutingBucket(src, bucket) { call('SetPlayerRoutingBucket', src, bucket); }
  function setJob(src, fw, job, grade) {
    if (fw === 'esx') {
      const ESX = exp('es_extended').getSharedObject();
      const x = ESX.GetPlayerFromId(src);
      if (!x) return false;
      if (typeof ESX.DoesJobExist === 'function' && !ESX.DoesJobExist(job, String(grade))) return false;
      x.setJob(job, grade);
      return true;
    }
    if (fw === 'qbcore') {
      const p = frameworkPlayer({ name: 'qbcore' }, src);
      if (!p) return false;
      return p.Functions.SetJob(job, grade) !== false;
    }
    if (fw === 'qbox') {
      if (!frameworkPlayer({ name: 'qbox' }, src)) return false;
      exp('qbx_core').SetJob(src, job, grade);
      return true;
    }
    return false;
  }
  function addItem(src, item, count, metadata) {
    const r = exp('ox_inventory').AddItem(src, item, count, metadata || undefined);
    const ok = r === true || (Array.isArray(r) ? r[0] === true : !!r && typeof r === 'object' && r.success === true);
    return { ok, reason: Array.isArray(r) && r[1] ? String(r[1]) : null };
  }
  function removeItem(src, item, count, slot) {
    const r = exp('ox_inventory').RemoveItem(src, item, count, undefined, slot || undefined);
    return r === true || (Array.isArray(r) ? r[0] === true : false);
  }
  function slotItem(src, slot) {
    const inv = readInventory(src);
    if (!inv) return null;
    return inv.items.find((i) => i.slot === slot) || null;
  }

  return {
    world, platform,
    oneSyncEnabled, gameName, serverInfo, roster, beginBatch, endBatch, findOnline, onlineCount, pingOf, identityOf, peekIdentity, forget, readPlayer, positionSamples,
    detectFramework, readFramework, inventorySource, readInventory, itemRecord, finishItem, weaponRecord,
    itemExists, itemCount, listImages, readImage, hasAssets, started,
    emitClient, dropPlayer, setRoutingBucket, setJob, addItem, removeItem, slotItem,
    endpointOf: (src) => String(safe('GetPlayerEndpoint', src) ?? ''),
    weaponName: (h) => hashName(h).name,
    UNARMED,
    fingerprint,
  };
}

module.exports = { createFiveMGame, vec, joaat };
