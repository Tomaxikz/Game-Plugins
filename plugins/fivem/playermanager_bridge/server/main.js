'use strict';
// FiveM / RedM server entry. Wires FXServer events to the bridge core. Never throws to the game:
// every boundary is wrapped, and any startup failure only disables the bridge.
const fs = require('fs');
const net = require('net');
const path = require('path');
const { createFiveMGame } = require('./lib/fivem-game');
const { BridgeCore } = require('./lib/bridge');
const { Transport } = require('./lib/transport');
const { loadConfig } = require('./lib/config');
const { paths, resolveServerRoot } = require('./lib/discovery');
const { chooseStorage } = require('./lib/storage');
const { E } = require('./lib/errors');
const { Offload } = require('./lib/offload');
const { BRIDGE_VERSION } = require('./version');

(function boot() {
  const resourceName = GetCurrentResourceName();
  const started = Date.now();
  let logFile = null;
  let fsView = fs;
  const emit = (msg) => {
    const line = new Date().toISOString() + ' ' + msg;
    try { console.log('[playermanager_bridge] ' + msg); } catch (e) { /* ignore */ }
    if (logFile) {
      try {
        const cur = fs.existsSync(logFile) ? fs.statSync(logFile).size : 0;
        if (cur > 5 * 1024 * 1024) fsView.renameSync(logFile, logFile + '.1');
        fsView.writeFileSync(logFile, line + '\n', { flag: 'a' });
      } catch (e) { /* log file is best effort */ }
    }
  };
  const log = (msg) => emit(msg);
  try {
    const resourcePath = GetResourcePath(resourceName);
    const resourceDir = path.resolve(String(resourcePath || process.cwd()));
    const root = resolveServerRoot(fs, { cwd: process.cwd(), resourcePath, override: process.env.PM_BRIDGE_ROOT || null });
    const gameName = String(typeof GetGameName === 'function' ? GetGameName() : 'gta5');
    const platform = gameName === 'rdr3' ? 'redm' : 'fivem';

    // Write strategy selection (lib/storage.js): plain fs at the server root, then the resource folder
    // (@alias, absolute), then the SaveResourceFile native. The first that verifiably writes wins.
    const chosen = chooseStorage({ g: globalThis, fs, root, resourceName, resourceDir, log: (m) => console.log('[playermanager_bridge] ' + m) });
    if (!chosen) {
      console.log('[playermanager_bridge] disabled: no write strategy worked');
      return;
    }
    const { dir, view, name: strategy, attempts } = chosen;
    const why = strategy === 'server-root' ? 'server root is writable' : 'server root not usable; strategy ' + strategy;
    fsView = view;
    logFile = path.join(dir, 'bridge.log');
    emit('write strategy ' + strategy + ' selected (attempts: ' + attempts.join('; ') + ')');
    const relDir = path.relative(root, dir).split(path.sep).join('/');
    const p = paths(dir);
    const socketAbs = p.socket;
    const forceTcp = Buffer.byteLength(socketAbs) >= 108;
    emit('location: ' + dir + ' (' + why + '); bridge.json dir=' + relDir + (forceTcp ? '; socket path too long, tcp fallback' : ''));
    const { config, source: configSource } = loadConfig(view, p.config, p.config);
    emit('config: ' + configSource);

    const game = createFiveMGame({ g: globalThis, fs: view, resourceName, platform, log });
    // All game reads/writes run on the game thread: requests arriving in the same tick share one setImmediate hop
    // (one drain pass). No deadline timer is armed per request; the deadline is checked when the hop runs, which is
    // the only point at which the work could start late.
    let queue = [];
    const drain = () => {
      const batch = queue; queue = [];
      const now = Date.now();
      game.beginBatch();
      try {
      for (const it of batch) {
        if (now - it.t0 > it.ms) { it.reject(E.timeout('game thread did not answer in time')); continue; }
        try { it.resolve(it.fn()); } catch (e) { it.reject(e); }
      }
      } finally { game.endBatch(); }
    };
    const onMain = (fn, ms) => new Promise((resolve, reject) => {
      if (queue.length === 0) setImmediate(drain);
      queue.push({ fn, ms, resolve, reject, t0: Date.now() });
    });

    const core = new BridgeCore({
      game, config, bridgeVersion: BRIDGE_VERSION, onMain, log,
      platformVersion: 'FXServer ' + String(GetConvar('version', '') || ''),
      getUptime: () => (Date.now() - started) / 1000,
      monoMs: () => Date.now(),
    });

    const info = {
      platform, platform_version: 'FXServer ' + String(GetConvar('version', '') || ''),
      game_version: game.world, bridge_version: BRIDGE_VERSION, pid: process.pid, started_at: Math.floor(Date.now() / 1000),
    };
    const transport = new Transport({ core, net, fs: view, config, paths: p, bridgeInfo: info, log,
      relDir, relSocket: relDir + '/bridge.sock', forceTcp,
      // Optional worker-thread encode offload (needs `add_unsafe_worker_permission <resource>` in server.cfg); lazy, falls back cleanly.
      offload: Number(typeof GetConvarInt === 'function' ? GetConvarInt('pm_bridge_offload', 1) : 1) ? new Offload({ file: path.join(resourceDir, 'server', 'lib', 'encode-worker.js'), log }) : null });
    transport.start().then((res) => {
      if (!res) emit('bridge disabled: no transport could be bound');
      else emit('listening (' + res.transport + ')');
    }).catch((e) => emit('start failed: ' + (e && e.message)));

    // ---- FXServer event wiring (games/fivem.md section 4, 3) ----
    const identityCache = new Map();
    const remember = (src) => { const id = game.identityOf(src); if (id) identityCache.set(src, id); return id; };
    const safeCall = (name, fn) => (...args) => { try { fn(...args); } catch (e) { log(name + ': ' + (e && e.message)); } };

    on('playerJoining', safeCall('join', () => {
      const src = Number(source);
      core.invalidateReads(null);
      if (core.eventSubs.size === 0) { game.forget(src); return; } // nobody listens: skip the identity reads
      const id = remember(src);
      if (id) core.emitEvent({ type: 'join', player: { id: id.id, name: id.name }, world: game.world });
    }));
    on('playerDropped', safeCall('leave', () => {
      const src = Number(source);
      const id = identityCache.get(src) || game.peekIdentity(src);
      identityCache.delete(src);
      core.vehicleState.delete(src);
      game.forget(src);
      core.invalidateReads(id ? id.id : null);
      if (id) core.emitEvent({ type: 'leave', player: { id: id.id, name: id.name }, reason: null, world: game.world });
    }));
    on('chatMessage', safeCall('chat', (...args) => {
      const src = typeof args[0] === 'number' && args[0] > 0 ? args[0] : Number(source);
      const message = args[args.length - 1];
      if (core.eventSubs.size === 0) return;
      if (typeof message !== 'string' || message.startsWith('/')) return;
      const id = identityCache.get(src) || remember(src);
      if (!id) return;
      core.emitEvent({ type: 'chat', player: { id: id.id, name: id.name }, message: message.slice(0, 512), channel: 'global', world: game.world });
    }));
    RegisterNetEvent('pm:server:ack');
    RegisterNetEvent('pm:server:state');
    onNet('pm:server:ack', safeCall('ack', (req, ok, error) => core.handleAck(Number(source), req, ok === true, error)));
    onNet('pm:server:state', safeCall('state', (payload) => core.handleVehicleState(Number(source), payload)));
    on('onResourceStop', safeCall('stop', (name) => { if (name === resourceName) transport.stop(); }));
  } catch (e) {
    console.log('[playermanager_bridge] disabled: ' + (e && e.stack ? e.stack : e));
  }
})();
