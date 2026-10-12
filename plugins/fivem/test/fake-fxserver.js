'use strict';
const realFs = { writeFileSync: require('fs').writeFileSync, readFileSync: require('fs').readFileSync, existsSync: require('fs').existsSync, mkdirSync: require('fs').mkdirSync };
// Fake FXServer global environment for tests. Installs natives, JS helpers, exports and event hooks on globalThis.
// Values mirror the verified signatures in games/fivem.md; nothing here is a real game.
const handlers = new Map();   // event name -> [fn]
const netHandlers = new Map();
const emitted = [];           // { event, target, payload } from emitNet
const onEmit = { fn: null };  // optional hook: (event, target, payload) => void

const state = {
  resources: { ox_inventory: 'started', qbx_core: 'started', 'qb-core': 'missing', es_extended: 'missing', monitor: 'started', playermanager_bridge: 'started' },
  convars: { onesync: 'on', sv_hostname: 'Test Server', sv_maxclients: '48', version: 'FXServer-test v1.0', gametype: 'roleplay', mapname: 'default', sv_projectName: 'Test' },
  gameName: 'gta5',
  resourcePath: '',
  players: {},   // source -> record
  inventories: {},
  framework: {},
  calls: [],
};

function addPlayer(src, rec) {
  state.players[src] = {
    source: src, name: 'Player' + src, identifiers: ['license:' + rec.license, 'steam:110000112345678'],
    ping: 48, bucket: 0, endpoint: '10.0.0.' + src + ':30120', ped: src * 100 + 1,
    coords: [-1037.5, -2737.6, 13.8], heading: 329.5, health: 200, maxHealth: 200, armour: 35,
    weapon: 2725352035, pedModel: 1885233650, vehicle: null, ...rec,
  };
  state.players[src].license = rec.license;
}

function reset() {
  for (const k of Object.keys(state.players)) delete state.players[k];
  state.convars.onesync = 'on';
  state.gameName = 'gta5';
  handlers.clear(); netHandlers.clear(); emitted.length = 0;
  state.resources = { ox_inventory: 'started', qbx_core: 'started', 'qb-core': 'missing', es_extended: 'missing', monitor: 'started', playermanager_bridge: 'started' };
  state.inventories = {};
  addPlayer(1, { license: 'aaaa1111bbbb2222' , name: 'Alice' });
  addPlayer(2, { license: 'cccc2222dddd3333', name: 'Bob', vehicle: { handle: 555, model: 1031562256, plate: 'AB 123 CD', speed: 12.4 }, coords: [10, 20, 30] });
  addPlayer(3, { license: null, name: 'NoLicense', identifiers: ['steam:110000199999999'] });
  state.players[3].identifiers = ['steam:110000199999999'];
  state.inventories[1] = { items: { 1: { name: 'water', count: 3, label: 'Water', weight: 500, slot: 1 }, 5: { name: 'WEAPON_PISTOL', count: 1, label: 'Pistol', weight: 1000, slot: 5, metadata: { serial: 'X1' } } }, weight: 1500, maxWeight: 30000, slots: 50 };
  state.inventories[2] = { items: {}, weight: 0, maxWeight: 30000, slots: 50 };
  state.qbox = {};
  onEmit.fn = null;
}

const pl = (src) => state.players[src] || null;

function install() {
  const g = globalThis;
  reset();
  g.GetCurrentResourceName = () => 'playermanager_bridge';
  g.GetResourcePath = (name) => state.resourcePath;
  g.GetGameName = () => state.gameName;
  g.GetConvar = (n, d) => (n in state.convars ? state.convars[n] : d);
  g.GetConvarInt = (n, d) => (n in state.convars ? Number(state.convars[n]) : d);
  g.GetResourceState = (n) => state.resources[n] || 'missing';
  g.GetNumResources = () => Object.keys(state.resources).length;
  g.GetResourceByFindIndex = (i) => Object.keys(state.resources)[i];
  g.GetGameBuildNumber = () => 3095;
  g.getPlayers = () => Object.keys(state.players).filter((k) => state.players[k].online !== false).map(String);
  g.GetPlayers = g.getPlayers;
  g.getPlayerIdentifiers = (s) => (pl(Number(s)) ? pl(Number(s)).identifiers.slice() : []);
  g.GetPlayerName = (s) => (pl(s) ? pl(s).name : null);
  g.GetPlayerPed = (s) => (pl(s) ? pl(s).ped : 0);
  g.GetPlayerPing = (s) => (pl(s) ? pl(s).ping : -1);
  g.GetPlayerEndpoint = (s) => (pl(s) ? pl(s).endpoint : '');
  g.GetPlayerRoutingBucket = (s) => (pl(s) ? pl(s).bucket : 0);
  g.SetPlayerRoutingBucket = (s, b) => { state.calls.push(['bucket', s, b]); if (pl(s)) pl(s).bucket = b; };
  g.DropPlayer = (s, reason) => { state.calls.push(['drop', s, reason]); if (pl(s)) pl(s).online = false; };
  const pedOf = (ped) => Object.values(state.players).find((p) => p.ped === ped);
  g.GetEntityCoords = (e) => { const p = pedOf(e); return p ? p.coords.slice() : [0, 0, 0]; };
  g.GetEntityHeading = (e) => { const p = pedOf(e); return p ? p.heading : 0; };
  g.GetEntityHealth = (e) => { const p = pedOf(e); return p ? p.health : 0; };
  g.GetEntityMaxHealth = (e) => { const p = pedOf(e); return p ? p.maxHealth : 0; };
  g.GetPedArmour = (e) => { const p = pedOf(e); return p ? p.armour : 0; };
  g.GetSelectedPedWeapon = (e) => { const p = pedOf(e); return p ? p.weapon : 0; };
  g.GetEntityModel = (e) => { const p = pedOf(e); if (p) return p.pedModel; const v = Object.values(state.players).find((x) => x.vehicle && x.vehicle.handle === e); return v ? v.vehicle.model : 0; };
  g.GetVehiclePedIsIn = (ped) => { const p = pedOf(ped); return p && p.vehicle ? p.vehicle.handle : 0; };
  g.GetVehicleNumberPlateText = (v) => { const p = Object.values(state.players).find((x) => x.vehicle && x.vehicle.handle === v); return p ? p.vehicle.plate : ''; };
  g.GetEntitySpeed = (v) => { const p = Object.values(state.players).find((x) => x.vehicle && x.vehicle.handle === v); return p ? p.vehicle.speed : 0; };
  g.emitNet = (event, target, ...args) => {
    const payload = args[0];
    emitted.push({ event, target, payload });
    if (onEmit.fn) onEmit.fn(event, target, payload);
  };
  g.on = (name, fn) => { if (!handlers.has(name)) handlers.set(name, []); handlers.get(name).push(fn); };
  g.AddEventHandler = g.on;
  g.onNet = (name, fn) => { if (!netHandlers.has(name)) netHandlers.set(name, []); netHandlers.get(name).push(fn); };
  g.RegisterNetEvent = () => {};
  g.exports = {
    ox_inventory: {
      GetInventory: (s) => state.inventories[s] || null,
      Items: (name) => (['water', 'WEAPON_PISTOL', 'bread'].includes(name) ? { name, label: name } : null),
      GetItemCount: (s, item) => { const inv = state.inventories[s]; if (!inv) return 0; return Object.values(inv.items).filter((i) => i.name === item).reduce((a, i) => a + i.count, 0); },
      AddItem: (s, item, count) => (state.inventories[s] ? true : [false, 'no_inventory']),
      RemoveItem: (s, item, count, meta, slot) => { const inv = state.inventories[s]; if (!inv) return false; if (slot) { delete inv.items[slot]; } return true; },
    },
    qbx_core: {
      GetPlayer: (s) => (state.qbox[s] ? { PlayerData: state.qbox[s] } : (pl(s) ? { PlayerData: { job: { name: 'police', label: 'Police', grade: { level: 2 } }, gang: { name: 'none', label: 'No gang', grade: { level: 0 } }, money: { cash: 1500, bank: 22000 }, metadata: { hunger: 80 }, items: [] } } : null)),
      SetJob: (s, job, grade) => { state.calls.push(['setjob', s, job, grade]); return undefined; },
    },
  };
  return g;
}

function fireEvent(name, src, ...args) {
  const prev = globalThis.source;
  globalThis.source = src;
  try { for (const fn of handlers.get(name) || []) fn(...args); } finally { globalThis.source = prev; }
}
function fireNet(name, src, ...args) {
  const prev = globalThis.source;
  globalThis.source = src;
  try { for (const fn of netHandlers.get(name) || []) fn(...args); } finally { globalThis.source = prev; }
}

module.exports = { install, state, addPlayer, fireEvent, fireNet, emitted, onEmit, reset, handlers, netHandlers };

// Simulates FXServer's Node filesystem policy (citizenfx/fivem FilesystemPermissions.cpp, NodeScriptRuntime.cpp):
//   absoluteWrites: 'none' | 'outside-resource' (absolute writes refused except inside resourceDir) | 'all'
//   aliasWrites:    'ok' ('@<resource>/...' maps to resourceDir) | 'enoent' | 'eperm'
//   unlink:         'ok' | 'eperm' (every unlink refused)
// Patches the real fs module so the bridge code runs through the same calls it would use on FXServer.
function installFsPolicy({ resourceName, resourceDir, absoluteWrites = 'none', aliasWrites = 'ok', unlink = 'ok' }) {
  const fs = require('fs');
  const path = require('path');
  const ops = ['writeFileSync', 'mkdirSync', 'renameSync', 'unlinkSync', 'appendFileSync', 'rmSync', 'chmodSync'];
  const denied = [];
  const err = (code, msg) => Object.assign(new Error(code + ': ' + msg), { code });
  const inResource = (p) => { const r = path.relative(resourceDir, path.resolve(p)); return !!r && !r.startsWith('..') && !path.isAbsolute(r); };
  const mapPath = (p, op) => {
    if (typeof p !== 'string') return p;
    if (p.startsWith('@')) {
      const prefix = '@' + resourceName + '/';
      if (!p.startsWith(prefix)) throw err('EPERM', 'write to other resource ' + p);
      if (aliasWrites === 'enoent') { denied.push(op + ' ' + p); throw err('ENOENT', 'no such file (relative @ path) ' + p); }
      if (aliasWrites === 'eperm') { denied.push(op + ' ' + p); throw err('EPERM', 'alias write refused ' + p); }
      return path.join(resourceDir, p.slice(prefix.length));
    }
    const refused = absoluteWrites === 'all' || (absoluteWrites === 'outside-resource' && !inResource(p));
    if (refused) { denied.push(op + ' ' + p); throw err('EPERM', 'operation not permitted, ' + op + ' ' + p); }
    return p;
  };
  for (const op of ops) {
    const orig = fs[op];
    fs[op] = (a, ...rest) => {
      if (op === 'unlinkSync' && unlink === 'eperm') { denied.push(op + ' ' + a); throw err('EPERM', 'unlink refused ' + a); }
      if (op === 'renameSync') return orig(mapPath(a, op), mapPath(rest[0], op));
      return orig(mapPath(a, op), ...rest);
    };
  }
  return { denied };
}
module.exports.installFsPolicy = installFsPolicy;

// FXServer natives that bypass Node fs (SaveResourceFile / LoadResourceFile). Writes stay inside the resource folder.
function installNative({ resourceDir }) {
  const path = require('path');
  globalThis.SaveResourceFile = (resource, rel, data, length) => {
    const abs = path.join(resourceDir, rel);
    if (!realFs.existsSync(path.dirname(abs))) return false;
    realFs.writeFileSync(abs, String(data));
    return true;
  };
  globalThis.LoadResourceFile = (resource, rel) => {
    const abs = path.join(resourceDir, rel);
    return realFs.existsSync(abs) ? realFs.readFileSync(abs, 'utf8') : null;
  };
}
module.exports.installNative = installNative;
module.exports.removeNative = () => { delete globalThis.SaveResourceFile; delete globalThis.LoadResourceFile; };
