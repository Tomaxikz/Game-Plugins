'use strict';
// Micro-benchmarks for the main-thread cost of the bridge. Run:  node plugins/fivem/test/bench.js
// FXServer runs resource JS on its main thread, so JS time here == main-thread time there. Native calls are counted
// (a real native crosses the JS/C++ boundary, costing far more than the fake O(1) lookups used here).
const { performance } = require('perf_hooks');
const lib = process.env.BENCH_LIB ? process.env.BENCH_LIB + '/' : '../playermanager_bridge/server/lib/';
const ref = require('./ref-msgpack');
const cur = require(lib + 'msgpack');
const { createFiveMGame } = require(lib + 'fivem-game');
const { BridgeCore } = require(lib + 'bridge');
const { DEFAULTS, sanitise } = require(lib + 'config');

const N = Number(process.env.BENCH_PLAYERS || 500);
const ITER = Number(process.env.BENCH_ITER || 200);

function time(fn, iter) {
  for (let i = 0; i < Math.min(20, iter); i++) fn(); // warm-up
  let best = Infinity; // best of 5 batches: robust against noisy neighbours
  for (let r = 0; r < 5; r++) {
    const t0 = performance.now();
    for (let i = 0; i < iter; i++) fn();
    best = Math.min(best, (performance.now() - t0) / iter);
  }
  return best;
}
const row = (name, ms, extra = '') => console.log(name.padEnd(46) + ms.toFixed(4).padStart(10) + ' ms' + (extra ? '   ' + extra : ''));

// ---- 1. encoder -----------------------------------------------------------
const frame = { sub: 1, seq: 7, data: { key: false, players: Array.from({ length: N }, (_, i) => ({
  id: 'license:' + (0x1000000000 + i).toString(16).padStart(16, '0'), name: 'Player' + i, world: 'gta5',
  x: -16000 + i * 31, y: 40000 - i * 17, z: 220, yaw: 12345, state: 'alive' })) } };
const list = { id: 3, ok: true, data: { players: frame.data.players.map((p, i) => ({ id: p.id, name: p.name, online: true, ping: 40 + (i % 50), world: 'gta5', groups: [], game: { server_id: i + 1 } })) } };
const rec = { id: 'water', count: 3, slot: 1, components: { label: 'Water', weight: 500, metadata: { a: 1, b: 'two', c: [1, 2, 3] } } };
const sameBytes = (a, b) => Buffer.compare(ref.encode(a, b), cur.encode(a, b)) === 0;
console.log(`# encoder (${N} players)`);
for (const [n, v, o] of [['position frame', frame], ['players.list reply', list], ['canonical record', rec, { sortKeys: true, canonical: true }]]) {
  const a = time(() => ref.encode(v, o), ITER), b = time(() => cur.encode(v, o), ITER);
  row('encode ' + n + ' (reference)', a); row('encode ' + n + ' (current)', b, (a / b).toFixed(1) + 'x faster, identical=' + sameBytes(v, o));
}

// ---- 2. sampler -------------------------------------------------------------
const counts = new Map();
const players = new Map();
const g = {};
function native(name, fn) { g[name] = (...a) => { counts.set(name, (counts.get(name) || 0) + 1); return fn(...a); }; }
for (let i = 1; i <= N; i++) players.set(i, { ped: 10000 + i, name: 'Player' + i, ids: ['license:' + i.toString(16).padStart(16, '0'), 'steam:1100001' + i, 'discord:' + i, 'fivem:' + i], c: [i * 1.5, -i * 2.25, 30.5], hp: 200 });
const byPed = new Map([...players].map(([s, p]) => [p.ped, p]));
native('GetPlayers', () => [...players.keys()].map(String));
g.getPlayers = g.GetPlayers;
native('GetNumPlayerIdentifiers', (s) => players.get(Number(s)).ids.length);
native('GetPlayerIdentifier', (s, i) => players.get(Number(s)).ids[i]);
g.getPlayerIdentifiers = (s) => { counts.set('getPlayerIdentifiers(JS helper: 1 + 2n natives)', (counts.get('getPlayerIdentifiers(JS helper: 1 + 2n natives)') || 0) + 1); return players.get(Number(s)).ids.slice(); };
native('GetPlayerName', (s) => players.get(s).name);
native('GetPlayerPed', (s) => players.get(s).ped);
native('GetPlayerPing', () => 40);
native('GetPlayerRoutingBucket', () => 0);
native('GetEntityCoords', (e) => byPed.get(e).c.slice());
native('GetEntityHeading', () => 90.5);
native('GetEntityHealth', (e) => byPed.get(e).hp);
native('GetEntityMaxHealth', () => 200);
native('GetPedArmour', () => 0);
native('GetSelectedPedWeapon', () => 0);
native('GetEntityModel', () => 1885233650);
native('GetVehiclePedIsIn', () => 0);
native('GetResourceState', () => 'missing');
native('GetConvar', (n, d) => (n === 'onesync' ? 'on' : d));
native('GetConvarInt', (n, d) => d);
native('GetGameName', () => 'gta5');
const game = createFiveMGame({ g, fs: require('fs'), resourceName: 'playermanager_bridge', platform: 'fivem' });
const onMain = (fn) => new Promise((res, rej) => setImmediate(() => { try { res(fn()); } catch (e) { rej(e); } }));
const core = new BridgeCore({ game, config: sanitise(DEFAULTS), bridgeVersion: '1.0.0', onMain });
core.caps = core.computeCaps();
let frames = 0, bytes = 0;
const conn = { id: 1, hello: true, positions: null, events: null, nextSub() { return 1; }, sendRaw(o) { frames++; bytes += cur.encode(o).length; } };
core.positionsSubscribe(conn, { interval_ms: 250 });
core.maybeStopSampler(); core.samplerTimer && clearInterval(core.samplerTimer); core.samplerTimer = null; // drive manually
console.log(`\n# position sampler, ${N} players, one subscriber`);
(async () => {
  async function tick() { await core.sampleTick(); }
  await tick(); // first tick: keyframe + caches
  counts.clear(); frames = 0;
  const T = 100;
  // a moving world: positions change by a little for ~half the players each tick
  const t0 = performance.now();
  let accMain = 0;
  for (let i = 0; i < T; i++) {
    for (let k = 1; k <= N; k += 2) players.get(k).c[0] += 0.5;
    for (const sub of core.positionSubs) sub.lastSampleAt = 0; // newer cores rate-limit per subscriber
    const s = performance.now();
    await tick();
    accMain += performance.now() - s;
  }
  const per = accMain / T;
  row('sampleTick wall (incl. diff + encode of frame)', per, `frames=${frames}`);
  let total = 0; for (const v of counts.values()) total += v;
  console.log('native calls per sample:'); for (const [k, v] of [...counts].sort()) console.log('   ' + k.padEnd(60) + String(Math.round(v / T)).padStart(6));
  console.log('   TOTAL'.padEnd(63) + String(Math.round(total / T)).padStart(6));
  // players.list + player.get
  counts.clear();
  core.playersList({});
  const nat = [...counts.values()].reduce((a, b) => a + b, 0);
  const lt = time(() => core.playersList({}), 50);
  row('players.list handler', lt, 'natives/call=' + nat);
  // ten panel users asking for the same list at once
  counts.clear();
  const c2 = { id: 9, hello: true, positions: null, events: null, nextSub() { return 2; }, sendRaw() {} };
  const t1 = performance.now();
  await Promise.all(Array.from({ length: 10 }, () => core.handle(c2, { op: 'players.list', args: {} })));
  const nat2 = [...counts.values()].reduce((a, b) => a + b, 0);
  row('10 identical players.list requests', performance.now() - t1, 'natives total=' + nat2);
  process.exit(0);
})();
