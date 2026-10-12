'use strict';
// Protocol core for FiveM/RedM (docs/protocol/operations.md, games/fivem.md). Transport-agnostic.
// All game access goes through `game` (fivem-game.js in production, a fake in tests) and runs via `onMain`.
const crypto = require('crypto');
const { E, BridgeError } = require('./errors');
const V = require('./validate');
const { Float } = require('./msgpack');
const { hashName, UNARMED } = require('./weapons');
const { canonicalBytes } = require('./canonical');

const MAX_FRAME = 4 * 1024 * 1024;
const MAX_INFLIGHT = 16;
const ACK_TIMEOUT_MS = 2000;
const KEYFRAME_EVERY_MS = 10000;
const PING_AFTER_MS = 60000;
const IDLE_CLOSE_MS = 120000;
const EVENT_QUEUE_CAP = 1024;
const EVENT_TYPES = ['join', 'leave', 'chat'];
const PLAYER_FIELDS_BASE = ['position', 'health', 'max_health', 'held_item', 'vehicle', 'ping', 'game'];
const GAME_KEYS = ['server_id', 'identifiers', 'routing_bucket', 'ped_model', 'vehicle', 'weapon', 'framework', 'inventory'];

const F = (key, label, kind, o = {}) => ({ key, label, kind, choices: o.choices || [], min: o.min ?? null, max: o.max ?? null });

const ACTION_SPECS = {
  kick: { label: 'Kick', destructive: true, fields: [F('reason', 'Reason', 'text')], needs: 'server' },
  message: { label: 'Message', destructive: false, fields: [F('message', 'Message', 'text')], needs: 'client' },
  teleport: { label: 'Teleport', destructive: false, fields: [
    F('to_player', 'To player', 'target'),
    F('x', 'X', 'number', { min: -20000, max: 20000 }), F('y', 'Y', 'number', { min: -20000, max: 20000 }),
    F('z', 'Z', 'number', { min: -2000, max: 5000 }), F('yaw', 'Yaw', 'number', { min: 0, max: 360 }),
    F('world', 'World', 'text'), F('routing_bucket', 'Routing bucket', 'integer', { min: 0, max: 2147483647 }),
  ], needs: 'client' },
  heal: { label: 'Heal', destructive: false, fields: [F('health', 'Health', 'integer', { min: 0, max: 1000 })], needs: 'client' },
  set_armour: { label: 'Set armour', destructive: false, fields: [F('amount', 'Armour', 'integer', { min: 0, max: 100 })], needs: 'client', gtaOnly: true },
  revive: { label: 'Revive', destructive: false, fields: [], needs: 'client' },
  give_item: { label: 'Give item', destructive: false, fields: [F('item', 'Item', 'item'), F('amount', 'Amount', 'integer', { min: 1, max: 10000 })], needs: 'server', oxOnly: true },
  remove_item: { label: 'Remove item', destructive: true, fields: [F('item', 'Item', 'item'), F('amount', 'Amount', 'integer', { min: 1, max: 10000 }),
    F('slot', 'Slot', 'integer', { min: 1, max: 1000 }), F('expected_fingerprint', 'Expected fingerprint', 'text')], needs: 'server', oxOnly: true },
  set_job: { label: 'Set job', destructive: false, fields: [F('job', 'Job', 'text'), F('grade', 'Grade', 'integer', { min: 0, max: 50 })], needs: 'server', frameworkOnly: true },
};

class BridgeCore {
  constructor({ game, config, version, bridgeVersion, onMain, log = () => {}, now = () => Math.floor(Date.now() / 1000), monoMs = () => Date.now(), getUptime = () => 0, platformVersion = 'FXServer' }) {
    this.game = game;
    this.config = config;
    this.bridgeVersion = bridgeVersion;
    this.onMain = onMain; // (fn, timeoutMs) => Promise
    this.log = log;
    this.now = now;
    this.monoMs = monoMs;
    this.getUptime = getUptime;
    this.platformVersion = platformVersion;
    this.caps = null;
    this.pendingAcks = new Map(); // req -> { source, resolve, timer }
    this.vehicleState = new Map(); // source -> { seat, health, speed, at }
    this.conns = new Set();
    this.positionSubs = new Set();
    this.eventSubs = new Set();
    this.nameCache = new Map(); // source -> identity for leave events
    this.lastEventAt = 0;
  }

  // ---- capabilities -------------------------------------------------------
  computeCaps() {
    const g = this.game;
    const cfg = this.config;
    if (!g.oneSyncEnabled()) {
      return { supported: false, reason: 'OneSync is required (convar onesync must be "on")', ops: ['hello'], player_fields: [], events: [], actions: [], features: [] };
    }
    const fw = g.detectFramework().name;
    const inv = g.inventorySource();
    const oxHere = inv === 'ox_inventory';
    const gta = g.world === 'gta5';
    const ops = ['hello', 'server.info', 'players.list', 'player.get', 'positions.subscribe', 'positions.keyframe',
      'positions.unsubscribe', 'events.subscribe', 'events.unsubscribe', 'action.run'];
    if (inv) ops.push('player.inventory', 'item.get');
    const assets = g.hasAssets();
    if (assets) ops.push('assets.manifest', 'assets.get');
    const features = ['positions.stream', 'sensitive'];
    if (oxHere) features.push('inventory.write');
    if (assets) features.push('assets');
    const playerFields = PLAYER_FIELDS_BASE.slice();
    if (gta) playerFields.splice(3, 0, 'armor');
    const actions = [];
    for (const [id, s] of Object.entries(ACTION_SPECS)) {
      if (s.gtaOnly && !gta) continue;
      if (s.oxOnly && !oxHere) continue;
      if (s.frameworkOnly && !['esx', 'qbcore', 'qbox'].includes(fw)) continue;
      if (id === 'revive' && (!cfg.platform.revive_event || fw === 'none')) continue;
      if (cfg.disabled_actions.includes(id)) continue;
      actions.push({ id, label: s.label, target: true, destructive: !!s.destructive, offline: false, fields: s.fields });
    }
    const opsFiltered = ops.filter((op) => op === 'hello' || cfg.enabled_ops.includes('*') || cfg.enabled_ops.includes(op));
    return {
      supported: true, reason: null,
      ops: opsFiltered,
      player_fields: playerFields,
      events: EVENT_TYPES.slice(),
      actions,
      features,
      framework: fw,
      inventory: inv,
    };
  }

  // ---- connection lifecycle ------------------------------------------------
  attach(conn) { this.conns.add(conn); }
  detach(conn) {
    this.conns.delete(conn);
    for (const s of [conn.positions, conn.events]) if (s) this.dropSub(s);
    conn.positions = null; conn.events = null;
  }
  dropSub(sub) {
    this.positionSubs.delete(sub);
    this.eventSubs.delete(sub);
  }

  // ---- dispatch ------------------------------------------------------------
  async handle(conn, req) {
    const op = req.op;
    const args = req.args && typeof req.args === 'object' && !Array.isArray(req.args) ? req.args : {};
    if (typeof op !== 'string') throw E.invalid('op must be a string');
    if (op === 'hello') return this.hello(conn, args);
    if (!conn.hello) throw E.invalid('hello required');
    if (!this.caps || !this.caps.supported || !this.caps.ops.includes(op)) throw E.unsupported('operation not supported: ' + op);
    switch (op) {
      case 'server.info': return this.main(() => this.serverInfo(), 2000);
      case 'players.list': return this.main(() => this.playersList(args), 2000);
      case 'player.get': return this.main(() => this.playerGet(args), 3000);
      case 'player.inventory': return this.main(() => this.playerInventory(args), 3000);
      case 'item.get': return this.main(() => this.itemGet(args), 3000);
      case 'positions.subscribe': return this.positionsSubscribe(conn, args);
      case 'positions.keyframe': return this.positionsKeyframe(conn, args);
      case 'positions.unsubscribe': return this.unsubscribe(conn, args, 'positions');
      case 'events.subscribe': return this.eventsSubscribe(conn, args);
      case 'events.unsubscribe': return this.unsubscribe(conn, args, 'events');
      case 'action.run': return this.actionRun(args);
      case 'assets.manifest': return this.main(() => this.assetsManifest(args), 3000);
      case 'assets.get': return this.main(() => this.assetsGet(args), 5000);
      default: throw E.unsupported('operation not supported: ' + op);
    }
  }

  main(fn, ms) { return this.onMain(fn, ms); }

  hello(conn, args) {
    V.reqString(args, 'panel_version', { max: 64, min: 1, allowEmpty: false });
    return this.main(() => {
      this.caps = this.computeCaps();
      const g = this.game;
      const base = {
        protocol: 1,
        game: 'fivem',
        platform: g.platform,
        platform_version: this.platformVersion,
        game_version: g.world,
        bridge_version: this.bridgeVersion,
        capabilities: {
          ops: this.caps.ops,
          player_fields: this.caps.player_fields,
          events: this.caps.events,
          actions: this.caps.actions,
          features: this.caps.features,
        },
        limits: { max_frame: MAX_FRAME, positions_min_interval_ms: this.config.positions_min_interval_ms, max_inflight: MAX_INFLIGHT },
      };
      if (!this.caps.supported) base.unsupported_reason = this.caps.reason;
      conn.hello = true;
      return base;
    }, 2000);
  }

  // ---- server.info -----------------------------------------------------------
  serverInfo() {
    const g = this.game;
    const info = g.serverInfo();
    const online = g.roster().length;
    return {
      name: info.name,
      max_players: info.max_players,
      online,
      uptime: Math.floor(this.getUptime()),
      worlds: [{ world: g.world, name: g.world }],
      extensions: info.resources,
      game: info.game,
    };
  }

  // ---- players ---------------------------------------------------------------
  playersList(args) {
    const sensitive = args.include_sensitive === true && this.caps.features.includes('sensitive');
    const rows = [];
    for (const p of this.game.roster()) {
      const r = this.game.readPlayer(p.source, { withVehicle: false });
      const row = { id: p.id, name: p.name, online: true, ping: r.ping ?? null, world: this.game.world, groups: [], game: { server_id: p.source } };
      if (sensitive) row.ip = this.game.endpointOf(p.source).replace(/:\d+$/, '');
      rows.push(row);
      if (rows.length >= 5000) break;
    }
    return { players: rows };
  }

  resolveOnline(id) {
    const p = this.game.roster().find((x) => x.id === id);
    return p || null;
  }

  parseFields(fields) {
    if (fields === undefined || fields === null) return { common: this.caps.player_fields.filter((f) => f !== 'game'), gameKeys: GAME_KEYS.slice() };
    if (!Array.isArray(fields) || fields.some((f) => typeof f !== 'string')) throw E.invalid('fields must be a string array');
    const common = new Set();
    let gameKeys = null;
    for (const f of fields) {
      if (f === 'game') { gameKeys = GAME_KEYS.slice(); continue; }
      if (f.startsWith('game.')) {
        const k = f.slice(5);
        if (!GAME_KEYS.includes(k)) throw E.invalid('unknown field ' + f);
        gameKeys = gameKeys || [];
        gameKeys.push(k);
        continue;
      }
      if (!this.caps.player_fields.includes(f)) throw E.invalid('unknown field ' + f);
      common.add(f);
    }
    return { common: [...common], gameKeys };
  }

  buildPlayer(id, args) {
    const g = this.game;
    const p = this.resolveOnline(id);
    if (!p) throw E.notFound('Player is not online');
    const sel = this.parseFields(args.fields);
    const sensitive = args.include_sensitive === true && this.caps.features.includes('sensitive');
    const r = g.readPlayer(p.source);
    const want = (k) => sel.common.includes(k);
    const out = { id: p.id, name: p.name, online: true, world: g.world };
    const weaponHash = r.weapon_hash ?? 0;
    const hasWeapon = !!r.ped && weaponHash !== 0 && weaponHash !== UNARMED;
    const vs = this.freshVehicleState(p.source);
    if (want('position')) out.position = r.coords ? { x: new Float(r.coords.x), y: new Float(r.coords.y), z: new Float(r.coords.z), yaw: new Float(r.heading) } : null;
    if (want('health')) out.health = r.ped ? new Float(r.health) : null;
    if (want('max_health')) out.max_health = r.ped ? new Float(r.max_health) : null;
    if (want('armor') && r.ped && g.world === 'gta5') out.armor = r.armour;
    if (want('held_item')) out.held_item = hasWeapon ? g.weaponRecord(weaponHash) : null;
    if (want('vehicle')) out.vehicle = r.vehicle ? { type: 'vehicle' } : null;
    if (want('ping')) out.ping = r.ping ?? null;
    if (sensitive) out.ip = g.endpointOf(p.source).replace(/:\d+$/, '');
    if (sel.gameKeys) {
      const fw = g.readFramework(p.source);
      const gm = {};
      const all = {
        server_id: p.source,
        identifiers: p.identifiers,
        routing_bucket: r.routing_bucket,
        ped_model: r.ped ? hashName(r.ped_model) : null,
        vehicle: r.vehicle ? {
          model: hashName(r.vehicle.model),
          plate: r.vehicle.plate || null,
          seat: vs ? vs.seat : null,
          speed: new Float(r.vehicle.speed),
          health: vs && vs.health !== null ? new Float(vs.health) : null,
        } : null,
        weapon: hasWeapon ? hashName(weaponHash) : null,
        framework: fw,
        inventory: null,
      };
      if (sel.gameKeys.includes('inventory') || sel.gameKeys.length === GAME_KEYS.length) {
        const inv = g.readInventory(p.source);
        all.inventory = inv ? { source: inv.source, weight: inv.weight, max_weight: inv.max_weight, slots: inv.slots, ...(inv.source === 'ox_inventory' ? { hotbar_slots: 5 } : {}) } : null;
      }
      for (const k of sel.gameKeys) gm[k] = all[k];
      out.game = gm;
    }
    return out;
  }

  playerGet(args) {
    const id = V.playerId(args.id);
    return this.buildPlayer(id, args);
  }

  freshVehicleState(source) {
    const s = this.vehicleState.get(source);
    if (!s) return null;
    if (this.monoMs() - s.at > 5000) return null;
    return s;
  }

  // ---- inventory -----------------------------------------------------------
  playerInventory(args) {
    const id = V.playerId(args.id);
    const p = this.resolveOnline(id);
    if (!p) throw E.notFound('Player is not online');
    const inv = this.game.readInventory(p.source);
    if (!inv) throw E.unsupported('no inventory source available');
    const slots = inv.items.slice().sort((a, b) => a.slot - b.slot).map((it) => ({ slot: it.slot, item: this.game.finishItem(it) }));
    const size = inv.source === 'ox_inventory' ? inv.slots : Math.max(0, ...inv.items.map((i) => i.slot));
    return { containers: [{ id: 'main', size, slots }] };
  }

  itemGet(args) {
    const id = V.playerId(args.id);
    const p = this.resolveOnline(id);
    if (!p) throw E.notFound('Player is not online');
    if (args.container !== 'main') throw E.notFound('unknown container');
    if (args.path !== undefined && !(Array.isArray(args.path) && args.path.length === 0)) throw E.unsupported('nested items are not exposed on FiveM');
    const slot = V.slotKey(args.slot);
    const it = this.game.slotItem(p.source, slot);
    if (!it) throw E.notFound('empty slot');
    return { item: this.game.finishItem(it) };
  }

  // ---- positions stream ------------------------------------------------------
  positionsSubscribe(conn, args) {
    if (!this.caps.ops.includes('positions.subscribe')) throw E.unsupported('positions not supported');
    if (conn.positions) throw E.busy('connection already has a positions subscription');
    const interval = V.requireNumber(args, 'interval_ms', 1, 60000);
    const floor = this.config.positions_min_interval_ms;
    let worlds = null;
    if (args.worlds !== undefined) {
      if (!Array.isArray(args.worlds) || args.worlds.some((w) => typeof w !== 'string')) throw E.invalid('worlds must be a string array');
      worlds = new Set(args.worlds);
    }
    const sub = { id: conn.nextSub(), kind: 'positions', conn, interval: Math.max(floor, Math.round(interval)), worlds, last: new Map(), seq: 0, needKey: true, lastKeyAt: 0, lastFrameAt: this.monoMs(), timer: 0 };
    conn.positions = sub;
    this.positionSubs.add(sub);
    this.ensureSampler();
    return { sub: sub.id };
  }

  positionsKeyframe(conn, args) {
    const sub = this.ownSub(conn, args.sub, 'positions');
    sub.needKey = true;
    return {};
  }

  ownSub(conn, subId, kind) {
    const sub = kind === 'positions' ? conn.positions : conn.events;
    if (!sub || sub.id !== subId) throw E.notFound('unknown subscription');
    return sub;
  }

  unsubscribe(conn, args, kind) {
    const subId = V.optionalNumber(args, 'sub', 0, 2 ** 32 - 1, { integer: true });
    if (subId === undefined) throw E.invalid('sub is required');
    const sub = this.ownSub(conn, subId, kind);
    this.dropSub(sub);
    if (kind === 'positions') conn.positions = null; else conn.events = null;
    this.maybeStopSampler();
    return {};
  }

  eventsSubscribe(conn, args) {
    if (conn.events) throw E.busy('connection already has an events subscription');
    if (!Array.isArray(args.types)) throw E.invalid('types is required');
    if (args.types.length === 0) throw E.invalid('types must not be empty');
    for (const t of args.types) if (typeof t !== 'string') throw E.invalid('types must be strings');
    for (const t of args.types) if (!this.caps.events.includes(t)) throw E.unsupported('event type not supported: ' + t);
    const sub = { id: conn.nextSub(), kind: 'events', conn, types: new Set(args.types), queue: [], dropped: 0, seq: 0, lastFrameAt: this.monoMs(), flushing: false };
    conn.events = sub;
    this.eventSubs.add(sub);
    return { sub: sub.id };
  }

  // Sampler: one timer for all position subscribers; one main-thread read per tick.
  ensureSampler() {
    if (this.samplerTimer) return;
    const period = this.config.positions_min_interval_ms;
    this.samplerTimer = setInterval(() => { this.sampleTick().catch((e) => this.log('sampler: ' + (e && e.message))); }, period);
    if (this.samplerTimer && this.samplerTimer.unref) this.samplerTimer.unref();
  }
  maybeStopSampler() {
    if (this.positionSubs.size === 0 && this.samplerTimer) { clearInterval(this.samplerTimer); this.samplerTimer = null; }
  }

  async sampleTick() {
    if (this.positionSubs.size === 0) return;
    const raw = await this.main(() => this.game.positionSamples(), 2000).catch(() => null);
    if (!raw) return;
    const now = this.monoMs();
    const entries = raw.map((p) => this.quantise(p));
    for (const sub of [...this.positionSubs]) {
      try { this.emitPositions(sub, entries, now); } catch (e) { this.log('positions frame: ' + (e && e.message)); }
    }
  }

  quantise(p) {
    const c = p.coords;
    const h = (((Number(p.heading) || 0) % 360) + 360) % 360;
    return {
      id: p.id, name: p.name, world: this.game.world,
      x: c ? Math.round(c.x * 16) : 0, y: c ? Math.round(c.y * 16) : 0, z: c ? Math.round(c.z * 16) : 0,
      yaw: Math.round(h * 100),
      state: p.health > 0 ? 'alive' : 'dead',
    };
  }

  emitPositions(sub, entries, now) {
    const scoped = entries.filter((e) => !sub.worlds || sub.worlds.has(e.world));
    const keyFrame = sub.needKey || now - sub.lastKeyAt >= KEYFRAME_EVERY_MS;
    const players = [];
    const seen = new Set();
    for (const e of scoped) {
      seen.add(e.id);
      const prev = sub.last.get(e.id);
      const sig = `${e.world}|${e.x}|${e.y}|${e.z}|${e.yaw}|${e.state}|${e.name}`;
      if (keyFrame || !prev || prev !== sig) { players.push(this.pub(e)); }
      sub.last.set(e.id, sig);
    }
    const gone = [];
    if (!keyFrame) {
      for (const id of [...sub.last.keys()]) if (!seen.has(id)) { gone.push(id); sub.last.delete(id); }
    } else {
      sub.last = new Map(scoped.map((e) => [e.id, `${e.world}|${e.x}|${e.y}|${e.z}|${e.yaw}|${e.state}|${e.name}`]));
    }
    if (keyFrame) { sub.needKey = false; sub.lastKeyAt = now; }
    if (!keyFrame && players.length === 0 && gone.length === 0) {
      if (now - sub.lastFrameAt >= PING_AFTER_MS) this.sendSub(sub, { type: 'ping' }, now);
      return;
    }
    const data = { key: keyFrame, players };
    if (!keyFrame) data.gone = gone;
    this.sendSub(sub, data, now);
  }

  pub(e) {
    return { id: e.id, name: e.name, world: e.world, x: e.x, y: e.y, z: e.z, yaw: e.yaw, state: e.state };
  }

  sendSub(sub, data, now) {
    const seq = sub.seq++;
    sub.lastFrameAt = now;
    sub.conn.sendRaw({ sub: sub.id, seq, data });
  }

  // ---- events ---------------------------------------------------------------
  // Called by the FiveM event listeners (main thread): enqueue only, never block.
  emitEvent(ev) {
    for (const sub of this.eventSubs) {
      if (!sub.types.has(ev.type)) continue;
      if (sub.queue.length >= EVENT_QUEUE_CAP) { sub.queue.shift(); sub.dropped++; }
      sub.queue.push({ ...ev, time: ev.time ?? this.now() });
      this.scheduleFlush(sub);
    }
  }

  scheduleFlush(sub) {
    if (sub.flushing) return;
    sub.flushing = true;
    setImmediate(() => {
      sub.flushing = false;
      if (!this.eventSubs.has(sub)) return;
      if (sub.dropped) { const n = sub.dropped; sub.dropped = 0; this.sendEvent(sub, { type: 'overflow', time: this.now(), dropped: n }); }
      while (sub.queue.length) this.sendEvent(sub, sub.queue.shift());
    });
  }

  sendEvent(sub, data) {
    const seq = sub.seq++;
    sub.lastFrameAt = this.monoMs();
    sub.conn.sendRaw({ sub: sub.id, seq, data });
  }

  // Client-ack bookkeeping. `source` is the FiveM event source of pm:server:ack.
  handleAck(source, req, ok, error) {
    const p = this.pendingAcks.get(req);
    if (!p || p.source !== source) return; // not for this source: discard
    this.pendingAcks.delete(req);
    clearTimeout(p.timer);
    p.resolve({ ok: ok === true, error: typeof error === 'string' ? error.slice(0, 200) : null });
  }

  // Handles pm:server:state. Rate limited; values range-checked.
  handleVehicleState(source, payload) {
    if (!payload || typeof payload !== 'object') return;
    const now = this.monoMs();
    const prev = this.vehicleState.get(source);
    if (prev && now - prev.recvAt < 250) return;
    if (payload.seat === null || payload.seat === undefined) { this.vehicleState.delete(source); return; }
    const seat = Number(payload.seat);
    const health = payload.vehicle_health === null || payload.vehicle_health === undefined ? null : Number(payload.vehicle_health);
    if (!Number.isInteger(seat) || seat < -1 || seat > 15) return;
    if (health !== null && (!Number.isFinite(health) || health < 0 || health > 1000)) return;
    this.vehicleState.set(source, { seat, health, speed: null, at: now, recvAt: now });
  }

  // Sends a client request and waits for the matching ack from the same source.
  clientRequest(source, event, payload) {
    let req;
    do { req = crypto.randomBytes(4).readUInt32BE(0) || 1; } while (this.pendingAcks.has(req));
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pendingAcks.delete(req);
        resolve({ timeout: true });
      }, ACK_TIMEOUT_MS);
      this.pendingAcks.set(req, { source, resolve, timer });
      this.main(() => this.game.emitClient(event, source, { req, ...payload }), 1000).catch((e) => {
        clearTimeout(timer);
        this.pendingAcks.delete(req);
        resolve({ ok: false, error: 'delivery failed: ' + (e && e.message) });
      });
    });
  }

  // ---- actions ---------------------------------------------------------------
  specFor(id) {
    if (!this.caps || !this.caps.supported) return null;
    return this.caps.actions.find((a) => a.id === id) || null;
  }

  validateFields(spec, a) {
    const out = {};
    for (const f of spec.fields) {
      const v = a[f.key];
      if (v === undefined || v === null) continue;
      switch (f.kind) {
        case 'text': {
          const s = V.reqString(a, f.key, { max: 256 });
          if (/^[@/]/.test(s)) throw E.invalid(f.key + ' must not start with @ or /');
          out[f.key] = s; break;
        }
        case 'integer': out[f.key] = V.optionalNumber(a, f.key, f.min, f.max, { integer: true }); break;
        case 'number': out[f.key] = V.optionalNumber(a, f.key, f.min, f.max); break;
        case 'item': out[f.key] = V.itemName(v); break;
        case 'target': out[f.key] = V.playerId(v); break;
        default: throw E.invalid('unsupported field kind');
      }
    }
    return out;
  }

  async actionRun(args) {
    const actionId = V.reqString(args, 'action', { max: 64, allowEmpty: false });
    const spec = this.specFor(actionId);
    if (!spec) throw E.unsupported('action not supported: ' + actionId);
    const def = ACTION_SPECS[actionId];
    let a = args.args === undefined || args.args === null ? {} : args.args;
    if (typeof a !== 'object' || Array.isArray(a)) throw E.invalid('args must be a map');
    const v = this.validateFields(spec, a);
    let target = null;
    if (spec.target) {
      const tid = V.playerId(args.target);
      target = await this.main(() => this.resolveOnline(tid), 2000);
      if (!target) throw E.offline('target is not online');
    }
    const r = await this.runAction(actionId, def, target, v, args, a);
    return r;
  }

  async runAction(id, def, target, v, args, raw) {
    const g = this.game;
    switch (id) {
      case 'kick': {
        const reason = v.reason || 'Kicked by server administrator';
        await this.main(() => g.dropPlayer(target.source, reason), 2000);
        return { ok: true, done: true, result: { done: true } };
      }
      case 'message': {
        const text = v.message;
        if (!text) throw E.invalid('message is required');
        return this.clientAction(target, 'pm:client:notify', { text });
      }
      case 'teleport': return this.teleport(target, v, args);
      case 'heal': {
        const max = await this.main(() => g.readPlayer(target.source, { withVehicle: false }).max_health || 200, 2000);
        const health = v.health === undefined ? max : v.health;
        return this.clientAction(target, 'pm:client:heal', { health });
      }
      case 'set_armour': return this.clientAction(target, 'pm:client:set_armour', { amount: v.amount });
      case 'revive': {
        const ev = this.config.platform.revive_event;
        return this.clientAction(target, 'pm:client:revive', { event: ev });
      }
      case 'give_item': return this.giveItem(target, v, raw);
      case 'remove_item': return this.removeItem(target, v, raw);
      case 'set_job': {
        const fw = this.caps.framework;
        const grade = v.grade === undefined ? 0 : v.grade;
        if (!v.job) throw E.invalid('job is required');
        const ok = await this.main(() => g.setJob(target.source, fw, v.job, grade), 3000);
        if (!ok) return { ok: false, message: 'framework refused the job', done: false };
        return { ok: true, done: true, result: { done: true } };
      }
      default: throw E.unsupported('action not supported: ' + id);
    }
  }

  async clientAction(target, event, payload) {
    const r = await this.clientRequest(target.source, event, payload);
    if (r.timeout) throw E.timeout('client did not acknowledge within 2 s');
    if (!r.ok) throw E.internal(r.error || 'client reported failure');
    return { ok: true, done: true, result: { done: true } };
  }

  async teleport(target, v, args) {
    const g = this.game;
    const hasTo = v.to_player !== undefined;
    const hasXyz = v.x !== undefined || v.y !== undefined || v.z !== undefined;
    if (hasTo && hasXyz) throw E.invalid('use either to_player or x/y/z');
    if (!hasTo && (v.x === undefined || v.y === undefined || v.z === undefined)) throw E.invalid('x, y and z are required');
    if (v.world !== undefined && v.world !== g.world) throw E.notFound('unknown world');
    let dest;
    if (hasTo) {
      const dp = await this.main(() => this.resolveOnline(v.to_player), 2000);
      if (!dp) throw E.offline('destination player is not online');
      const r = await this.main(() => g.readPlayer(dp.source, { withVehicle: false }), 2000);
      if (!r.coords) throw E.notFound('destination player has no position');
      dest = { x: r.coords.x, y: r.coords.y, z: r.coords.z, heading: r.heading };
    } else {
      dest = { x: v.x, y: v.y, z: v.z, heading: v.yaw ?? 0 };
    }
    if (v.routing_bucket !== undefined) await this.main(() => g.setRoutingBucket(target.source, v.routing_bucket), 2000);
    const res = await this.clientAction(target, 'pm:client:teleport', { x: dest.x, y: dest.y, z: dest.z, heading: dest.heading });
    return { ...res, result: { world: g.world, x: dest.x, y: dest.y, z: dest.z } };
  }

  async giveItem(target, v, raw) {
    const g = this.game;
    if (!v.item) throw E.invalid('item is required');
    const metadata = checkMetadata(raw && raw.metadata);
    const exists = await this.main(() => g.itemExists(v.item), 2000);
    if (!exists) throw E.notFound('unknown item');
    const count = v.amount === undefined ? 1 : v.amount;
    const r = await this.main(() => g.addItem(target.source, v.item, count, metadata), 3000);
    if (!r.ok) return { ok: false, message: r.reason || 'item could not be added', done: false };
    return { ok: true, done: true, result: { given: count } };
  }

  async removeItem(target, v, raw) {
    const g = this.game;
    if (!v.item) throw E.invalid('item is required');
    const amount = v.amount === undefined ? 1 : v.amount;
    const expectSlot = v.slot;
    const expFp = typeof raw.expected_fingerprint === 'string' ? raw.expected_fingerprint : v.expected_fingerprint;
    const expect = raw && raw.expect;
    return this.main(() => {
      const have = g.itemCount(target.source, v.item);
      if (have < amount) throw E.conflict('item shortfall');
      let slot;
      if (expectSlot !== undefined || expFp !== undefined || (expect && expect.slot !== undefined)) {
        slot = expectSlot !== undefined ? V.slotKey(expectSlot) : V.slotKey(expect.slot);
        const cur = g.slotItem(target.source, slot);
        if (!cur || cur.name !== v.item || cur.count < amount) throw E.conflict('slot changed');
        if (expFp !== undefined) {
          const rec = g.finishItem(cur);
          if (rec.fingerprint !== expFp) throw E.conflict('fingerprint mismatch');
        }
        if (expect && expect.count !== undefined && cur.count !== expect.count) throw E.conflict('count mismatch');
        if (expect && expect.id !== undefined && cur.name !== expect.id) throw E.conflict('id mismatch');
      }
      const ok = g.removeItem(target.source, v.item, amount, slot);
      if (!ok) return { ok: false, message: 'item could not be removed', done: false };
      return { ok: true, done: true, result: { removed: amount } };
    }, 3000);
  }

  // ---- assets (ox_inventory images only) -------------------------------------
  assetsManifest(args) {
    const names = this.game.listImages();
    const limit = Math.min(5000, Math.max(1, args.limit === undefined ? 2000 : Number(args.limit) || 2000));
    const after = typeof args.cursor === 'string' ? args.cursor : null;
    const keys = names.map((n) => 'item:' + n.replace(/\.png$/, '')).sort();
    const start = after ? keys.findIndex((k) => k > after) : 0;
    const from = start < 0 ? keys.length : start;
    const page = keys.slice(from, from + limit);
    const entries = page.map((key) => {
      const img = this.game.readImage(key.slice(5) + '.png');
      const hash = img ? assetHash(img) : '0000000000000000';
      return { key, hash, render: 'static' };
    });
    const next = from + limit < keys.length ? page[page.length - 1] : null;
    return next ? { entries, next, manifest_hash: this.manifestHash(keys) } : { entries, manifest_hash: this.manifestHash(keys) };
  }

  // First 16 hex of SHA-256 over canonical msgpack of the sorted [key, hash] list (full listing).
  manifestHash(keys) {
    const pairs = keys.map((k) => { const img = this.game.readImage(k.slice(5) + ".png"); return [k, img ? assetHash(img) : "0000000000000000"]; });
    return crypto.createHash("sha256").update(canonicalBytes(pairs)).digest("hex").slice(0, 16);
  }

  assetsGet(args) {
    if (!Array.isArray(args.keys) || args.keys.some((k) => typeof k !== 'string')) throw E.invalid('keys must be a string array');
    if (args.keys.length > 64) throw E.invalid('at most 64 keys');
    const out = [];
    let bytes = 0;
    let more = false;
    let cursor = null;
    for (let i = 0; i < args.keys.length; i++) {
      const key = args.keys[i];
      const m = /^item:([A-Za-z0-9_]{1,64})$/.exec(key);
      const img = m ? this.game.readImage(m[1] + '.png') : null;
      if (!img) { out.push({ key, hash: '0000000000000000', render: 'unavailable', unavailable_reason: 'not_found' }); continue; }
      const dim = pngSize(img);
      const asset = { key, hash: assetHash(img), render: 'static', textures: { icon: { png: new Uint8Array(img), width: dim.width, height: dim.height } } };
      bytes += img.length;
      if (bytes > 3.5 * 1024 * 1024 && out.length > 0) { more = true; cursor = args.keys[i]; break; }
      out.push(asset);
    }
    return cursor ? { assets: out, more, cursor } : { assets: out, more };
  }
}

// Optional item metadata: a small map of scalar values only.
function checkMetadata(m) {
  if (m === undefined || m === null) return undefined;
  if (typeof m !== 'object' || Array.isArray(m)) throw E.invalid('metadata must be a map');
  const keys = Object.keys(m);
  if (keys.length > 16) throw E.invalid('metadata has too many keys');
  for (const k of keys) {
    const v = m[k];
    if (!/^[A-Za-z0-9_]{1,32}$/.test(k)) throw E.invalid('metadata key invalid');
    if (typeof v === 'string') { if (v.length > 128 || V.hasControl(v)) throw E.invalid('metadata value invalid'); }
    else if (typeof v === 'number') { if (!Number.isFinite(v)) throw E.invalid('metadata value invalid'); }
    else if (typeof v !== 'boolean') throw E.invalid('metadata value invalid');
  }
  return m;
}

function pngSize(buf) {
  if (buf.length >= 24 && buf.readUInt32BE(0) === 0x89504e47) return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  return { width: 0, height: 0 };
}

// Asset hash: first 16 hex of SHA-256 over canonical msgpack of {render, textures:{icon:{sha,width,height}}}.
function assetHash(img) {
  const d = pngSize(img);
  const sha = crypto.createHash('sha256').update(img).digest('hex');
  const bytes = canonicalBytes({ render: 'static', textures: { icon: { sha, width: d.width, height: d.height } } });
  return crypto.createHash('sha256').update(bytes).digest('hex').slice(0, 16);
}

module.exports = { BridgeCore, ACTION_SPECS, MAX_FRAME, MAX_INFLIGHT, IDLE_CLOSE_MS, PING_AFTER_MS, assetHash, pngSize, BridgeError };
