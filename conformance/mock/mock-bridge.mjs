#!/usr/bin/env node
// Reference mock bridge for self-testing the conformance suite. It implements the protocol in
// docs/protocol (Minecraft Java, Paper-like, fake data) over a unix socket or TCP+token.
//
//   node conformance/mock/mock-bridge.mjs --root <dir> [--tcp [--port N]] [--fast-timers]
//                                         [--moving] [--chunk-churn] [--no-optional-ops] [--busy-ms N]
//                                         [--break <name>[,<name>...]]
//
// --break switches make the mock deliberately non-conformant so the suite can prove it detects
// the problem. See BREAKS below. Writes <root>/.playermanager/bridge.json (atomically) once
// listening, and <root>/.playermanager/token (0600) in tcp mode.

import net from 'node:net';
import { randomBytes } from 'node:crypto';
import { crc32 } from 'node:zlib';
import { mkdirSync, writeFileSync, renameSync, unlinkSync, existsSync, chmodSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { FrameReader, encodeFrame, FrameError } from '../lib/framing.mjs';
import { decode, num } from '../lib/msgpack.mjs';
import { buildSurface, surfaceHash, packU16LE, MAX_CHUNKS_FRAME, COOLDOWN_MS } from '../lib/chunks.mjs';
import { canonicalBytes, hash16, fingerprintOf, renderSubsetOf, assetHash, manifestHash, sha256Hex } from '../lib/canonical.mjs';
import { normaliseState, bytewise } from '../lib/volume.mjs';
import { blocksHash } from '../lib/registry.mjs';
import { fileHash, listingHash, packPathError, PACK_TOO_LARGE, PACK_MAX_PATHS } from '../lib/pack.mjs';

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
  'common-key-in-game': 'player.get repeats position/health inside game (D1)',
  'xp-keys-in-game': 'player.get game carries xp_level (D11)',
  'inventory-in-player-get': 'player.get carries an inventory key',
  'slot-on-top-level': 'player.inventory records carry slot at top level',
  'address-present': 'players.list rows carry an address key (D8)',
  'skip-render-components': 'render hash ignores render_components (dyed leather)',
  'mainhand-slot': 'equipment container lists a mainhand slot',
  'max-connections-4': 'bridge refuses connections beyond 4 (config max_connections below the 8 minimum)',
  'bad-block-data': 'player.nearby blocks.data is one byte short',
  'block-index-oob': 'player.nearby blocks indices exceed the palette',
  'chunk-bad-length': 'ChunkSurface top is 510 bytes instead of 512',
  'chunk-wrong-hash': 'ChunkSurface hash is computed without the palette',
  'chunk-initial-dump': 'chunks.subscribe sends every loaded chunk right after the ack',
  'chunk-no-coalesce': 'chunks.subscribe sends every chunk a second time 500 ms after the first frame',
  'chunk-oversize-frame': 'chunks.subscribe frames carry all ready chunks (more than 64), no load mode',
  'chunk-missing-wrong': 'map.chunks answers unloaded (far) chunks as if they were loaded',
  'move-no-swap': 'move_item(_live) onto an occupied slot is always a conflict (no stack, no swap)',
  'move-overstack': 'move_item(_live) stacks past the max stack size (64)',
  'move-armor-unchecked': 'move_item(_live) puts any item into armor slots',
  'move-stale-ok': 'move_item(_live) ignores destination_expected_id and the destination_expected_* checks',
  'chunk-waterlogged-as-water': 'waterlogged blocks are reported as water (water_depth 1 under a non-water top)',
  'feature-biomes-missing': 'biomes.list is advertised but the biomes feature is not',
  'feature-pack-half': 'pack.manifest and the pack feature are advertised without pack.get',
  'volume-air-in-palette': 'map.volume writes air as a palette entry (minecraft:air) instead of index 65535',
  'volume-wrong-band': 'map.volume y0 is one level lower than max(lowest - 4, min Y, highest - 159)',
  'volume-unsorted-state': 'map.volume palette strings list properties in source order (not sorted by key)',
  'volume-bad-length': 'map.volume blocks is 2 bytes short',
  'volume-biome-mismatch': 'map.volume reports another biome than map.chunks for column 0',
  'volume-dup-answer': 'map.volume answers a chunk requested twice twice',
  'volume-lose-on-page': 'map.volume drops the first chunk of every page after the first',
  'volume-ignore-y': 'map.volume ignores y_min and y_max',
  'volume-too-many-ok': 'map.volume accepts up to 256 chunks',
  'volume-missing-wrong': 'map.volume answers unloaded (far) chunks as if they were loaded',
  'volume-surface-air': 'map.volume writes air (65535) at the surface block of column 0',
  'volume-bad-y-order': 'map.volume accepts y_min > y_max',
  'biomes-unsorted': 'biomes.list is sorted in descending order',
  'biomes-bad-color': 'biomes.list sends water_color with an alpha byte (not 24-bit)',
  'biomes-bad-modifier': 'biomes.list sends grass_color_modifier tropical',
  'biomes-no-plains': 'biomes.list leaves out minecraft:plains',
  'biomes-missing-chunk-biome': 'biomes.list leaves out minecraft:river, which map.chunks reports',
  'blocks-wrong-hash': 'blocks.list hash is computed without the first entry',
  'blocks-unsorted': 'blocks.list swaps two entries (the hash covers the order as sent)',
  'blocks-state-same': 'blocks.list lists a state that does not differ from the default',
  'blocks-since-ignored': 'blocks.list ignores since_hash',
  'blocks-bad-cursor-ok': 'blocks.list treats a cursor it never issued as the start',
  'blocks-page-hash': 'blocks.list pages after the first carry another hash',
  'blocks-bad-light': 'blocks.list sends emits_light on a block with light 0',
  'pack-bad-hash': 'pack.get serves ruby.png with one byte changed',
  'pack-listing-hash': 'pack.manifest hash is not the hash of the listing',
  'pack-unsorted': 'pack.manifest lists the files in reverse order',
  'pack-path-rule': 'pack.manifest lists a sounds/ file and a path with an uppercase letter',
  'pack-accounting': 'pack.get leaves a missing path out of every list',
  'pack-deferred-lost': 'pack.get leaves deferred paths out of every list',
  'pack-dup-twice': 'pack.get serves a path requested twice twice',
  'pack-get-limit': 'pack.get accepts 65 paths',
  'pack-bad-path-served': 'pack.get answers a path that breaks the path rule as missing instead of invalid_args',
  'asset-slice-gap': 'assets.get: the second slice of layers[0] starts one byte after the end of the first (png_offset gap)',
  'asset-slice-wrong-layer': 'assets.get: the slices of layers[1] say chunk.layer 0',
  'asset-slice-bad-hash': 'assets.get: every slice carries a hash that is not the hash of the whole asset',
  'asset-slice-hash-differs': 'assets.get: the last slice of a sliced asset carries another hash than the first',
  'pack-too-large-served': 'pack.get reports a 3 MiB + 1 byte file as deferred instead of too_large',
};

function parseArgs(argv) {
  const o = { root: null, tcp: false, port: 0, fast: false, moving: false, churn: false, optionalOps: true, busyMs: 1200, breaks: new Set() };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--root') o.root = argv[++i];
    else if (a === '--tcp') o.tcp = true;
    else if (a === '--port') o.port = Number(argv[++i]);
    else if (a === '--fast-timers') o.fast = true;
    else if (a === '--moving') o.moving = true;
    else if (a === '--chunk-churn') o.churn = true;
    else if (a === '--no-optional-ops') o.optionalOps = false;
    else if (a === '--busy-ms') o.busyMs = Number(argv[++i]);
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
const MAX_CONNECTIONS = BRK.has('max-connections-4') ? 4 : 8; // config.json max_connections default 8
let activeConnections = 0;
const MAX_FRAME = 4194304;
const SUB_KEYFRAME_MS = 10000;

// ------------------------------------------------------------------ fake data

const NOW = Math.floor(Date.now() / 1000);
const PNG_1x1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

function finishItem(base) {
  const rec = { ...base };
  if (rec.components && !rec.encoding) rec.encoding = 'components';
  const sub = renderSubsetOf(rec);
  if (BRK.has('skip-render-components') && sub.subset) delete sub.subset.render_components;
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
      inventory: { size: 36, slots: { 0: SWORD, 4: COBBLE, 9: SHULKER, 10: LEATHER } },
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
      inventory: { size: 36, slots: { 0: STONE } },
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


const tf = (key, kind = 'text', min = null, max = null) => ({ key, label: key, kind, choices: [], min, max });
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
      { key: 'slot', label: 'Slot', kind: 'text', choices: [], min: null, max: null },
      { key: 'expected_id', label: 'Expected id', kind: 'identifier', choices: [], min: null, max: null },
    ],
  },
  { id: 'set_item_live', label: 'set item', target: true, destructive: true, offline: false, fields: [tf('container'), tf('slot'), tf('expected_id', 'identifier'), tf('item', 'item')] },
  { id: 'move_item_live', label: 'move item', target: true, destructive: true, offline: false, fields: [tf('container'), tf('slot'), tf('destination_container'), tf('destination_slot'), tf('expected_id', 'identifier'), tf('destination_expected_id', 'identifier')] },
  { id: 'move_item', label: 'move item', target: true, destructive: true, offline: false, fields: [tf('container'), tf('slot'), tf('expected_id', 'identifier'), tf('expected_count', 'integer', 0, 64), tf('expected_fingerprint'), tf('destination_slot'), tf('destination_expected_id', 'identifier'), tf('destination_expected_count', 'integer', 0, 64), tf('destination_expected_fingerprint')] },
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

const OPTIONAL_OPS = ['map.volume', 'biomes.list', 'blocks.list', 'pack.manifest', 'pack.get'];
const OPS = ['hello', 'server.info', 'players.list', 'player.get', 'player.inventory', 'player.nearby', 'item.get', 'positions.subscribe', 'positions.keyframe', 'positions.unsubscribe', 'events.subscribe', 'events.unsubscribe', 'action.run', 'assets.manifest', 'assets.get', 'map.chunks', 'chunks.subscribe', 'chunks.unsubscribe'];
const FEATURES = ['positions.stream', 'inventory.write', 'offline_players', 'sensitive', 'assets', 'map.chunks', 'chunks.stream'];
if (OPT.optionalOps) {
  OPS.push(...OPTIONAL_OPS);
  FEATURES.push('map.volume', 'biomes', 'blocks', 'pack');
  if (BRK.has('feature-biomes-missing')) FEATURES.splice(FEATURES.indexOf('biomes'), 1);
  if (BRK.has('feature-pack-half')) OPS.splice(OPS.indexOf('pack.get'), 1);
}
const EVENTS = ['join', 'leave', 'death', 'chat', 'command', 'advancement', 'damage', 'kick', 'ban'];
const PLAYER_FIELDS = ['position', 'health', 'max_health', 'armor', 'held_item', 'vehicle', 'game_mode', 'experience', 'food', 'game'];

const ASSET_ENTRIES = [
  { key: 'item:examplemod:ruby', render: 'static', png: PNG_1x1 },
  { key: 'item:examplemod:ruby_block', render: 'static', png: PNG_1x1 },
];
for (const a of ASSET_ENTRIES) {
  a.hash = assetHash({ render: 'static', textures: { icon: { png: a.png, width: 1, height: 1 } } });
}

/** A valid 1x1 PNG padded to exactly `n` bytes with a private ancillary chunk (signature and IHDR stay readable). */
function bigPng(n, seed) {
  const head = PNG_1x1.subarray(0, 33); // signature + IHDR
  const tail = PNG_1x1.subarray(33);
  const payload = Buffer.alloc(n - PNG_1x1.length - 12);
  for (let i = 0; i < payload.length; i++) payload[i] = (i * 13 + seed * 29 + (i >> 9)) & 0xff;
  const type = Buffer.from('prVt');
  const len = Buffer.alloc(4);
  len.writeUInt32BE(payload.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([type, payload])));
  return Buffer.concat([head, len, type, payload, crc, tail]);
}

// An asset above 3 MiB with a plain texture, two layers (the second with an emissive overlay): answered in slices.
const SLICE_MAX = 3 * 1024 * 1024;
const BIG_ASSET = {
  key: 'item:examplemod:mosaic',
  render: 'static',
  textures: {
    icon: { png: bigPng(1024 * 1024, 1), width: 1, height: 1 },
    layers: [
      { png: bigPng(SLICE_MAX + 512 * 1024, 2), width: 1, height: 1 },
      { png: bigPng(1024 * 1024, 3), width: 1, height: 1, tint_index: 1, emissive: { png: bigPng(1024 * 1024, 4), width: 1, height: 1 } },
    ],
  },
};
BIG_ASSET.hash = assetHash(BIG_ASSET);
ASSET_ENTRIES.push(BIG_ASSET);

function bigSlices() {
  const strip = (o) => Object.fromEntries(Object.entries(o).filter(([k]) => k !== 'png' && k !== 'emissive'));
  const parts = [
    { tkey: 'icon', layer: null, overlay: false, obj: BIG_ASSET.textures.icon },
    { tkey: 'layers', layer: 0, overlay: false, obj: BIG_ASSET.textures.layers[0] },
    { tkey: 'layers', layer: 1, overlay: false, obj: BIG_ASSET.textures.layers[1] },
    { tkey: 'layers', layer: 1, overlay: true, obj: BIG_ASSET.textures.layers[1].emissive },
  ];
  const out = [];
  for (const part of parts) {
    const png = part.obj.png;
    const n = Math.ceil(png.length / SLICE_MAX);
    for (let i = 0; i < n; i++) {
      const piece = { ...strip(part.obj), png: new Uint8Array(png.subarray(i * SLICE_MAX, (i + 1) * SLICE_MAX)), png_offset: i * SLICE_MAX, png_total: png.length };
      const tex = part.overlay ? { emissive: piece } : piece;
      const textures = part.layer === null ? { [part.tkey]: tex } : { layers: [tex] };
      const chunk = { index: 0, total: 0 };
      if (part.layer !== null) chunk.layer = BRK.has('asset-slice-wrong-layer') && part.layer === 1 ? 0 : part.layer;
      out.push({ key: BIG_ASSET.key, hash: BIG_ASSET.hash, render: 'static', chunk, textures });
    }
  }
  out.forEach((e, i) => {
    e.chunk.index = i;
    e.chunk.total = out.length;
  });
  if (BRK.has('asset-slice-gap')) {
    const e = out.find((x) => x.textures.layers?.[0]?.png_offset > 0 && x.chunk.layer === 0);
    e.textures.layers[0].png_offset += 1;
  }
  if (BRK.has('asset-slice-bad-hash')) for (const e of out) e.hash = '0123456789abcdef';
  if (BRK.has('asset-slice-hash-differs')) out[out.length - 1].hash = '0000000000000000';
  return out;
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
  if (BRK.has('address-present') && p.online) row.address = '127.0.0.1:25565';
  if (BRK.has('ip-always') && p.online && !includeSensitive) row.ip = '127.0.0.1';
  return row;
}

function packBits(indices, bits) {
  const out = new Uint8Array(Math.ceil((indices.length * bits) / 8));
  indices.forEach((v, n) => {
    for (let k = 0; k < bits; k++) {
      const bit = n * bits + k;
      if ((v >> (bits - 1 - k)) & 1) out[bit >> 3] |= 1 << (7 - (bit & 7));
    }
  });
  return out;
}

function mockBlocks() {
  const palette = ['minecraft:air', 'minecraft:stone', 'minecraft:grass_block[snowy=false]'];
  const size = { x: 2, y: 2, z: 2 };
  let indices = [0, 1, 2, 1, 0, 0, 1, 2];
  if (BRK.has('block-index-oob')) indices = [0, 1, 2, 1, 0, 0, 1, 3];
  const bits = Math.max(1, Math.ceil(Math.log2(palette.length)));
  let data = packBits(indices, bits);
  if (BRK.has('bad-block-data')) data = data.subarray(0, data.length - 1);
  return {
    origin: { x: 10, y: 60, z: -80 },
    size,
    world: 'world',
    dimension: 'minecraft:overworld',
    order: 'yzx',
    palette,
    bits,
    data,
    block_entities: [],
    truncated: false,
  };
}

const AIR = 'minecraft:air';
const EQUIP_SLOTS = ['head', 'chest', 'legs', 'feet', 'offhand'];
function ARMOR_SLOT_OF(id) {
  if (/_helmet$/.test(id)) return 'head';
  if (/_chestplate$/.test(id)) return 'chest';
  if (/_leggings$/.test(id)) return 'legs';
  if (/_boots$/.test(id)) return 'feet';
  return null;
}
function maxStack(id) {
  if (/_(sword|helmet|chestplate|leggings|boots|pickaxe|axe|shovel|hoe)$/.test(id) || id === 'minecraft:shulker_box') return 1;
  if (id === 'minecraft:ender_pearl') return 16;
  return 64;
}
function sameItem(a, b) {
  const strip = ({ count, hash, fingerprint, slot, ...rest }) => Buffer.from(canonicalBytes(rest)).toString('hex');
  return strip(a) === strip(b);
}

function containersOf(p) {
  const out = [];
  for (const [id, c] of Object.entries(p.inv)) {
    const slots = Object.entries(c.slots).map(([k, item]) => ({ slot: /^\d+$/.test(k) ? Number(k) : k, item: BRK.has('slot-on-top-level') ? { ...item, slot: 0 } : item }));
    if (id === 'equipment' && BRK.has('mainhand-slot')) slots.push({ slot: 'mainhand', item: STONE });
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


// ------------------------------------------------------------------ fake chunk terrain

const CHUNK_WORLD = 'world';
const CHUNK_DIMS = ['minecraft:overworld', 'minecraft:the_nether'];
const LOAD_RADIUS = 4; // chunks loaded around each online player
const loaded = new Set(); // `${dim}|${cx},${cz}`
const chunkMods = new Map(); // key -> change counter (the churn hook)
const chunkListeners = new Set(); // (dim, cx, cz) => void

const ckey = (dim, cx, cz) => `${dim}|${cx},${cz}`;
const mod = (n, m) => ((n % m) + m) % m;

function fakeColumns(dim, cx, cz) {
  const cols = [];
  const kind = dim === 'minecraft:overworld' ? mod(cx + cz, 7) : -1;
  const rev = chunkMods.get(ckey(dim, cx, cz)) ?? 0;
  const wl = BRK.has('chunk-waterlogged-as-water');
  for (let z = 0; z < 16; z++) {
    for (let x = 0; x < 16; x++) {
      let c;
      if (kind === -1) c = { top: 'minecraft:bedrock', y: 127, depth: 0, floor: null, biome: 'minecraft:nether_wastes' };
      else if (kind === 0) c = { top: 'minecraft:air', y: -64, depth: 0, floor: null, biome: 'minecraft:plains' };
      else if (kind === 1) {
        c = { top: 'minecraft:grass_block', y: 64 + ((x + z) % 3), depth: 0, floor: null, biome: 'minecraft:plains' };
        if (x === 3 && z === 5) c = { top: 'minecraft:air', y: -64, depth: 0, floor: null, biome: 'minecraft:plains' };
      } else if (kind === 2) {
        if (x === 15 && z === 15) c = { top: 'minecraft:water', y: 62, depth: 127, floor: null, biome: 'minecraft:river' };
        else if (x === 8 && z === 0) c = { top: 'minecraft:kelp', y: 62, depth: 3, floor: 'minecraft:sand', biome: 'minecraft:river' };
        else if (x < 8) c = { top: 'minecraft:water', y: 62, depth: 1 + (x % 4), floor: z % 2 === 0 ? 'minecraft:sand' : 'minecraft:gravel', biome: 'minecraft:river' };
        else c = { top: 'minecraft:grass_block', y: 65, depth: 0, floor: null, biome: 'minecraft:plains' };
      } else if (kind === 3) {
        if (x < 8) c = { top: 'minecraft:oak_stairs', y: 63, depth: wl ? 1 : 0, floor: wl ? 'minecraft:sand' : null, biome: 'minecraft:forest' };
        else c = { top: 'minecraft:stone', y: 70, depth: 0, floor: null, biome: 'minecraft:forest' };
      } else if (kind === 4) c = { top: x === 2 && z === 2 ? 'minecraft:oak_log' : 'minecraft:oak_leaves', y: 80, depth: 0, floor: null, biome: 'minecraft:forest' };
      else if (kind === 5) {
        c = { top: 'minecraft:grass_block', y: 64, depth: 0, floor: null, biome: 'minecraft:plains' };
        // A deep pit: lowest - 4 falls below the overworld minimum Y (-64), so map.volume clamps y0.
        if (x === 15 && z === 15) c = { top: 'minecraft:stone', y: -62, depth: 0, floor: null, biome: 'minecraft:plains' };
      } else {
        c = { top: 'minecraft:stone', y: 100, depth: 0, floor: null, biome: x < 8 ? 'minecraft:forest' : 'minecraft:plains' };
        // A spire: highest - 159 lifts y0 above lowest - 4, so map.volume hits the 160 level cap.
        if (x === 0 && z === 0) c = { top: 'minecraft:stone', y: 300, depth: 0, floor: null, biome: 'minecraft:forest' };
      }
      if (rev > 0 && x === 0 && z === 0 && c.top !== 'minecraft:air') c = { ...c, y: c.y + (rev % 5) + 1 };
      cols.push(c);
    }
  }
  return cols;
}

function fakeSurface(dim, cx, cz) {
  const s = buildSurface({ cx, cz, world: CHUNK_WORLD, dimension: dim, columns: fakeColumns(dim, cx, cz) });
  if (s.void) return s;
  if (BRK.has('chunk-bad-length')) s.top = s.top.subarray(0, 510);
  if (BRK.has('chunk-wrong-hash')) s.hash = surfaceHash({ ...s, palette: [] });
  return s;
}

function resolveChunkTarget(args) {
  if (typeof args.world !== 'string') return { error: ['invalid_args', 'world required'] };
  if (args.dimension !== undefined && typeof args.dimension !== 'string') return { error: ['invalid_args', 'dimension must be a string'] };
  if (args.world !== CHUNK_WORLD) return { error: ['not_found', `unknown world ${args.world}`] };
  if (args.dimension === undefined) return { error: ['invalid_args', 'dimension is required: the world has several dimensions'] };
  if (!CHUNK_DIMS.includes(args.dimension)) return { error: ['not_found', `unknown dimension ${args.dimension}`] };
  return { dim: args.dimension };
}

/** Recomputes the loaded set: radius LOAD_RADIUS around every online player. Fires listeners for new chunks. */
function refreshLoaded() {
  const next = new Set();
  for (const p of PLAYERS) {
    if (!p.online) continue;
    const pcx = Math.floor(p.pos.x / 16);
    const pcz = Math.floor(p.pos.z / 16);
    for (let dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) for (let dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) next.add(ckey(p.dimension, pcx + dx, pcz + dz));
  }
  const fresh = [];
  for (const k of next) if (!loaded.has(k)) fresh.push(k);
  loaded.clear();
  for (const k of next) loaded.add(k);
  for (const k of fresh) {
    const [dim, rest] = k.split('|');
    const [cx, cz] = rest.split(',').map(Number);
    for (const l of chunkListeners) l(dim, cx, cz);
  }
}

function isLoaded(dim, cx, cz) {
  return BRK.has('chunk-missing-wrong') ? true : loaded.has(ckey(dim, cx, cz));
}

function teleportPlayer(p, x, y, z) {
  p.pos.x = x;
  p.pos.y = y;
  p.pos.z = z;
  refreshLoaded();
}

refreshLoaded();
if (OPT.churn) {
  setInterval(() => {
    const p = PLAYERS.find((x) => x.online && x.dimension === 'minecraft:overworld');
    if (!p) return;
    const cx = Math.floor(p.pos.x / 16);
    const cz = Math.floor(p.pos.z / 16);
    const k = ckey(p.dimension, cx, cz);
    chunkMods.set(k, (chunkMods.get(k) ?? 0) + 1);
    for (const l of chunkListeners) l(p.dimension, cx, cz);
  }, 400).unref();
}

// ------------------------------------------------------------------ fake 3D map data (map.volume, biomes, blocks, pack)

const STARTED_AT = Date.now();
const isBusy = () => Date.now() - STARTED_AT < OPT.busyMs; // the "background build" of blocks.list and the pack index
const DIM_MIN_Y = { 'minecraft:overworld': -64, 'minecraft:the_nether': 0 };
const VOLUME_PAGE_BYTES = 64 * 1024; // small on purpose, so the cursor path is exercised (the contract allows up to max_frame)

// Block state properties in source order (not sorted): the mock normalises them like a bridge must.
const STATE_PROPS = {
  'minecraft:grass_block': [['snowy', 'false']],
  'minecraft:oak_stairs': [['waterlogged', 'false'], ['shape', 'straight'], ['half', 'bottom'], ['facing', 'east']],
  'minecraft:oak_log': [['axis', 'y']],
  'minecraft:oak_leaves': [['persistent', 'false'], ['waterlogged', 'false'], ['distance', '7']],
  'minecraft:water': [['level', '0']],
  'minecraft:kelp': [['age', '5']],
  'minecraft:light': [['waterlogged', 'false'], ['level', '10']],
};

function stateString(id) {
  const props = STATE_PROPS[id];
  if (!props) return id;
  if (BRK.has('volume-unsorted-state')) return `${id}[${props.map(([k, v]) => `${k}=${v}`).join(',')}]`;
  return normaliseState(id, props);
}

/** Blocks that are not a column's surface: { columnIndex: { y: id } } (here a light block in an otherwise empty column). */
function fakeExtras(dim, cx, cz) {
  const kind = dim === 'minecraft:overworld' ? mod(cx + cz, 7) : -1;
  return kind === 1 ? { [5 * 16 + 3]: { 100: 'minecraft:light' } } : {};
}

/** The block state string at height y of a column built from a map.chunks column, or null for air. */
function columnBlock(col, extra, y, dim) {
  if (extra && extra[y]) return stateString(extra[y]);
  if (col.top === 'minecraft:air') return null;
  const sy = col.y;
  if (y > sy) return null;
  if (y === sy) return stateString(col.top);
  if (WATER_LIST.includes(col.top) && col.depth > 0) {
    if (y > sy - col.depth) return stateString(col.top === 'minecraft:kelp' ? 'minecraft:kelp_plant' : 'minecraft:water');
    if (y === sy - col.depth && col.floor) return stateString(col.floor);
  }
  if (dim === 'minecraft:the_nether') return 'minecraft:netherrack';
  return sy - y <= 3 ? 'minecraft:dirt' : 'minecraft:stone';
}

const WATER_LIST = ['minecraft:water', 'minecraft:bubble_column', 'minecraft:kelp', 'minecraft:kelp_plant', 'minecraft:seagrass', 'minecraft:tall_seagrass'];

/** A ChunkVolume built from the same columns as fakeSurface (operations.md 6c band rules). */
function fakeVolume(dim, cx, cz, yMin, yMax) {
  const cols = fakeColumns(dim, cx, cz);
  const extras = fakeExtras(dim, cx, cz);
  const minY = DIM_MIN_Y[dim] ?? -64;
  const surf = cols.filter((c) => c.top !== 'minecraft:air');
  const biomeIds = [];
  const biome = cols.map((c, i) => {
    let id = c.biome;
    if (i === 0 && BRK.has('volume-biome-mismatch')) id = 'minecraft:desert';
    let k = biomeIds.indexOf(id);
    if (k < 0) biomeIds.push(id), (k = biomeIds.length - 1);
    return k;
  });
  const out = { cx, cz, y0: minY, height: 0, palette: [], blocks: new Uint8Array(0), biome_ids: biomeIds, biome: packU16LE(biome) };
  if (surf.length === 0) return out;
  const lowest = Math.min(...surf.map((c) => c.y));
  let highest = Math.max(...surf.map((c) => c.y));
  for (const e of Object.values(extras)) for (const y of Object.keys(e)) highest = Math.max(highest, Number(y));
  let y0 = Math.max(lowest - 4, minY, highest - 159);
  let top = highest;
  if (!BRK.has('volume-ignore-y')) {
    if (yMin !== undefined) y0 = Math.max(y0, yMin);
    if (yMax !== undefined) top = Math.min(top, yMax);
  }
  if (BRK.has('volume-wrong-band')) y0 -= 1;
  const height = top >= y0 ? top - y0 + 1 : 0;
  if (height === 0) return { ...out, y0 };
  const palette = [];
  const idx = new Map();
  const cells = new Array(height * 256);
  for (let level = 0; level < height; level++) {
    const y = y0 + level;
    for (let i = 0; i < 256; i++) {
      let st = columnBlock(cols[i], extras[i], y, dim);
      if (st === null && BRK.has('volume-air-in-palette')) st = 'minecraft:air';
      if (st === null) {
        cells[level * 256 + i] = 0xffff;
        continue;
      }
      let k = idx.get(st);
      if (k === undefined) {
        k = palette.length;
        palette.push(st);
        idx.set(st, k);
      }
      cells[level * 256 + i] = k;
    }
  }
  if (BRK.has('volume-surface-air')) {
    const sy = cols[0].y;
    if (cols[0].top !== 'minecraft:air' && sy >= y0 && sy <= top) cells[(sy - y0) * 256] = 0xffff;
  }
  let blocks = packU16LE(cells);
  if (BRK.has('volume-bad-length')) blocks = blocks.subarray(0, blocks.length - 2);
  return { ...out, y0, height, palette, blocks };
}

function volumeSize(v) {
  return v.blocks.length + v.palette.reduce((n, p) => n + p.length + 2, 0) + 700;
}

// ---- biomes

const BIOME_LIST = [
  { id: 'examplemod:crystal_forest', temperature: 0.7, downfall: 0.6, grass_color: 4508808, foliage_color: null, dry_foliage_color: null, water_color: 4159204, grass_color_modifier: 'none' },
  { id: 'minecraft:dark_forest', temperature: 0.7, downfall: 0.8, grass_color: null, foliage_color: null, dry_foliage_color: null, water_color: 4159204, grass_color_modifier: 'dark_forest' },
  { id: 'minecraft:desert', temperature: 2, downfall: 0, grass_color: null, foliage_color: null, dry_foliage_color: 10387789, water_color: 4159204, grass_color_modifier: 'none' },
  { id: 'minecraft:forest', temperature: 0.7, downfall: 0.8, grass_color: null, foliage_color: null, dry_foliage_color: null, water_color: 4159204, grass_color_modifier: 'none' },
  { id: 'minecraft:nether_wastes', temperature: 2, downfall: 0, grass_color: null, foliage_color: null, dry_foliage_color: null, water_color: 4159204, grass_color_modifier: 'none' },
  { id: 'minecraft:plains', temperature: 0.8, downfall: 0.4, grass_color: null, foliage_color: null, dry_foliage_color: null, water_color: 4159204, grass_color_modifier: 'none' },
  { id: 'minecraft:river', temperature: 0.5, downfall: 0.5, grass_color: null, foliage_color: null, dry_foliage_color: null, water_color: 4159204, grass_color_modifier: 'none' },
  { id: 'minecraft:swamp', temperature: 0.8, downfall: 0.9, grass_color: null, foliage_color: 6975545, dry_foliage_color: 8082228, water_color: 6388580, grass_color_modifier: 'swamp' },
];

function biomesAnswer() {
  let list = BIOME_LIST.map((b) => ({ ...b }));
  if (BRK.has('biomes-no-plains')) list = list.filter((b) => b.id !== 'minecraft:plains');
  if (BRK.has('biomes-missing-chunk-biome')) list = list.filter((b) => b.id !== 'minecraft:river');
  if (BRK.has('biomes-bad-color')) list[2].water_color = 0xff000000 + list[2].water_color;
  if (BRK.has('biomes-bad-modifier')) list[3].grass_color_modifier = 'tropical';
  if (BRK.has('biomes-unsorted')) list.reverse();
  return { biomes: list };
}

// ---- blocks

function buildBlocks() {
  const b = (id, map_color, flags, light = 0, states) => (states ? { id, map_color, flags, light, states } : { id, map_color, flags, light });
  const list = [
    b('minecraft:air', null, 177),
    b('minecraft:cave_air', null, 177),
    b('minecraft:void_air', null, 177),
    b('minecraft:bedrock', 5592405, 12),
    b('minecraft:dirt', 9923917, 12),
    b('minecraft:grass_block', 8368696, 12),
    b('minecraft:gravel', 8355711, 12),
    b('minecraft:kelp', 4210943, 16),
    b('minecraft:kelp_plant', 4210943, 16),
    b('minecraft:light', null, 720, 15),
    b('minecraft:netherrack', 7340032, 12),
    b('minecraft:oak_leaves', 31744, 516),
    b('minecraft:oak_log', 9402184, 12, 0, [
      { properties: 'axis=x', flags: 13 },
      { properties: 'axis=z', map_color: 6567200, flags: 14 },
    ]),
    b('minecraft:oak_stairs', 9402184, 516),
    b('minecraft:sand', 16247203, 12),
    b('minecraft:stone', 7368816, 12),
    b('minecraft:water', 4210943, 178),
    b('minecraft:furnace', 7368816, 268, 0, [
      { properties: 'facing=east,lit=true', flags: 332 },
      { properties: 'facing=north,lit=true', flags: 332 },
      { properties: 'facing=south,lit=true', flags: 332 },
      { properties: 'facing=west,lit=true', flags: 332 },
    ]),
    b('examplemod:ruby_block', 14684176, 12),
    b('examplemod:ruby_lamp', 14684176, 76, 15, [{ properties: 'lit=false', flags: 12 }]),
  ];
  // Mod blocks, enough entries for several pages.
  for (let i = 0; i < 40; i++) {
    const id = `examplemod:block_${String(i).padStart(2, '0')}`;
    list.push(b(id, i % 9 === 0 ? null : (i * 6271 + 1000) & 0xffffff, i % 4 === 0 ? 4 : 12, 0, i % 5 === 0 ? [{ properties: 'powered=true', map_color: (i * 977) & 0xffffff, flags: i % 4 === 0 ? 4 : 12 }, { properties: 'rotation=3', flags: 13 }] : undefined));
  }
  list.sort((x, y) => bytewise(x.id, y.id));
  for (const e of list) if (e.states) e.states.sort((x, y) => bytewise(x.properties, y.properties));
  return list;
}

const BLOCKS_PAGE_BYTES = 1100;
const BLOCKS = buildBlocks();
if (BRK.has('blocks-state-same')) BLOCKS.find((x) => x.id === 'minecraft:furnace').states.push({ properties: 'facing=zzz,lit=false', flags: 268 });
if (BRK.has('blocks-bad-light')) BLOCKS.find((x) => x.id === 'minecraft:stone').flags |= 64;
if (BRK.has('blocks-unsorted')) [BLOCKS[3], BLOCKS[4]] = [BLOCKS[4], BLOCKS[3]];
const BLOCKS_HASH = BRK.has('blocks-wrong-hash') ? blocksHash(BLOCKS.slice(1)) : blocksHash(BLOCKS);
const BLOCK_PAGES = (() => {
  const pages = [];
  let cur = [];
  let size = 0;
  for (const e of BLOCKS) {
    const n = canonicalBytes(e).length;
    if (cur.length && size + n > BLOCKS_PAGE_BYTES) {
      pages.push(cur);
      cur = [];
      size = 0;
    }
    cur.push(e);
    size += n;
  }
  pages.push(cur);
  return pages;
})();

// ---- pack

function patterned(n, seed) {
  const b = Buffer.alloc(n);
  for (let i = 0; i < n; i++) b[i] = (i * 31 + seed * 17 + (i >> 8)) & 0xff;
  return b;
}

const PACK = new Map();
const addPack = (path, bytes) => PACK.set(path, bytes);
addPack('assets/examplemod/blockstates/ruby_block.json', Buffer.from('{"variants":{"":{"model":"examplemod:block/ruby_block"}}}\n'));
addPack('assets/examplemod/models/block/ruby_block.json', Buffer.from('{"parent":"minecraft:block/cube_all","textures":{"all":"examplemod:block/ruby_block"}}\n'));
addPack('assets/examplemod/models/item/ruby.json', Buffer.from('{"parent":"minecraft:item/generated","textures":{"layer0":"examplemod:item/ruby"}}\n'));
addPack('assets/examplemod/textures/block/big_a.png', patterned(2.5 * 1024 * 1024, 1));
addPack('assets/examplemod/textures/block/big_b.png', patterned(2.5 * 1024 * 1024, 2));
addPack('assets/examplemod/textures/block/huge.png', patterned(PACK_TOO_LARGE + 1, 3));
addPack('assets/examplemod/textures/block/ruby_block.png', PNG_1x1);
addPack('assets/examplemod/textures/item/ruby.png', Buffer.from([1, 2, 3]));
addPack('assets/examplemod/textures/item/ruby.png.mcmeta', Buffer.from('{"animation":{}}\n'));

function packEntries() {
  const entries = [...PACK.entries()].map(([path, bytes]) => ({ path, hash: fileHash(bytes), size: bytes.length }));
  if (BRK.has('pack-path-rule')) {
    entries.push({ path: 'assets/examplemod/sounds/ding.ogg', hash: fileHash(Buffer.from('ding')), size: 4 });
    entries.push({ path: 'assets/examplemod/textures/item/Ruby.png', hash: fileHash(Buffer.from('R')), size: 1 });
  }
  entries.sort((x, y) => bytewise(x.path, y.path));
  return entries;
}

const PACK_ENTRIES = packEntries();
const PACK_HASH = BRK.has('pack-listing-hash') ? '0000000000000000' : listingHash(PACK_ENTRIES);
const PACK_PAGE_FILES = 3;
const PACK_FRAME_BUDGET = MAX_FRAME - 8192;


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
    this.chk = null; // chunks subscription, see chunksSubscribe()
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
    this.dropChunks();
    this.socket.destroy();
  }

  hasSub() {
    return Boolean(this.pos || this.ev || this.chk);
  }

  tick() {
    const now = Date.now();
    for (const s of [this.pos, this.ev, this.chk]) {
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
        const blocks = args.blocks === true ? mockBlocks() : undefined;
        return ok({
          ...(blocks ? { blocks } : {}),
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
      case 'map.chunks':
        return this.mapChunks(args);
      case 'map.volume':
      case 'biomes.list':
      case 'blocks.list':
      case 'pack.manifest':
      case 'pack.get':
        if (!OPT.optionalOps || !OPS.includes(op)) return unknown();
        return this[{ 'map.volume': 'mapVolume', 'biomes.list': 'biomesList', 'blocks.list': 'blocksList', 'pack.manifest': 'packManifest', 'pack.get': 'packGet' }[op]](args);
      case 'chunks.subscribe':
        return this.chunksSubscribe(args);
      case 'chunks.unsubscribe': {
        if (!this.chk || this.chk.sub !== args.sub) return err('not_found', 'unknown sub');
        this.dropChunks();
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
    if (want('game')) {
      d.game = { offline_uuid: false, air_ticks: 300, max_air_ticks: 300, fire_ticks: -20, effects: [{ id: 'minecraft:speed', amplifier: 1, duration_ticks: 1200, ambient: false, particles: true }], groups: p.groups };
      if (BRK.has('common-key-in-game')) d.game.position = { ...p.pos };
      if (BRK.has('xp-keys-in-game')) d.game.xp_level = 30;
    }
    if (BRK.has('inventory-in-player-get')) d.inventory = [];
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
    if (['set_item_live', 'remove_item_live', 'move_item_live', 'move_item'].includes(a.id) && !args.expect) {
      return this.inventoryAction(a.id, PLAYERS.find((x) => x.id === args.target), subArgs);
    }
    if (a.id === 'teleport' && !args.expect) {
      const x = subArgs.x;
      const z = subArgs.z;
      if (typeof x !== 'number' || typeof z !== 'number') return { body: errorBody('invalid_args', 'teleport needs x and z (the mock has no to_player)') };
      const p = PLAYERS.find((q) => q.id === args.target);
      if (!p.online) return { body: errorBody('offline', 'player is offline') };
      teleportPlayer(p, x, typeof subArgs.y === 'number' ? subArgs.y : p.pos.y, z);
      return { body: { ok: true, data: { ok: true, message: 'teleported' } } };
    }
    // The mock never modifies any other game state.
    return { body: { ok: true, data: { ok: false, message: 'mock: action recognised, not executed' } } };
  }

  /** set_item_live / remove_item_live / move_item(_live) on the fake inventory (NOTES D24). */
  inventoryAction(id, p, a) {
    const bad = (m) => ({ body: errorBody('invalid_args', m) });
    const conflict = (m) => ({ body: errorBody('conflict', m) });
    const done = (message, result) => ({ body: { ok: true, data: { ok: true, message, ...(result ? { result } : {}) } } });
    if (!p.online) return { body: errorBody('offline', 'player is offline') };
    const where = (cName, sName) => {
      if (typeof cName !== 'string' || !p.inv[cName]) return null;
      const c = p.inv[cName];
      const key = slotKey(sName, cName);
      if (key === null) return null;
      const okKey = cName === 'equipment' ? EQUIP_SLOTS.includes(key) : Number(key) < c.size;
      return okKey ? { c, key, cName } : null;
    };
    const idOf = (it) => (it ? it.id : AIR);
    const src = where(a.container, a.slot);
    if (!src) return bad('container/slot is not valid');
    const cur = src.c.slots[src.key];

    if (id === 'set_item_live') {
      if (a.expected_id !== undefined && idOf(cur) !== a.expected_id) return conflict('slot changed');
      const it = a.item;
      if (!it || typeof it !== 'object' || typeof it.id !== 'string' || !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(it.id)) return bad('item.id required');
      const n = it.count === undefined ? 1 : it.count;
      if (!Number.isInteger(n) || n < 1 || n > 64) return bad('item.count must be 1..64');
      src.c.slots[src.key] = finishItem({ id: it.id, count: n });
      return done('item set');
    }
    if (id === 'remove_item_live') {
      if (a.expected_id !== undefined && idOf(cur) !== a.expected_id) return conflict('slot changed');
      if (!cur) return conflict('slot is empty');
      delete src.c.slots[src.key];
      return done('item removed');
    }
    // move_item_live (cross-container) / move_item (same container)
    const dst = where(id === 'move_item' ? a.container : a.destination_container ?? a.container, a.destination_slot);
    if (!dst) return bad('destination is not valid');
    if (dst.cName === src.cName && dst.key === src.key) return bad('source and destination are the same slot');
    const dcur = dst.c.slots[dst.key];
    if (!cur || (a.expected_id !== undefined && cur.id !== a.expected_id)) return conflict('source changed');
    if (id === 'move_item') {
      if (a.expected_count !== undefined && cur.count !== a.expected_count) return conflict('source count changed');
      if (a.expected_fingerprint !== undefined && cur.fingerprint !== a.expected_fingerprint) return conflict('source fingerprint changed');
    }
    if (!BRK.has('move-stale-ok')) {
      if (a.destination_expected_id !== undefined && idOf(dcur) !== a.destination_expected_id) return conflict('destination changed');
      if (id === 'move_item') {
        if (a.destination_expected_count !== undefined && (dcur ? dcur.count : 0) !== a.destination_expected_count) return conflict('destination count changed');
        if (a.destination_expected_fingerprint !== undefined && (dcur ? dcur.fingerprint : 'none') !== a.destination_expected_fingerprint) return conflict('destination fingerprint changed');
      }
    }
    const armorOk = (cName, key, it) => {
      if (BRK.has('move-armor-unchecked') || !it) return true;
      if (cName !== 'equipment' || !['head', 'chest', 'legs', 'feet'].includes(key)) return true;
      return ARMOR_SLOT_OF(it.id) === key;
    };
    if (!dcur) {
      if (!armorOk(dst.cName, dst.key, cur)) return conflict('item does not fit the destination slot');
      dst.c.slots[dst.key] = cur;
      delete src.c.slots[src.key];
      return done('moved', { op: 'move', moved: cur.count });
    }
    if (BRK.has('move-no-swap')) return conflict('destination is occupied');
    if (a.destination_expected_id === undefined && !BRK.has('move-stale-ok')) return conflict('destination is occupied and no destination_expected_id was given');
    const same = sameItem(cur, dcur);
    const max = BRK.has('move-overstack') ? 1000 : maxStack(cur.id);
    if (same && dcur.count < max) {
      const moved = Math.min(max - dcur.count, cur.count);
      dst.c.slots[dst.key] = finishItem({ ...dcur, count: dcur.count + moved });
      if (moved === cur.count) delete src.c.slots[src.key];
      else src.c.slots[src.key] = finishItem({ ...cur, count: cur.count - moved });
      return done('stacked', { op: 'stack', moved });
    }
    if (!armorOk(dst.cName, dst.key, cur) || !armorOk(src.cName, src.key, dcur)) return conflict('item does not fit the destination slot');
    dst.c.slots[dst.key] = cur;
    src.c.slots[src.key] = dcur;
    return done('swapped', { op: 'swap', moved: cur.count });
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
    if (args.cursor !== undefined && typeof args.cursor !== 'string') return { body: errorBody('invalid_args', 'cursor must be a string') };
    let startKey = 0;
    let startSlice = 0;
    if (args.cursor !== undefined) {
      const m = /^a1:(\d+):(\d+)$/.exec(args.cursor);
      if (!m || Number(m[1]) >= args.keys.length) return { body: errorBody('invalid_args', 'bad cursor') };
      startKey = Number(m[1]);
      startSlice = Number(m[2]);
    }
    let out = [];
    let ki = startKey;
    let cursor = null;
    for (; ki < args.keys.length; ki++) {
      const k = args.keys[ki];
      if (k === BIG_ASSET.key) {
        // Whole assets fill a page first; a sliced asset is answered one texture slice per call.
        if (out.length > 0) break;
        const slices = bigSlices();
        const si = ki === startKey ? startSlice : 0;
        if (si >= slices.length) return { body: errorBody('invalid_args', 'bad cursor') };
        out.push(slices[si]);
        if (si + 1 < slices.length) {
          cursor = `a1:${ki}:${si + 1}`;
        } else ki++;
        break;
      }
      const e = ASSET_ENTRIES.find((x) => x.key === k);
      if (e) {
        out.push({ key: k, hash: e.hash, render: 'static', textures: { icon: { png: e.png, width: 1, height: 1 } } });
      } else if (k.startsWith('item:minecraft:')) {
        out.push({ key: k, hash: '0000000000000000', render: 'unavailable', unavailable_reason: 'vanilla_assets_panel_side' });
      } else {
        out.push({ key: k, hash: '0000000000000000', render: 'unavailable', unavailable_reason: 'not_found' });
      }
    }
    if (BRK.has('reversed-assets')) out = out.reverse();
    const data = { assets: out, more: cursor !== null || ki < args.keys.length };
    if (data.more) data.cursor = cursor ?? `a1:${ki}:0`;
    return { body: { ok: true, data } };
  }

  // ---------------------------------------------------------- 3D map ops

  mapVolume(args) {
    const bad = (code, msg) => ({ body: errorBody(code, msg) });
    const max = BRK.has('volume-too-many-ok') ? 256 : 64;
    if (!Array.isArray(args.chunks)) return bad('invalid_args', 'chunks must be an array');
    if (args.chunks.length > max) return bad('invalid_args', 'at most 64 chunks');
    if (!args.chunks.every((c) => Array.isArray(c) && c.length === 2 && Number.isInteger(num(c[0])) && Number.isInteger(num(c[1])))) return bad('invalid_args', 'chunks must be [[cx, cz], ...]');
    for (const k of ['y_min', 'y_max']) if (args[k] !== undefined && !Number.isInteger(num(args[k]))) return bad('invalid_args', `${k} must be an int`);
    const yMin = args.y_min === undefined ? undefined : num(args.y_min);
    const yMax = args.y_max === undefined ? undefined : num(args.y_max);
    if (yMin !== undefined && yMax !== undefined && yMin > yMax && !BRK.has('volume-bad-y-order')) return bad('invalid_args', 'y_min > y_max');
    if (args.cursor !== undefined && typeof args.cursor !== 'string') return bad('invalid_args', 'cursor must be a string');
    const t = resolveChunkTarget(args);
    if (t.error) return bad(...t.error);
    let list = args.chunks.map((c) => [num(c[0]), num(c[1])]);
    if (!BRK.has('volume-dup-answer')) {
      const seen = new Set();
      list = list.filter(([cx, cz]) => !seen.has(`${cx},${cz}`) && seen.add(`${cx},${cz}`));
    }
    let start = 0;
    if (args.cursor !== undefined) {
      const m = /^vol1:(\d+)$/.exec(args.cursor);
      if (!m || Number(m[1]) > list.length) return bad('invalid_args', 'bad cursor');
      start = Number(m[1]);
    }
    const chunks = [];
    const missing = [];
    let size = 0;
    let i = start;
    for (; i < list.length; i++) {
      const [cx, cz] = list[i];
      if (!(BRK.has('volume-missing-wrong') ? true : isLoaded(t.dim, cx, cz))) {
        missing.push([cx, cz]);
        continue;
      }
      const v = fakeVolume(t.dim, cx, cz, yMin, yMax);
      const n = volumeSize(v);
      if (chunks.length > 0 && size + n > VOLUME_PAGE_BYTES) break;
      if (!(BRK.has('volume-lose-on-page') && start > 0 && chunks.length === 0 && missing.length === 0)) chunks.push(v);
      size += n;
    }
    const data = { chunks, missing };
    if (i < list.length) {
      data.more = true;
      data.cursor = `vol1:${i}`;
    }
    return { body: { ok: true, data } };
  }

  biomesList() {
    return { body: { ok: true, data: biomesAnswer() } };
  }

  blocksList(args) {
    const bad = (code, msg) => ({ body: errorBody(code, msg) });
    if (isBusy()) return bad('busy', 'the block registry answer is still being built');
    if (args.since_hash !== undefined && typeof args.since_hash !== 'string') return bad('invalid_args', 'since_hash must be a string');
    if (args.cursor !== undefined && typeof args.cursor !== 'string') return bad('invalid_args', 'cursor must be a string');
    let page = 0;
    if (args.cursor !== undefined) {
      const m = /^blk1:(\d+)$/.exec(args.cursor);
      if (m && Number(m[1]) > 0 && Number(m[1]) < BLOCK_PAGES.length) page = Number(m[1]);
      else if (!BRK.has('blocks-bad-cursor-ok')) return bad('invalid_args', 'bad cursor');
    } else if (args.since_hash !== undefined && args.since_hash === BLOCKS_HASH && !BRK.has('blocks-since-ignored')) {
      return { body: { ok: true, data: { hash: BLOCKS_HASH, unchanged: true } } };
    }
    const data = { hash: BRK.has('blocks-page-hash') && page > 0 ? '0000000000000000' : BLOCKS_HASH, blocks: BLOCK_PAGES[page] };
    if (page + 1 < BLOCK_PAGES.length) data.next = `blk1:${page + 1}`;
    return { body: { ok: true, data } };
  }

  packManifest(args) {
    const bad = (code, msg) => ({ body: errorBody(code, msg) });
    if (isBusy()) return bad('busy', 'the pack index is still being built');
    if (args.cursor !== undefined && typeof args.cursor !== 'string') return bad('invalid_args', 'cursor must be a string');
    let start = 0;
    if (args.cursor !== undefined) {
      const m = /^pk1:(\d+)$/.exec(args.cursor);
      if (!m || Number(m[1]) <= 0 || Number(m[1]) >= PACK_ENTRIES.length) return bad('invalid_args', 'bad cursor');
      start = Number(m[1]);
    }
    let files = PACK_ENTRIES.slice(start, start + PACK_PAGE_FILES);
    if (BRK.has('pack-unsorted')) files = PACK_ENTRIES.slice().reverse().slice(start, start + PACK_PAGE_FILES);
    const data = { hash: PACK_HASH, files };
    if (start + PACK_PAGE_FILES < PACK_ENTRIES.length) data.next = `pk1:${start + PACK_PAGE_FILES}`;
    return { body: { ok: true, data } };
  }

  packGet(args) {
    const bad = (code, msg) => ({ body: errorBody(code, msg) });
    if (isBusy()) return bad('busy', 'the pack index is still being built');
    const max = BRK.has('pack-get-limit') ? 1000 : PACK_MAX_PATHS;
    if (!Array.isArray(args.paths) || args.paths.length === 0 || args.paths.length > max) return bad('invalid_args', 'paths must be 1..64 strings');
    if (!args.paths.every((p) => typeof p === 'string')) return bad('invalid_args', 'paths must be strings');
    const broken = args.paths.some((p) => packPathError(p));
    if (broken && !BRK.has('pack-bad-path-served')) return bad('invalid_args', 'a path breaks the pack path rule');
    const order = BRK.has('pack-dup-twice') ? args.paths : [...new Set(args.paths)];
    const files = [];
    const missing = [];
    const deferred = [];
    const tooLarge = [];
    let used = 256;
    let overflow = false;
    for (const path of order) {
      let bytes = PACK.get(path);
      if (!bytes || packPathError(path)) {
        if (!BRK.has('pack-accounting')) missing.push(path);
        continue;
      }
      if (bytes.length > PACK_TOO_LARGE) {
        (BRK.has('pack-too-large-served') ? deferred : tooLarge).push(path);
        continue;
      }
      const cost = bytes.length + path.length + 40;
      if (overflow || (files.length > 0 && used + cost > PACK_FRAME_BUDGET)) {
        overflow = true;
        if (!BRK.has('pack-deferred-lost')) deferred.push(path);
        continue;
      }
      if (BRK.has('pack-bad-hash') && path.endsWith('textures/item/ruby.png')) {
        bytes = Buffer.from(bytes);
        bytes[bytes.length - 1] ^= 0xff;
      }
      files.push({ path, data: new Uint8Array(bytes) });
      used += cost;
    }
    const data = { files, missing };
    if (deferred.length) data.deferred = deferred;
    if (tooLarge.length) data.too_large = tooLarge;
    return { body: { ok: true, data } };
  }

  // ---------------------------------------------------------- chunks

  mapChunks(args) {
    const bad = (code, msg) => ({ body: errorBody(code, msg) });
    if (!Array.isArray(args.chunks)) return bad('invalid_args', 'chunks must be an array');
    if (args.chunks.length > 256) return bad('invalid_args', 'at most 256 chunks');
    if (!args.chunks.every((c) => Array.isArray(c) && c.length === 2 && Number.isInteger(num(c[0])) && Number.isInteger(num(c[1])))) return bad('invalid_args', 'chunks must be [[cx, cz], ...]');
    const t = resolveChunkTarget(args);
    if (t.error) return bad(...t.error);
    const chunks = [];
    const missing = [];
    for (const c of args.chunks) {
      const cx = num(c[0]);
      const cz = num(c[1]);
      if (isLoaded(t.dim, cx, cz)) chunks.push(fakeSurface(t.dim, cx, cz));
      else missing.push([cx, cz]);
    }
    return { body: { ok: true, data: { chunks, missing } } };
  }

  chunksSubscribe(args) {
    const bad = (code, msg) => ({ body: errorBody(code, msg) });
    const t = resolveChunkTarget(args);
    if (t.error) return bad(...t.error);
    let area = null;
    if (args.area !== undefined) {
      const a = args.area;
      const keys = ['min_cx', 'min_cz', 'max_cx', 'max_cz'];
      if (!a || typeof a !== 'object' || Array.isArray(a) || !keys.every((k) => Number.isInteger(num(a[k])))) return bad('invalid_args', 'area must be {min_cx, min_cz, max_cx, max_cz}');
      area = Object.fromEntries(keys.map((k) => [k, num(a[k])]));
    }
    if (this.chk) return bad('busy', 'chunks subscription already active');
    const sub = this.nextSub++;
    const s = { sub, dim: t.dim, area, seq: 0, lastSentAt: Date.now(), pending: new Set(), lastHash: new Map(), lastSent: new Map(), loadUntil: 0, loadRemaining: 0, dropped: 0, timer: null, listener: null };
    s.listener = (dim, cx, cz) => {
      if (dim !== s.dim) return;
      if (area && (cx < area.min_cx || cx > area.max_cx || cz < area.min_cz || cz > area.max_cz)) return;
      s.pending.add(`${cx},${cz}`);
    };
    chunkListeners.add(s.listener);
    s.timer = setInterval(() => this.chunksFlush(), 250);
    this.chk = s;
    if (BRK.has('chunk-initial-dump')) {
      setImmediate(() => {
        if (this.closed || this.chk !== s) return;
        const all = [];
        for (const k of loaded) {
          const [dim, rest] = k.split('|');
          if (dim !== s.dim) continue;
          const [cx, cz] = rest.split(',').map(Number);
          if (!area || (cx >= area.min_cx && cx <= area.max_cx && cz >= area.min_cz && cz <= area.max_cz)) all.push(fakeSurface(dim, cx, cz));
        }
        for (let i = 0; i < all.length; i += MAX_CHUNKS_FRAME) this.emitChunks({ chunks: all.slice(i, i + MAX_CHUNKS_FRAME) });
      });
    }
    return { body: { ok: true, data: { sub } } };
  }

  dropChunks() {
    if (!this.chk) return;
    clearInterval(this.chk.timer);
    chunkListeners.delete(this.chk.listener);
    this.chk = null;
  }

  /** D23 flush: every 250 ms, chunks whose 2 s cooldown passed, <= 64 per frame, load mode above 64 waiting. */
  chunksFlush() {
    const s = this.chk;
    if (this.closed || !s) return;
    const now = Date.now();
    const ready = [];
    for (const k of [...s.pending]) {
      const [cx, cz] = k.split(',').map(Number);
      if (!loaded.has(ckey(s.dim, cx, cz))) {
        s.pending.delete(k);
        continue;
      }
      const last = s.lastSent.get(k);
      if (last === undefined || now - last >= COOLDOWN_MS) ready.push(k);
    }
    if (ready.length === 0 && s.dropped === 0) return;
    let send = ready;
    if (!BRK.has('chunk-oversize-frame')) {
      if (ready.length > MAX_CHUNKS_FRAME && now >= s.loadUntil) {
        s.loadUntil = now + 1000;
        s.loadRemaining = 16;
      }
      if (now < s.loadUntil) {
        send = ready.slice(0, s.loadRemaining);
        s.loadRemaining -= send.length;
        for (const k of ready.slice(send.length)) s.pending.delete(k);
        s.dropped += ready.length - send.length;
      }
    }
    const out = [];
    for (const k of send) {
      s.pending.delete(k);
      const [cx, cz] = k.split(',').map(Number);
      const c = fakeSurface(s.dim, cx, cz);
      if (s.lastHash.get(k) === c.hash) continue;
      s.lastHash.set(k, c.hash);
      s.lastSent.set(k, now);
      out.push(c);
    }
    if (out.length === 0 && s.dropped === 0) return;
    const per = BRK.has('chunk-oversize-frame') ? Infinity : MAX_CHUNKS_FRAME;
    const frames = [];
    for (let i = 0; i < Math.max(out.length, 1); i += per) frames.push({ chunks: out.slice(i, i + per) });
    if (s.dropped > 0) {
      frames[0].dropped = s.dropped;
      s.dropped = 0;
    }
    for (const f of frames) this.emitChunks(f);
    if (BRK.has('chunk-no-coalesce') && out.length > 0) {
      setTimeout(() => {
        if (!this.closed && this.chk === s) for (const f of frames) this.emitChunks({ chunks: f.chunks });
      }, 500);
    }
  }

  emitChunks(data) {
    const s = this.chk;
    const frame = { sub: s.sub, seq: s.seq, data };
    s.seq += 1;
    s.lastSentAt = Date.now();
    this.send(frame);
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
  if (activeConnections >= MAX_CONNECTIONS) {
    socket.destroy();
    return;
  }
  activeConnections += 1;
  socket.once('close', () => {
    activeConnections -= 1;
  });
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

