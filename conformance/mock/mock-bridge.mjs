#!/usr/bin/env node
// Reference mock bridge for self-testing the conformance suite. It implements the protocol in
// docs/protocol (Minecraft Java, Paper-like, fake data) over a unix socket or TCP+token.
//
//   node conformance/mock/mock-bridge.mjs --root <dir> [--tcp [--port N]] [--fast-timers]
//                                         [--moving] [--break <name>[,<name>...]]
//
// --break switches make the mock deliberately non-conformant so the suite can prove it detects
// the problem. See BREAKS below. Writes <root>/.playermanager/bridge.json (atomically) once
// listening, and <root>/.playermanager/token (0600) in tcp mode.

import net from 'node:net';
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync, renameSync, unlinkSync, existsSync, chmodSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { FrameReader, encodeFrame, FrameError } from '../lib/framing.mjs';
import { decode, num } from '../lib/msgpack.mjs';
import { canonicalBytes, hash16, fingerprintOf, renderSubsetOf, assetHash, manifestHash, sha256Hex } from '../lib/canonical.mjs';

// ------------------------------------------------------------------ options

const BREAKS = {
  'no-hello-check': 'ops are served before hello (unix and tcp)',
  'unix-close-prehello': 'unix: a pre-hello request closes the connection instead of invalid_args (D15)',
  'tcp-no-token-check': 'tcp: any token is accepted',
  'wrong-protocol': 'hello.protocol differs from bridge.json.protocol',
  'teleport-player-advertised': 'advertises teleport_player (D5)',
  'unsupported-advertised': 'positions.subscribe is advertised but answers unsupported',
  'unknown-op-ok': 'unknown ops answer ok {}',
  'no-busy': 'a second positions subscription on a connection is accepted',
  'bad-seq': 'positions seq skips a number after the first frame',
  'no-keyframe-key': 'positions keyframes omit key:true',
  'float-positions': 'positions x/y/z are non-integral floats',
  'no-ping': 'quiet subscriptions never receive the ping keep-alive',
  'accept-oversize': 'frame headers above 4 MiB are buffered, not rejected',
  'wrong-hash': 'item hash is computed over the wrong input',
  'wrong-fingerprint': 'item fingerprint includes the hash',
  'no-groups': 'players.list rows omit groups',
  'bad-groups': 'players.list groups contain an invalid name',
  'ip-always': 'players.list includes ip without include_sensitive',
  'wrong-manifest-hash': 'assets.manifest manifest_hash is constant garbage',
  'reversed-assets': 'assets.get returns results in reverse request order',
  'accept-hotbar-string': 'item.get accepts the "hotbar:0" slot form (D4)',
};

function parseArgs(argv) {
  const o = { root: null, tcp: false, port: 0, fast: false, moving: false, breaks: new Set() };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--root') o.root = argv[++i];
    else if (a === '--tcp') o.tcp = true;
    else if (a === '--port') o.port = Number(argv[++i]);
    else if (a === '--fast-timers') o.fast = true;
    else if (a === '--moving') o.moving = true;
    else if (a === '--break') for (const b of argv[++i].split(',')) o.breaks.add(b.trim());
    else if (a === '--list-breaks') {
      for (const [k, v] of Object.entries(BREAKS)) console.log(`${k}\t${v}`);
      process.exit(0);
    } else throw new Error(`unknown argument ${a}`);
  }
  if (!o.root) throw new Error('--root is required');
  for (const b of o.breaks) if (!(b in BREAKS)) throw new Error(`unknown --break ${b}`);
  return o;
}

const OPT = parseArgs(process.argv.slice(2));
const BRK = OPT.breaks;
const TIMERS = OPT.fast ? { ping: 1000, idle: 3000 } : { ping: 60000, idle: 120000 };
const ROOT = resolve(OPT.root);
const PM_DIR = join(ROOT, '.playermanager');
const SOCK_REL = '.playermanager/bridge.sock';
const SOCK_ABS = join(ROOT, SOCK_REL);
const MAX_INFLIGHT = 16;
const MAX_FRAME = 4194304;
const SUB_KEYFRAME_MS = 10000;

// ------------------------------------------------------------------ fake data

const NOW = Math.floor(Date.now() / 1000);
const PNG_1x1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

function finishItem(base) {
  const rec = { ...base };
  const sub = renderSubsetOf(rec);
  let hash = sub.verifiable ? hash16(sub.subset) : hash16({ id: rec.id, tint: rec.tint });
  if (BRK.has('wrong-hash')) hash = hash16({ id: rec.id, count: rec.count });
  rec.hash = hash;
  let fp = fingerprintOf(rec);
  if (BRK.has('wrong-fingerprint')) fp = sha256Hex(canonicalBytes({ ...rec, hash }));
  rec.fingerprint = fp;
  return rec;
}

const SWORD = finishItem({
  id: 'minecraft:diamond_sword',
  count: 1,
  name: { plain: 'Excalibur', json: { text: 'Excalibur', color: 'gold' } },
  lore: [{ plain: 'Forged in the Nether' }],
  damage: 12,
  max_damage: 1561,
  enchantments: [{ id: 'minecraft:sharpness', level: 5 }],
  encoding: 'components',
  components: { 'minecraft:custom_data': { owner: '069a79f4' }, 'minecraft:repair_cost': 7 },
});
const COBBLE = finishItem({ id: 'minecraft:cobblestone', count: 64 });
const SHULKER = finishItem({
  id: 'minecraft:shulker_box',
  count: 1,
  contents: [finishItem({ id: 'minecraft:cobblestone', count: 64, slot: 0 })],
});
const HELMET = finishItem({ id: 'minecraft:iron_helmet', count: 1 });
const STONE = finishItem({ id: 'minecraft:stone', count: 3 });
const LEATHER = finishItem({ id: 'minecraft:leather_chestplate', count: 1, tint: [10511680], components: { 'minecraft:dyed_color': { rgb: 10511680 } } });

const PLAYERS = [
  {
    id: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
    name: 'Notch',
    online: true,
    ping: 42,
    world: 'world',
    dimension: 'minecraft:overworld',
    pos: { x: 12.5, y: 64, z: -80.25, yaw: 90, pitch: 10 },
    groups: ['operators'],
    admin: true,
    afk_seconds: 0,
    joined_at: NOW - 100,
    client: { brand: 'vanilla', version: '1.21.4', locale: 'en_us' },
    health: 20,
    food: 20,
    inv: {
      inventory: { size: 36, slots: { 0: SWORD, 4: COBBLE, 9: SHULKER } },
      equipment: { size: 5, slots: { head: HELMET } },
      ender_chest: { size: 27, slots: {} },
    },
  },
  {
    id: 'b8d1a2c3-0000-4000-8000-000000000001',
    name: 'Alex',
    online: true,
    ping: 17,
    world: 'world',
    dimension: 'minecraft:the_nether',
    pos: { x: -30.25, y: 70, z: 20, yaw: -90, pitch: 0 },
    groups: [],
    admin: false,
    afk_seconds: 12,
    joined_at: NOW - 900,
    client: { brand: 'vanilla', version: '1.21.4', locale: 'de_de' },
    health: 14,
    food: 17,
    inv: {
      inventory: { size: 36, slots: { 0: STONE, 30: LEATHER } },
      equipment: { size: 5, slots: {} },
      ender_chest: { size: 27, slots: {} },
    },
  },
  {
    id: '853c80ef-3c37-49fd-aa49-938b674adae6',
    name: 'jeb_',
    online: false,
    groups: ['banned'],
    admin: false,
    last_seen: 1760200000,
    reason: 'Griefing',
  },
];

const ACTIONS = [
  { id: 'kick', label: 'kick', target: true, destructive: true, offline: false, fields: [{ key: 'reason', label: 'Reason', kind: 'text', choices: [], min: null, max: null }] },
  { id: 'set_health', label: 'set health', target: true, destructive: false, offline: false, fields: [{ key: 'amount', label: 'Amount', kind: 'number', choices: [], min: 0, max: 20 }] },
  {
    id: 'remove_item_live',
    label: 'remove item',
    target: true,
    destructive: true,
    offline: false,
    fields: [
      { key: 'container', label: 'Container', kind: 'text', choices: [], min: null, max: null },
      { key: 'slot', label: 'Slot', kind: 'integer', choices: [], min: 0, max: 35 },
      { key: 'expected_id', label: 'Expected id', kind: 'identifier', choices: [], min: null, max: null },
    ],
  },
  {
    id: 'teleport',
    label: 'teleport',
    target: true,
    destructive: false,
    offline: false,
    fields: [
      { key: 'x', label: 'X', kind: 'number', choices: [], min: -30000000, max: 30000000 },
      { key: 'y', label: 'Y', kind: 'number', choices: [], min: -2048, max: 2048 },
      { key: 'z', label: 'Z', kind: 'number', choices: [], min: -30000000, max: 30000000 },
      { key: 'to_player', label: 'To player', kind: 'target', choices: [], min: null, max: null },
    ],
  },
];
if (BRK.has('teleport-player-advertised')) {
  ACTIONS.push({ id: 'teleport_player', label: 'teleport player', target: true, destructive: false, offline: false, fields: [{ key: 'destination', label: 'Destination', kind: 'target', choices: [], min: null, max: null }] });
}

const OPS = ['hello', 'server.info', 'players.list', 'player.get', 'player.inventory', 'player.nearby', 'item.get', 'positions.subscribe', 'positions.keyframe', 'positions.unsubscribe', 'events.subscribe', 'events.unsubscribe', 'action.run', 'assets.manifest', 'assets.get'];
const FEATURES = ['positions.stream', 'inventory.write', 'offline_players', 'sensitive', 'assets'];
const EVENTS = ['join', 'leave', 'death', 'chat', 'command', 'advancement', 'damage', 'kick', 'ban'];
const PLAYER_FIELDS = ['position', 'health', 'max_health', 'armor', 'held_item', 'vehicle', 'game_mode', 'experience', 'food', 'game'];

const ASSET_ENTRIES = [
  { key: 'item:examplemod:ruby', render: 'static', png: PNG_1x1 },
  { key: 'item:examplemod:ruby_block', render: 'static', png: PNG_1x1 },
];
for (const a of ASSET_ENTRIES) {
  a.hash = assetHash({ render: 'static', textures: { icon: { png: a.png, width: 1, height: 1 } } });
}
const MANIFEST = ASSET_ENTRIES.map((a) => ({ key: a.key, hash: a.hash, render: a.render })).sort((x, y) => Buffer.compare(Buffer.from(x.key), Buffer.from(y.key)));
const MANIFEST_HASH = BRK.has('wrong-manifest-hash') ? '0000000000000000' : manifestHash(MANIFEST);

function helloData() {
  return {
    protocol: BRK.has('wrong-protocol') ? 2 : 1,
    game: 'minecraft_java',
    platform: 'paper',
    platform_version: '1.21.4-232',
    game_version: '1.21.4',
    bridge_version: '1.0.0',
    capabilities: {
      ops: OPS.filter((o) => o !== 'hello'),
      player_fields: PLAYER_FIELDS,
      events: EVENTS.slice(0, 6),
      actions: ACTIONS,
      features: FEATURES,
    },
    integrations: ['luckperms'],
    limits: { max_frame: MAX_FRAME, positions_min_interval_ms: 250, max_inflight: MAX_INFLIGHT },
  };
}

function serverInfoData() {
  const online = PLAYERS.filter((p) => p.online).length;
  return {
    name: 'Mock Survival',
    motd: 'Conformance mock',
    max_players: 100,
    online,
    performance: { tps: 19.98, mspt: 11.4 },
    uptime: 86400,
    worlds: [
      { world: 'world', dimension: 'minecraft:overworld', environment: 'normal', name: 'world', players: 1 },
      { world: 'world', dimension: 'minecraft:the_nether', environment: 'nether', name: 'world_nether', players: 1 },
    ],
    extensions: [{ name: 'LuckPerms', version: '5.4.150', kind: 'plugin' }],
  };
}

// ------------------------------------------------------------------ player views

function playerRow(p, { includeOffline, includeSensitive }) {
  if (!p.online && !includeOffline) return null;
  const row = { id: p.id, name: p.name, online: p.online };
  if (p.online) {
    row.ping = p.ping;
    row.world = p.world;
    row.dimension = p.dimension;
  }
  if (!BRK.has('no-groups')) row.groups = BRK.has('bad-groups') ? ['Bad Group!'] : p.groups;
  row.admin = p.admin;
  if (p.online) {
    row.afk_seconds = p.afk_seconds;
    row.joined_at = p.joined_at;
    row.client = p.client;
  } else {
    row.last_seen = p.last_seen;
    row.reason = p.reason;
  }
  if (includeSensitive && p.online) row.ip = '127.0.0.1';
  if (BRK.has('ip-always') && p.online && !includeSensitive) row.ip = '127.0.0.1';
  return row;
}

function containersOf(p) {
  const out = [];
  for (const [id, c] of Object.entries(p.inv)) {
    const slots = Object.entries(c.slots).map(([k, item]) => ({ slot: /^\d+$/.test(k) ? Number(k) : k, item }));
    out.push({ id, size: c.size, slots });
  }
  return out;
}

function slotKey(raw, container) {
  if (typeof raw === 'number' && Number.isInteger(raw)) return String(raw);
  if (typeof raw === 'string' && /^\d+$/.test(raw)) return String(Number(raw));
  if (typeof raw === 'string' && container === 'equipment' && /^[a-z]+$/.test(raw)) return raw;
  if (BRK.has('accept-hotbar-string') && typeof raw === 'string') {
    const m = /^hotbar:(\d+)$/.exec(raw);
    if (m) return String(Number(m[1]));
  }
  return null;
}

// ------------------------------------------------------------------ positions

function quant(p) {
  return {
    x: Math.round(p.pos.x * 16),
    y: Math.round(p.pos.y * 16),
    z: Math.round(p.pos.z * 16),
    yaw: Math.round(p.pos.yaw * 100),
    pitch: Math.round(p.pos.pitch * 100),
  };
}

function positionEntries(worlds) {
  return PLAYERS.filter((p) => p.online && (!worlds || worlds.includes(p.world))).map((p) => {
    const q = quant(p);
    if (BRK.has('float-positions')) {
      q.x = q.x / 16 + 0.001;
      q.z = q.z / 16 + 0.001;
    }
    return { id: p.id, name: p.name, world: p.world, dimension: p.dimension, ...q, state: 'alive' };
  });
}

// ------------------------------------------------------------------ connections

let TOKEN = null;
const TOKEN_FILE = join(PM_DIR, 'token');

function errorBody(code, message) {
  return { ok: false, error: { code, message } };
}

class Conn {
  constructor(socket) {
    this.socket = socket;
    this.authed = false;
    this.inflight = 0;
    this.pos = null; // { sub, interval, worlds, lastKeys, seq, lastSentAt, timer }
    this.ev = null; // { sub, types, seq, lastSentAt }
    this.nextSub = 1;
    this.lastActivity = Date.now();
    this.reader = new FrameReader({ maxFrame: BRK.has('accept-oversize') ? Infinity : MAX_FRAME });
    this.closed = false;
    this.maintenance = setInterval(() => this.tick(), 250);
  }

  send(obj) {
    if (this.closed) return;
    this.lastActivity = Date.now();
    try {
      this.socket.write(encodeFrame(obj));
    } catch (err) {
      console.error(`mock: cannot encode reply: ${err.message}`);
    }
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.maintenance);
    if (this.pos?.timer) clearInterval(this.pos.timer);
    if (this.pos?.keyTimer) clearInterval(this.pos.keyTimer);
    this.socket.destroy();
  }

  hasSub() {
    return Boolean(this.pos || this.ev);
  }

  tick() {
    const now = Date.now();
    for (const s of [this.pos, this.ev]) {
      if (!s || BRK.has('no-ping')) continue;
      if (now - s.lastSentAt >= TIMERS.ping) {
        const frame = { sub: s.sub, seq: s.seq++, data: { type: 'ping' } };
        s.lastSentAt = now;
        this.send(frame);
      }
    }
    if (!this.hasSub() && now - this.lastActivity >= TIMERS.idle) this.close();
  }

  onData(chunk) {
    let bodies;
    try {
      bodies = this.reader.push(chunk);
    } catch (err) {
      if (err instanceof FrameError) {
        this.close();
        return;
      }
      throw err;
    }
    for (const body of bodies) {
      let msg;
      try {
        msg = decode(body);
      } catch {
        this.close();
        return;
      }
      this.lastActivity = Date.now();
      this.handle(msg);
    }
  }

  handle(msg) {
    if (!msg || typeof msg !== 'object' || !('id' in msg) || typeof msg.op !== 'string') {
      this.close();
      return;
    }
    const id = num(msg.id);
    const op = msg.op;
    const args = msg.args && typeof msg.args === 'object' ? msg.args : {};
    if (!this.authed && op !== 'hello' && !BRK.has('no-hello-check')) {
      if (MODE === 'tcp') {
        this.send({ id, ...errorBody('unauthorized', 'hello required with token') });
        this.close();
      } else if (BRK.has('unix-close-prehello')) {
        this.close();
      } else {
        this.send({ id, ...errorBody('invalid_args', 'hello required') });
      }
      return;
    }
    if (op === 'hello') {
      this.onHello(id, args);
      return;
    }
    if (this.inflight >= MAX_INFLIGHT) {
      this.send({ id, ...errorBody('busy', 'too many requests in flight') });
      return;
    }
    this.inflight++;
    setTimeout(() => {
      this.inflight--;
      if (this.closed) return;
      const result = this.dispatch(op, args);
      if (result.close) {
        this.send({ id, ...result.body });
        this.close();
        return;
      }
      this.send({ id, ...result.body });
    }, Math.floor(Math.random() * 4));
  }

  onHello(id, args) {
    if (typeof args.panel_version !== 'string') {
      this.send({ id, ...errorBody('invalid_args', 'panel_version required') });
      return;
    }
    if (MODE === 'tcp' && !BRK.has('tcp-no-token-check') && args.token !== TOKEN) {
      this.send({ id, ...errorBody('unauthorized', 'bad token') });
      this.close();
      return;
    }
    this.authed = true;
    this.send({ id, ok: true, data: helloData() });
  }

  dispatch(op, args) {
    const ok = (data = {}) => ({ body: { ok: true, data } });
    const err = (code, message) => ({ body: errorBody(code, message) });
    const unknown = () => (BRK.has('unknown-op-ok') ? ok({}) : err('unsupported', `unknown op ${op}`));
    switch (op) {
      case 'server.info':
        return ok(serverInfoData());
      case 'players.list': {
        const rows = [];
        for (const p of PLAYERS) {
          const row = playerRow(p, { includeOffline: args.include_offline === true, includeSensitive: args.include_sensitive === true });
          if (row) rows.push(row);
        }
        rows.sort((a, b) => Number(a.online === false) - Number(b.online === false));
        return ok({ players: rows.slice(0, 5000) });
      }
      case 'player.get':
        return this.playerGet(args);
      case 'player.inventory': {
        const p = PLAYERS.find((x) => x.id === args.id);
        if (args.id === undefined) return err('invalid_args', 'id required');
        if (!p) return err('not_found', 'unknown player');
        if (!p.online) return err('offline', 'player is offline');
        return ok({ containers: containersOf(p) });
      }
      case 'player.nearby': {
        const p = PLAYERS.find((x) => x.id === args.id);
        if (typeof args.id !== 'string') return err('invalid_args', 'id required');
        const r = args.radius ?? 16;
        if (typeof r !== 'number' || r < 1 || r > 32) return err('invalid_args', 'radius must be 1..32');
        if (!p) return err('not_found', 'unknown player');
        if (!p.online) return err('offline', 'player is offline');
        const yaw = p.pos.yaw;
        const rad = (yaw * Math.PI) / 180;
        const dx = -Math.sin(rad);
        const dz = Math.cos(rad);
        return ok({
          held_item: SWORD,
          look: { yaw, pitch: 0, dx, dy: 0, dz },
          target: null,
          entities: [{ type: 'minecraft:zombie', id: 'b8d1a2c3-0000-4000-8000-0000000000aa', position: { x: 14.1, y: 64, z: -77 }, health: 20, distance: 4.1 }],
        });
      }
      case 'item.get': {
        const p = PLAYERS.find((x) => x.id === args.id);
        if (typeof args.id !== 'string' || typeof args.container !== 'string') return err('invalid_args', 'id and container required');
        if (!(typeof args.slot === 'number' || typeof args.slot === 'string')) return err('invalid_args', 'slot must be int or string');
        const key = slotKey(args.slot, args.container);
        if (key === null) return err('invalid_args', `slot ${JSON.stringify(args.slot)} is not valid`);
        if (!p) return err('not_found', 'unknown player');
        const c = p.inv?.[args.container];
        if (!c) return err('not_found', 'unknown container');
        let it = c.slots[key];
        if (!it) return err('not_found', 'empty slot');
        for (const idx of args.path ?? []) {
          it = it.contents?.[idx];
          if (!it) return err('not_found', 'no nested item');
        }
        const { slot, ...rest } = it;
        return ok({ item: rest });
      }
      case 'positions.subscribe': {
        if (BRK.has('unsupported-advertised')) return err('unsupported', 'not available');
        if (!Number.isInteger(args.interval_ms)) return err('invalid_args', 'interval_ms required');
        if (this.pos && !BRK.has('no-busy')) return err('busy', 'positions subscription already active');
        const sub = this.nextSub++;
        const interval = Math.max(250, Math.min(60000, args.interval_ms));
        this.pos = { sub, interval, worlds: args.worlds ?? null, seq: 0, lastSentAt: Date.now(), keys: new Map(), timer: null, keyTimer: null, skipped: false };
        this.pos.keyTimer = setInterval(() => this.keyframe(), SUB_KEYFRAME_MS);
        this.pos.timer = setInterval(() => this.deltaTick(), interval);
        setImmediate(() => this.keyframe());
        return ok({ sub });
      }
      case 'positions.keyframe': {
        if (!this.pos || this.pos.sub !== args.sub) return err('not_found', 'unknown sub');
        setImmediate(() => this.keyframe());
        return ok({});
      }
      case 'positions.unsubscribe': {
        if (!this.pos || this.pos.sub !== args.sub) return err('not_found', 'unknown sub');
        clearInterval(this.pos.timer);
        clearInterval(this.pos.keyTimer);
        this.pos = null;
        return ok({});
      }
      case 'events.subscribe': {
        if (!Array.isArray(args.types) || args.types.length === 0 || !args.types.every((t) => typeof t === 'string')) {
          return err('invalid_args', 'types must be a non-empty string array');
        }
        for (const t of args.types) {
          if (!EVENTS.includes(t)) return err('invalid_args', `unknown event type ${t}`);
          if (!EVENTS.slice(0, 6).includes(t)) return err('unsupported', `event ${t} not supported`);
        }
        if (this.ev) return err('busy', 'events subscription already active');
        const sub = this.nextSub++;
        this.ev = { sub, types: args.types, seq: 0, lastSentAt: Date.now() };
        return ok({ sub });
      }
      case 'events.unsubscribe': {
        if (!this.ev || this.ev.sub !== args.sub) return err('not_found', 'unknown sub');
        this.ev = null;
        return ok({});
      }
      case 'action.run':
        return this.actionRun(args);
      case 'assets.manifest':
        return this.assetsManifest(args);
      case 'assets.get':
        return this.assetsGet(args);
      default:
        return unknown();
    }
  }

  playerGet(args) {
    if (args.id === undefined) return { body: errorBody('invalid_args', 'id required') };
    if (args.fields !== undefined) {
      if (!Array.isArray(args.fields) || !args.fields.every((f) => PLAYER_FIELDS.includes(String(f).split('.')[0]))) {
        return { body: errorBody('invalid_args', 'unknown field') };
      }
    }
    const p = PLAYERS.find((x) => x.id === args.id);
    if (!p) return { body: errorBody('not_found', 'unknown player') };
    const want = (f) => !args.fields || args.fields.includes(f);
    const d = { id: p.id, name: p.name, online: p.online };
    if (!p.online) {
      return { body: { ok: true, data: { id: p.id, name: p.name, online: false, last_seen: p.last_seen } } };
    }
    d.world = p.world;
    d.dimension = p.dimension;
    if (want('position')) d.position = { ...p.pos };
    if (want('health')) d.health = p.health;
    if (want('max_health')) d.max_health = 20;
    if (want('armor')) d.armor = 12;
    if (want('held_item')) d.held_item = SWORD;
    if (want('vehicle')) d.vehicle = null;
    if (want('game_mode')) d.game_mode = 'survival';
    if (want('food')) d.food = p.food;
    if (want('experience')) d.experience = { level: 30, progress: 0.4, total: 1395 };
    if (args.include_sensitive === true) d.ip = '127.0.0.1';
    if (want('game')) d.game = { offline_uuid: false, air_ticks: 300, fire_ticks: -20, effects: [{ id: 'minecraft:speed', amplifier: 1, duration_ticks: 1200, ambient: false, particles: true }], groups: p.groups };
    if (args.fields) for (const k of Object.keys(d)) if (!['id', 'name', 'online', ...args.fields.map((f) => f.split('.')[0])].includes(k)) delete d[k];
    return { body: { ok: true, data: d } };
  }

  actionRun(args) {
    if (typeof args.action !== 'string') return { body: errorBody('invalid_args', 'action required') };
    const a = ACTIONS.find((x) => x.id === args.action);
    if (!a) return { body: errorBody('unsupported', `action ${args.action} not available`) };
    if (args.target !== undefined && (typeof args.target !== 'string' || /[@\s;]/.test(args.target))) {
      return { body: errorBody('invalid_args', 'target is not a player id') };
    }
    if (a.target && args.target === undefined) return { body: errorBody('invalid_args', 'target required') };
    const subArgs = args.args && typeof args.args === 'object' ? args.args : {};
    for (const f of a.fields) {
      if ((f.kind === 'number' || f.kind === 'integer') && subArgs[f.key] !== undefined && typeof subArgs[f.key] !== 'number') {
        return { body: errorBody('invalid_args', `${f.key} must be a number`) };
      }
    }
    if (a.target) {
      const p = PLAYERS.find((x) => x.id === args.target);
      if (!p) return { body: errorBody('not_found', 'unknown player') };
    }
    if (args.expect && typeof args.expect === 'object') {
      const p = PLAYERS.find((x) => x.id === args.target);
      const c = p.inv[args.expect.container];
      const it = c?.slots[slotKey(args.expect.slot, args.expect.container)];
      const mismatch = !it || (args.expect.id !== undefined && it.id !== args.expect.id) || (args.expect.count !== undefined && it.count !== args.expect.count) || (args.expect.fingerprint !== undefined && it.fingerprint !== args.expect.fingerprint);
      if (mismatch) return { body: errorBody('conflict', 'expected item changed') };
    }
    // The mock never modifies game state.
    return { body: { ok: true, data: { ok: false, message: 'mock: action recognised, not executed' } } };
  }

  assetsManifest(args) {
    if (args.limit !== undefined && (!Number.isInteger(args.limit) || args.limit < 1 || args.limit > 5000)) {
      return { body: errorBody('invalid_args', 'limit must be 1..5000') };
    }
    if (args.since_hash !== undefined && args.since_hash === MANIFEST_HASH) {
      return { body: { ok: true, data: { unchanged: true, manifest_hash: MANIFEST_HASH } } };
    }
    const limit = args.limit ?? 2000;
    let list = MANIFEST;
    if (Array.isArray(args.namespaces)) {
      list = MANIFEST.filter((e) => args.namespaces.includes(e.key.split(':')[1]));
    }
    let start = 0;
    if (args.cursor !== undefined) {
      start = list.findIndex((e) => e.key > args.cursor);
      if (start < 0) start = list.length;
    }
    const page = list.slice(start, start + limit).map((e) => ({ key: e.key, hash: e.hash, render: e.render }));
    const more = start + limit < list.length;
    const data = { entries: page, manifest_hash: MANIFEST_HASH };
    if (more) data.next = page[page.length - 1].key;
    return { body: { ok: true, data } };
  }

  assetsGet(args) {
    if (!Array.isArray(args.keys) || args.keys.length === 0) return { body: errorBody('invalid_args', 'keys required') };
    if (args.keys.length > 64) return { body: errorBody('invalid_args', 'at most 64 keys') };
    let out = args.keys.map((k) => {
      const e = ASSET_ENTRIES.find((x) => x.key === k);
      if (e) {
        return { key: k, hash: e.hash, render: 'static', textures: { icon: { png: e.png, width: 1, height: 1 } } };
      }
      if (k.startsWith('item:minecraft:')) {
        return { key: k, hash: '0000000000000000', render: 'unavailable', unavailable_reason: 'vanilla_assets_panel_side' };
      }
      return { key: k, hash: '0000000000000000', render: 'unavailable', unavailable_reason: 'not_found' };
    });
    if (BRK.has('reversed-assets')) out = out.reverse();
    return { body: { ok: true, data: { assets: out, more: false } } };
  }

  // ---------------------------------------------------------- stream sampling

  keyframe() {
    if (this.closed || !this.pos) return;
    const s = this.pos;
    const players = positionEntries(s.worlds);
    s.keys = new Map(players.map((p) => [p.id, JSON.stringify(p)]));
    const data = BRK.has('no-keyframe-key') ? { players } : { key: true, players };
    this.emitPos(data);
  }

  deltaTick() {
    if (this.closed || !this.pos) return;
    const s = this.pos;
    const players = positionEntries(s.worlds);
    const changed = [];
    for (const p of players) {
      const j = JSON.stringify(p);
      if (s.keys.get(p.id) !== j) {
        changed.push(p);
        s.keys.set(p.id, j);
      }
    }
    if (changed.length === 0) return;
    this.emitPos({ key: false, players: changed, gone: [] });
  }

  emitPos(data) {
    const s = this.pos;
    const frame = { sub: s.sub, seq: s.seq, data };
    s.seq += 1;
    if (BRK.has('bad-seq') && !s.skipped) {
      s.skipped = true;
      s.seq += 1;
    }
    s.lastSentAt = Date.now();
    this.send(frame);
  }
}

// ------------------------------------------------------------------ server

const MODE = OPT.tcp ? 'tcp' : 'unix';
mkdirSync(PM_DIR, { recursive: true });

if (MODE === 'tcp') {
  if (existsSync(TOKEN_FILE)) {
    TOKEN = readFileSync(TOKEN_FILE, 'utf8').trim();
  } else {
    TOKEN = randomBytes(32).toString('hex');
    writeFileSync(TOKEN_FILE, `${TOKEN}\n`, { mode: 0o600 });
    chmodSync(TOKEN_FILE, 0o600);
  }
}

const server = net.createServer((socket) => {
  const conn = new Conn(socket);
  socket.on('data', (chunk) => conn.onData(chunk));
  socket.on('error', () => conn.close());
  socket.on('close', () => conn.close());
});

function writeBridgeJson(port) {
  const body = {
    protocol: 1,
    transport: MODE,
    socket: MODE === 'unix' ? SOCK_REL : null,
    port: MODE === 'tcp' ? port : null,
    platform: 'paper',
    platform_version: '1.21.4-232',
    game: 'minecraft_java',
    game_version: '1.21.4',
    bridge_version: '1.0.0',
    pid: process.pid,
    started_at: NOW,
  };
  const tmp = join(PM_DIR, `bridge.json.${process.pid}.tmp`);
  writeFileSync(tmp, `${JSON.stringify(body, null, 2)}\n`);
  renameSync(tmp, join(PM_DIR, 'bridge.json'));
}

function shutdown() {
  server.close();
  if (MODE === 'unix' && existsSync(SOCK_ABS)) unlinkSync(SOCK_ABS);
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

if (MODE === 'unix') {
  if (existsSync(SOCK_ABS)) unlinkSync(SOCK_ABS);
  server.listen(SOCK_ABS, () => {
    writeBridgeJson(null);
    console.log(`mock-bridge: unix ${SOCK_REL} breaks=[${[...BRK].join(',')}]`);
  });
} else {
  server.listen(OPT.port, '127.0.0.1', () => {
    const port = server.address().port;
    writeBridgeJson(port);
    console.log(`mock-bridge: tcp 127.0.0.1:${port} breaks=[${[...BRK].join(',')}]`);
  });
}

