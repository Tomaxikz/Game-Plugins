#!/usr/bin/env node
// Repeatable cost benchmark for the Minecraft bridges (any platform).
//
//   node scripts/bench-bridge.mjs --root <server-root> [--n 64] [--single 20] [--json out.json]
//
// Per op:
//   burst  : N identical requests sent at once (spread over ceil(N/16) connections because max_inflight is 16),
//            wall time until the last response. "main ms/call" = (wall - 50) / N, i.e. the burst is assumed to
//            start inside one 50 ms tick; values <= 0 mean "below measurement". This approximates game-thread cost.
//   single : median / p95 latency of sequential calls on one connection (queue + game thread + encode + socket).
// Needs one online player (a bot); player-scoped ops are skipped without one.
import { readFileSync, writeFileSync, existsSync, readdirSync, readlinkSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BridgeClient } from '../../../conformance/lib/client.mjs';

const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const root = resolve(opt('--root', '.'));
const N = +opt('--n', 128);
const SINGLE = +opt('--single', 20);
const ROUNDS = +opt('--rounds', 3);
const only = opt('--only', null)?.split(',');
const jsonOut = opt('--json', null);
const logFile = opt('--log', join(root, 'server.log'));
// With -Dpm.taskstats=true the bridge logs "[pm-taskstats] tasks=N nanos=M" (game-thread time inside bridge tasks) when activity stops.
const readStats = async () => {
  if (!existsSync(logFile)) return null;
  await new Promise((r) => setTimeout(r, 1100));
  const m = [...readFileSync(logFile, 'utf8').matchAll(/\[pm-taskstats\] tasks=(\d+) nanos=(\d+)/g)].pop();
  return m ? { tasks: +m[1], nanos: +m[2] } : null;
};

const bridge = JSON.parse(readFileSync(join(root, '.playermanager', 'bridge.json'), 'utf8'));
let token = null;
if (bridge.transport === 'tcp') token = readFileSync(join(root, '.playermanager', 'token'), 'utf8').trim();

async function open() {
  const c = await BridgeClient.connect(bridge.transport === 'unix' ? { path: join(root, bridge.socket) } : { host: '127.0.0.1', port: bridge.port });
  const h = await c.request('hello', { panel_version: '1.0.0-bench', ...(token ? { token } : {}) });
  if (!h.ok) throw new Error('hello failed ' + JSON.stringify(h.error));
  c.hello = h.data;
  return c;
}

// ---- idle CPU of the bridge's own threads (pm-bridge-*), without and with one idle connection: ticks from /proc/<pid>/task/*/stat
const IDLE = +opt('--idle', 6);
function serverPid() {
  for (const d of readdirSync('/proc')) {
    if (!/^\d+$/.test(d)) continue;
    try { if (readlinkSync(`/proc/${d}/cwd`) === root && readFileSync(`/proc/${d}/comm`, 'utf8').startsWith('java')) return +d; } catch { /* gone or not ours */ }
  }
  return null;
}
function bridgeCpuTicks(pid) {
  let t = 0, threads = 0;
  for (const tid of readdirSync(`/proc/${pid}/task`)) {
    try {
      const st = readFileSync(`/proc/${pid}/task/${tid}/stat`, 'utf8');
      const name = st.slice(st.indexOf('(') + 1, st.lastIndexOf(')'));
      if (!name.startsWith('pm-bridge')) continue;
      const f = st.slice(st.lastIndexOf(')') + 2).split(' ');
      t += +f[11] + +f[12];
      threads++;
    } catch { /* thread ended */ }
  }
  return { t, threads };
}
const idleReport = {};
const spid = process.platform === 'linux' && IDLE > 0 ? serverPid() : null;
async function idleCpu(label) {
  if (!spid) return;
  await new Promise((r) => setTimeout(r, 500));
  const s0 = await readStats();
  const a = bridgeCpuTicks(spid);
  await new Promise((r) => setTimeout(r, IDLE * 1000));
  const b = bridgeCpuTicks(spid);
  const s1 = await readStats();
  idleReport[label] = { cpu_ms: (b.t - a.t) * 10, seconds: IDLE, threads: b.threads, game_thread_tasks: s0 && s1 ? s1.tasks - s0.tasks : undefined };
}
await idleCpu('no panel connected');
const main = await open();
await idleCpu('one idle connection');
const ops = main.hello.capabilities.ops;
const pl = await main.request('players.list', {});
const player = pl.data.players.find((p) => p.online);
const world = main.hello.capabilities.worlds?.[0];

const cases = [];
const add = (name, op, a) => { if (ops.includes(op) && (!only || only.includes(name))) cases.push({ name, op, args: a }); };
add('server.info', 'server.info', {});
add('players.list', 'players.list', {});
add('players.list+sens', 'players.list', { include_sensitive: true });
if (player) {
  const id = player.id;
  const base = ['world', 'dimension', 'position', 'health', 'max_health', 'armor', 'held_item', 'vehicle', 'game_mode', 'food', 'saturation', 'experience', 'ping'];
  add('player.get (no game)', 'player.get', { id, fields: base });
  add('player.get game (no stats/adv)', 'player.get', { id, fields: ['game'] });
  add('player.get game.statistics', 'player.get', { id, fields: ['game.statistics'] });
  add('player.get game.advancements', 'player.get', { id, fields: ['game.advancements'], include_unobtained: true });
  add('player.get ALL', 'player.get', { id, include_unobtained: true });
  add('player.inventory', 'player.inventory', { id });
  add('player.nearby r16', 'player.nearby', { id, radius: 16 });
  add('player.nearby r32+blocks', 'player.nearby', { id, radius: 32, blocks: true });
  const inv = await main.request('player.inventory', { id });
  const slot = inv.ok && inv.data.containers.find((c) => c.id === 'inventory')?.slots?.[0]?.slot;
  if (slot !== undefined) add('item.get', 'item.get', { id, container: 'inventory', slot });
  const g = await main.request('player.get', { id, fields: ['world', 'dimension', 'position'] });
  if (g.ok && ops.includes('map.chunks')) {
    const cx = Math.floor(g.data.position.x / 16), cz = Math.floor(g.data.position.z / 16);
    const chunks = [];
    for (let x = -4; x <= 4; x++) for (let z = -4; z <= 4; z++) chunks.push([cx + x, cz + z]);
    add('map.chunks 81', 'map.chunks', { world: g.data.world, dimension: g.data.dimension, chunks });
  }
}
if (ops.includes('assets.manifest')) add('assets.manifest', 'assets.manifest', {});

const conns = [main];
for (let i = 1; i < Math.ceil(N / 16); i++) conns.push(await open());
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const med = (a) => a.slice().sort((x, y) => x - y)[Math.floor(a.length / 2)];

async function burst(c) {
  const per = Math.ceil(N / conns.length);
  const t0 = performance.now();
  const ps = [];
  let fails = 0;
  for (let i = 0; i < conns.length; i++) {
    for (let j = 0; j < per; j++) ps.push(conns[i].request(c.op, c.args, { timeout: 20000 }).then((r) => { if (!r.ok) fails++; return r; }));
  }
  await Promise.all(ps);
  return { wall: performance.now() - t0, count: ps.length, fails };
}

const rows = [];
let prev = await readStats();
for (const c of cases) {
  const probe = await main.request(c.op, c.args, { timeout: 20000 });
  if (!probe.ok) { rows.push({ name: c.name, error: probe.error?.code + ': ' + probe.error?.message }); continue; }
  await burst(c); // warm-up (JIT, caches)
  const walls = [];
  let fails = 0, count = 0;
  for (let r = 0; r < ROUNDS; r++) { await sleep(300); const b = await burst(c); walls.push(b.wall); fails += b.fails; count = b.count; }
  const lat = [];
  for (let i = 0; i < SINGLE; i++) { await sleep(20 + Math.random() * 40); const t0 = performance.now(); await main.request(c.op, c.args, { timeout: 20000 }); lat.push(performance.now() - t0); }
  const wall = med(walls);
  const calls = 1 + count * (ROUNDS + 1) + SINGLE;
  const st = await readStats();
  let taskMs, tasksPer;
  if (st && prev) { taskMs = (st.nanos - prev.nanos) / 1e6 / calls; tasksPer = (st.tasks - prev.tasks) / calls; }
  prev = st;
  rows.push({ name: c.name, taskMs, tasksPer, wall, perCall: Math.max(0, (wall - 50) / count), single: med(lat), p95: lat.slice().sort((a, b) => a - b)[Math.floor(lat.length * 0.95)], fails });
}

const f = (x, d = 2) => (x === undefined ? '-' : x.toFixed(d));
console.log(`\nbench: ${main.hello.platform} ${main.hello.platform_version}  N=${N} rounds=${ROUNDS} single=${SINGLE}`);
console.log('op'.padEnd(34) + 'burst wall ms'.padStart(15) + 'wall-50 ms/call'.padStart(16) + 'task ms/call'.padStart(14) + 'tasks'.padStart(7) + 'single p50'.padStart(12) + 'single p95'.padStart(12) + '  fails');
for (const r of rows) {
  if (r.error) console.log(r.name.padEnd(34) + '  ERROR ' + r.error);
  else console.log(r.name.padEnd(34) + f(r.wall, 1).padStart(15) + f(r.perCall, 3).padStart(16) + f(r.taskMs, 4).padStart(14) + f(r.tasksPer, 1).padStart(7) + f(r.single).padStart(12) + f(r.p95).padStart(12) + '  ' + r.fails);
}
for (const [k, v] of Object.entries(idleReport)) console.log(`idle bridge CPU, ${k}: ${v.cpu_ms} ms over ${v.seconds} s (${v.threads} pm-bridge threads), bridge tasks on the game thread: ${v.game_thread_tasks ?? 'n/a'}`);
if (jsonOut) writeFileSync(jsonOut, JSON.stringify({ hello: { platform: main.hello.platform, version: main.hello.platform_version }, N, rows, idle: idleReport }, null, 1));
for (const c of conns) c.close();
await sleep(200);
process.exit(0);
