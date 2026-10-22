#!/usr/bin/env node
// Live check of pack.manifest / pack.get against a running Minecraft bridge (any platform).
//
//   node scripts/pack-hook.mjs --root <server-root> [--layers] [--scale <files>] [--mods <dir> [--extract <tmp-dir>]] [--json out.json]
//
// Reads what the server really lists (all pages), then verifies by itself, independently of the Java code:
//   * the path rule for every listed path, bytewise sorted unique paths, hash/size shape,
//   * the listing hash (canonical MessagePack of the sorted [path, hash] list, first 16 hex of SHA-256),
//   * pack.get for every listed file in batches of 64: bytes hash to the manifest hash and match the size,
//     request order, missing / deferred / too_large accounting, invalid_args cases, `busy` during the first index.
// With --layers it also writes its own generated packs (solid colours made here, no third-party art) under
// <root>/plugins/PMPackTest and edits server.properties to exercise the Bukkit precedence stack
// (generic scan < Oraxen/Nexo/... folders < local resource-pack < pack matching resource-pack-sha1), and a live change.
// With --mods <dir> (mod loaders) it unzips every jar in <dir>, including nested META-INF/jars/*.jar, into --extract <tmp-dir> (deleted afterwards)
// and checks that the listing holds exactly the valid asset paths of those jars (plus <root>/pm-test-pack), each with the hash of one of the
// candidate files of that path.
// With --scale N it adds N small files to the generic pack to time the index build.
// Exit code 0 when everything passed.
import { createHash } from 'node:crypto';
import { deflateRawSync, crc32 } from 'node:zlib';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { BridgeClient } from '../../../conformance/lib/client.mjs';
import { hash16 } from '../../../conformance/lib/canonical.mjs';

const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const flag = (k) => args.includes(k);
const root = resolve(opt('--root', '.'));
const SCALE = +opt('--scale', 0);
const LAYERS = flag('--layers');
const jsonOut = opt('--json', null);
const MODS = opt('--mods', null);
const EXTRACT = opt('--extract', null);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let passed = 0;
let failed = 0;
const results = {};
function check(name, cond, extra = '') {
  if (cond) passed++; else failed++;
  console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${extra ? ' - ' + extra : ''}`);
}

// ---------------------------------------------------------------- independent model of the contract
const sha256 = (b) => createHash('sha256').update(b).digest('hex');
const fileHash = (b) => sha256(b).slice(0, 16);
const bytewise = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b));
const SEG = /^[a-z0-9_.-]+$/;
function validPath(p) {
  if (typeof p !== 'string' || Buffer.byteLength(p) > 512) return false;
  const parts = p.split('/');
  if (parts.length < 4 || parts[0] !== 'assets') return false;
  const [, ns, kind, ...rest] = parts;
  if (!SEG.test(ns) || ns === '.' || ns === '..') return false;
  if (!['blockstates', 'models', 'textures'].includes(kind)) return false;
  for (const s of rest) if (!SEG.test(s) || s === '.' || s === '..') return false;
  return kind === 'textures' ? p.endsWith('.png') || p.endsWith('.png.mcmeta') : p.endsWith('.json');
}

/** Minimal zip writer (deflate), so the hook needs no zip tool. */
function zipOf(files) {
  const locals = [];
  const central = [];
  let offset = 0;
  for (const [name, data] of Object.entries(files)) {
    const n = Buffer.from(name);
    const comp = deflateRawSync(data);
    const crc = crc32(data);
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0x0800, 6); lh.writeUInt16LE(8, 8);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(comp.length, 18); lh.writeUInt32LE(data.length, 22); lh.writeUInt16LE(n.length, 26);
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0x0800, 8); ch.writeUInt16LE(8, 10);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(comp.length, 20); ch.writeUInt32LE(data.length, 24); ch.writeUInt16LE(n.length, 28); ch.writeUInt32LE(offset, 42);
    locals.push(lh, n, comp);
    central.push(ch, n);
    offset += 30 + n.length + comp.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(Object.keys(files).length, 8); end.writeUInt16LE(Object.keys(files).length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

function png(w, h, seed) {
  // not a decodable image on purpose is fine for pack.*, but keep a valid signature + IHDR so tools do not choke
  const raw = Buffer.alloc(w * h * 4);
  let x = seed >>> 0 || 1;
  for (let i = 0; i < raw.length; i++) { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; raw[i] = x >>> 24; }
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), raw]);
}

function walkPack(dir, base = dir, out = new Map()) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const f = join(dir, e.name);
    if (e.isDirectory()) walkPack(f, base, out);
    else {
      const rel = f.slice(base.length + 1).split('\\').join('/');
      if (validPath(rel)) out.set(rel, readFileSync(f));
    }
  }
  return out;
}

/** Valid asset paths of a jar and of the jars nested in META-INF/jars, as path -> Set(hash). */
function jarCandidates(jar, tmp, out, depth = 0) {
  const dir = join(tmp, String(out.counter++));
  mkdirSync(dir, { recursive: true });
  try { execFileSync('unzip', ['-qo', jar, 'assets/*', 'META-INF/jars/*', '-d', dir], { stdio: 'ignore' }); } catch { /* no such entries: unzip exits 11 */ }
  for (const [p, b] of walkPack(dir)) {
    if (!out.map.has(p)) out.map.set(p, new Set());
    out.map.get(p).add(fileHash(b));
  }
  const nested = join(dir, 'META-INF', 'jars');
  if (existsSync(nested) && depth < 3) for (const n of readdirSync(nested)) if (n.endsWith('.jar')) { out.nested++; jarCandidates(join(nested, n), tmp, out, depth + 1); }
}

// ---------------------------------------------------------------- connection
const bridge = JSON.parse(readFileSync(join(root, '.playermanager', 'bridge.json'), 'utf8'));
const token = bridge.transport === 'tcp' ? readFileSync(join(root, '.playermanager', 'token'), 'utf8').trim() : null;
const c = await BridgeClient.connect(bridge.transport === 'unix' ? { path: join(root, bridge.socket) } : { host: '127.0.0.1', port: bridge.port });
const hello = await c.request('hello', { panel_version: '1.0.0-pack-hook', ...(token ? { token } : {}) });
if (!hello.ok) { console.error('hello failed', hello.error); process.exit(2); }
const caps = hello.data.capabilities;
const maxFrame = hello.data.limits.max_frame;
console.log(`platform ${hello.data.platform} ${hello.data.platform_version}, game ${hello.data.game_version}`);

const hasOps = caps.ops.includes('pack.manifest') && caps.ops.includes('pack.get');
check('hello: pack.manifest and pack.get advertised together with feature "pack"', hasOps && caps.features.includes('pack'));
if (!hasOps) process.exit(1);

const T = 10000;
const call = (op, a) => c.request(op, a, { timeout: T });

/** pack.manifest until not busy; returns { pages, files, hash, busy, ms }. */
async function listing(label = 'listing') {
  const t0 = Date.now();
  let busy = 0;
  const files = [];
  let hash = null;
  let cursor;
  let pages = 0;
  for (;;) {
    const r = await call('pack.manifest', cursor === undefined ? {} : { cursor });
    if (!r.ok) {
      if (r.error.code === 'busy' && Date.now() - t0 < 120000) { busy++; await sleep(250); continue; }
      throw new Error(`${label}: pack.manifest failed ${JSON.stringify(r.error)}`);
    }
    pages++;
    if (hash === null) hash = r.data.hash;
    else if (hash !== r.data.hash) { hash = null; files.length = 0; cursor = undefined; pages = 0; continue; }
    files.push(...r.data.files);
    if (r.data.next === undefined || r.data.next === null) break;
    cursor = r.data.next;
  }
  return { pages, files, hash, busy, ms: Date.now() - t0 };
}

function verifyListing(l, name) {
  const errs = [];
  let prev = null;
  for (const f of l.files) {
    if (!validPath(f.path)) errs.push(`bad path ${f.path}`);
    if (prev !== null && bytewise(prev, f.path) >= 0) errs.push(`order ${prev} / ${f.path}`);
    prev = f.path;
    if (!/^[0-9a-f]{16}$/.test(f.hash)) errs.push(`hash ${f.path}`);
    if (!Number.isInteger(Number(f.size)) || Number(f.size) < 0) errs.push(`size ${f.path}`);
    if (Object.keys(f).sort().join() !== 'hash,path,size') errs.push(`keys ${f.path}`);
  }
  check(`${name}: ${l.files.length} files, paths valid, sorted, unique, shape ok`, errs.length === 0, errs.slice(0, 3).join('; '));
  const want = hash16(l.files.map((f) => [f.path, f.hash]));
  check(`${name}: listing hash ${l.hash} equals canonical MessagePack hash`, want === l.hash, want);
}

/** Fetches every listed file; verifies bytes against the manifest. Returns ms. */
async function fetchAll(l, name) {
  const t0 = Date.now();
  const left = l.files.map((f) => f.path);
  const bySize = new Map(l.files.map((f) => [f.path, f]));
  let bytes = 0, calls = 0, ok = 0, bad = [], tooLarge = 0;
  while (left.length) {
    const batch = left.splice(0, 64);
    let todo = batch;
    while (todo.length) {
      const r = await call('pack.get', { paths: todo });
      calls++;
      if (!r.ok) {
        if (r.error.code === 'busy') { await sleep(250); continue; }
        throw new Error(`pack.get failed ${JSON.stringify(r.error)}`);
      }
      const d = r.data;
      const seen = new Set();
      const order = [];
      for (const f of d.files) {
        const e = bySize.get(f.path);
        const data = Buffer.from(f.data);
        order.push(f.path);
        seen.add(f.path);
        bytes += data.length;
        if (!e || fileHash(data) !== e.hash || data.length !== Number(e.size)) bad.push(f.path); else ok++;
      }
      for (const p of d.missing) { seen.add(p); bad.push('missing ' + p); }
      for (const p of d.too_large ?? []) { seen.add(p); tooLarge++; if (Number(bySize.get(p).size) <= 3 * 1024 * 1024) bad.push('too_large but small ' + p); }
      for (const p of d.deferred ?? []) seen.add(p);
      if (seen.size !== new Set(todo).size || [...seen].some((p) => !todo.includes(p))) bad.push('accounting ' + JSON.stringify([...todo]));
      if (order.join() !== todo.filter((p) => order.includes(p)).join()) bad.push('order');
      todo = d.deferred ?? [];
    }
  }
  const ms = Date.now() - t0;
  check(`${name}: pack.get served ${ok} files byte-exact (${(bytes / 1048576).toFixed(1)} MiB in ${calls} calls, ${ms} ms, ${tooLarge} too_large)`, bad.length === 0, bad.slice(0, 3).join('; '));
  return { ms, bytes, calls, ok, tooLarge };
}

// ---------------------------------------------------------------- test packs (--layers)
const base = join(root, 'plugins', 'PMPackTest');
const zips = join(root, 'pm-packs');             // outside plugins/: the generic scan must not pick these two up as lowest layer
const png1 = (n) => png(8, 8, n);
const lowSharedPng = png1(1);
const json = (o) => Buffer.from(JSON.stringify(o));
function put(dir, rel, data) {
  const f = join(dir, rel);
  mkdirSync(join(f, '..'), { recursive: true });
  writeFileSync(f, data);
}

const propsFile = join(root, 'server.properties');
function setProps(kv) {
  let s = readFileSync(propsFile, 'utf8').split('\n').filter((l) => !Object.keys(kv).some((k) => l.startsWith(k + '=')));
  s = s.filter((l, i) => !(i === s.length - 1 && l === ''));
  for (const [k, v] of Object.entries(kv)) if (v !== null) s.push(`${k}=${v}`);
  writeFileSync(propsFile, s.join('\n') + '\n');
}

/** Waits until the listing hash differs from `from` (the source list is re-checked at most once a second). */
async function changed(from, label) {
  const t0 = Date.now();
  let sawBusy = false;
  for (;;) {
    const r = await call('pack.manifest', {});
    if (!r.ok && r.error.code === 'busy') sawBusy = true;
    if (r.ok && r.data.hash !== from) return { ms: Date.now() - t0, sawBusy };
    if (Date.now() - t0 > 60000) throw new Error(`${label}: listing hash did not change`);
    await sleep(100);
  }
}

if (LAYERS) {
  const low = join(base, 'low');
  rmSync(base, { recursive: true, force: true });
  rmSync(zips, { recursive: true, force: true });
  put(low, 'pack.mcmeta', json({ pack: { pack_format: 46, description: 'pm test' } }));
  put(low, 'assets/pmpack/blockstates/lowblock.json', json({ variants: { '': { model: 'pmpack:block/lowblock' } } }));
  put(low, 'assets/pmpack/models/block/lowblock.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'pmpack:block/lowblock' } }));
  put(low, 'assets/pmpack/textures/block/lowblock.png', png1(2));
  put(low, 'assets/pmpack/textures/block/lowblock.png.mcmeta', json({ animation: { frametime: 2 } }));
  put(low, 'assets/pmpack/textures/block/shared.png', lowSharedPng);                   // overridden by every higher layer in turn
  put(low, 'assets/pmpack/models/block/shared.json', json({ layer: 'low' }));
  put(low, 'assets/pmpack/models/block/deep/a/b/c/d/e.json', json({ deep: true }));
  put(low, 'assets/minecraft/textures/block/stone.png', png1(3));                       // an operator pack may replace a vanilla texture
  // never listed: not on the path rule
  put(low, 'assets/pmpack/sounds/boom.ogg', Buffer.from('ogg'));
  put(low, 'assets/pmpack/sounds.json', json({}));
  put(low, 'assets/pmpack/lang/en_us.json', json({}));
  put(low, 'assets/pmpack/items/low.json', json({ model: {} }));
  put(low, 'assets/pmpack/font/default.json', json({}));
  put(low, 'assets/pmpack/shaders/core/x.json', json({}));
  put(low, 'assets/pmpack/atlases/blocks.json', json({}));
  put(low, 'assets/pmpack/models/block/Upper.json', json({}));
  put(low, 'assets/pmpack/textures/block/Upper.png', png1(4));
  put(low, 'assets/pmpack/textures/block/x.png.bak', png1(5));
  put(low, 'assets/pmpack/models/block/x.png', png1(5));
  put(low, 'assets/pmpack/textures/block/sp ace.png', png1(5));
  put(low, 'data/pmpack/loot_table/a.json', json({}));
  // sizes around the 3 MiB single-frame limit
  put(low, 'assets/pmpack/textures/big/exact3mib.png', Buffer.concat([png1(6), Buffer.alloc(3 * 1024 * 1024 - 8 - 8 * 8 * 4)]));
  put(low, 'assets/pmpack/textures/big/over3mib.png', Buffer.alloc(3 * 1024 * 1024 + 1, 7));
  put(low, 'assets/pmpack/textures/big/two_mib_a.png', Buffer.alloc(2 * 1024 * 1024, 8));
  put(low, 'assets/pmpack/textures/big/two_mib_b.png', Buffer.alloc(2 * 1024 * 1024, 9));
  put(low, 'assets/pmpack/textures/big/zero.png', Buffer.alloc(0));
  if (SCALE > 0) for (let i = 0; i < SCALE; i++) put(low, `assets/pmscale/textures/block/t${String(i).padStart(6, '0')}.png`, png1(1000 + i));

  const server = zipOf({
    'pack.mcmeta': json({ pack: { pack_format: 46, description: 'server' } }),
    'assets/pmpack/textures/block/shared.png': png1(21),
    'assets/pmpack/models/block/shared.json': json({ layer: 'server' }),
    'assets/pmpack/models/block/server_only.json': json({ layer: 'server' }),
  });
  const sent = zipOf({
    'pack.mcmeta': json({ pack: { pack_format: 46, description: 'sent' } }),
    'assets/pmpack/models/block/shared.json': json({ layer: 'sent' }),
    'assets/pmpack/models/block/sent_only.json': json({ layer: 'sent' }),
  });
  mkdirSync(zips, { recursive: true });
  writeFileSync(join(zips, 'server.zip'), server);
  writeFileSync(join(zips, 'sent.zip'), sent);
  console.log(`generated test packs under ${base} and ${zips}`);
}

const T0 = Date.now();
const first = await listing('first listing');
results.firstListing = { files: first.files.length, pages: first.pages, busyAnswers: first.busy, ms: first.ms, sinceHook: Date.now() - T0 };
console.log(`first listing: ${first.files.length} files in ${first.pages} page(s), ${first.busy} busy answer(s), ${first.ms} ms`);
verifyListing(first, 'initial');
results.fetch1 = await fetchAll(first, 'initial');

// ---- repeatability and cost of a cached listing
{
  const t = [];
  for (let i = 0; i < 20; i++) { const t0 = process.hrtime.bigint(); await call('pack.manifest', {}); t.push(Number(process.hrtime.bigint() - t0) / 1e6); }
  t.sort((a, b) => a - b);
  results.manifestCachedMs = { median: +t[10].toFixed(2), max: +t[19].toFixed(2) };
  console.log(`cached first page: median ${t[10].toFixed(2)} ms, max ${t[19].toFixed(2)} ms`);
}

// ---- argument errors
for (const [name, op, a] of [
  ['pack.get without paths', 'pack.get', {}],
  ['pack.get empty paths', 'pack.get', { paths: [] }],
  ['pack.get 65 paths', 'pack.get', { paths: Array.from({ length: 65 }, (_, i) => `assets/a/models/m${i}.json`) }],
  ['pack.get traversal', 'pack.get', { paths: ['assets/a/models/../../../x.json'] }],
  ['pack.get uppercase', 'pack.get', { paths: ['assets/a/models/A.json'] }],
  ['pack.get lang file', 'pack.get', { paths: ['assets/a/lang/en_us.json'] }],
  ['pack.get one bad path in a valid batch', 'pack.get', { paths: ['assets/minecraft/textures/block/stone.png', '/etc/passwd'] }],
  ['pack.manifest bad cursor', 'pack.manifest', { cursor: '../../etc/passwd' }],
  ['pack.manifest numeric cursor', 'pack.manifest', { cursor: 7 }],
]) {
  const r = await call(op, a);
  check(`${name} -> invalid_args`, !r.ok && r.error.code === 'invalid_args', r.ok ? 'ok' : r.error.code);
}

// ---- paging: follow cursors one entry's worth at a time is not controllable, so check the cursor semantics on the real listing
{
  const mid = first.files[Math.floor(first.files.length / 2)];
  const r = await call('pack.manifest', { cursor: mid.path });
  const idx = first.files.findIndex((f) => f.path === mid.path);
  check('cursor continues strictly after the given path', r.ok && r.data.files[0]?.path === first.files[idx + 1]?.path, r.ok ? '' : r.error.code);
  const end = await call('pack.manifest', { cursor: first.files[first.files.length - 1].path });
  check('cursor at the last path gives an empty last page', end.ok && end.data.files.length === 0 && end.data.next == null);
}

if (LAYERS) {
  const want = new Map();
  const merge = (m) => { for (const [k, v] of m) want.set(k, v); };
  merge(walkPack(join(base, 'low')));
  merge(walkPack(join(root, 'plugins', 'Oraxen', 'pack')));           // known generated-pack folder (seed-test-pack.mjs)
  const modelOf = (l) => new Map(l.files.map((f) => [f.path, f]));
  const got = modelOf(first);
  const diffs = [];
  for (const [p, b] of want) { const g = got.get(p); if (!g || g.hash !== fileHash(b) || Number(g.size) !== b.length) diffs.push(p); }
  for (const p of got.keys()) if (!want.has(p)) diffs.push('extra ' + p);
  check('Bukkit stack: generic scan < Oraxen folder equals the independent model', diffs.length === 0, diffs.slice(0, 4).join('; '));
  for (const p of ['assets/pmpack/sounds/boom.ogg', 'assets/pmpack/lang/en_us.json', 'assets/pmpack/items/low.json', 'assets/pmpack/models/block/Upper.json', 'pack.mcmeta']) {
    check(`excluded and not listed: ${p}`, !got.has(p));
  }
  const over = got.get('assets/pmpack/textures/big/over3mib.png');
  check('3 MiB + 1 file is listed with its size', over && Number(over.size) === 3 * 1024 * 1024 + 1);
  const empty = await call('pack.get', { paths: ['assets/pmpack/textures/big/over3mib.png', 'assets/pmpack/textures/big/zero.png', 'assets/pmpack/models/block/nope.json'] });
  check('pack.get: too_large / zero-length / missing accounted', empty.ok && JSON.stringify(empty.data.too_large) === '["assets/pmpack/textures/big/over3mib.png"]'
    && empty.data.files.length === 1 && empty.data.files[0].data.length === 0 && JSON.stringify(empty.data.missing) === '["assets/pmpack/models/block/nope.json"]', JSON.stringify(Object.keys(empty.data ?? {})));
  const two = await call('pack.get', { paths: ['assets/pmpack/textures/big/two_mib_a.png', 'assets/pmpack/textures/big/two_mib_b.png', 'assets/pmpack/textures/block/lowblock.png'] });
  check('pack.get: 2 MiB + 2 MiB do not fit one frame; the rest is deferred in order', two.ok && two.data.files.length === 1 && JSON.stringify(two.data.deferred)
    === '["assets/pmpack/textures/big/two_mib_b.png","assets/pmpack/textures/block/lowblock.png"]', two.ok ? JSON.stringify(two.data.deferred) : two.error.code);
  const exact = await call('pack.get', { paths: ['assets/pmpack/textures/big/exact3mib.png'] });
  check('pack.get: a file of exactly 3 MiB is served', exact.ok && exact.data.files.length === 1 && exact.data.files[0].data.length === 3 * 1024 * 1024);

  // ---- layer 3: local resource-pack of server.properties
  const h0 = first.hash;
  setProps({ 'resource-pack': 'pm-packs/server.zip' });
  const c3 = await changed(h0, 'resource-pack');
  const l3 = await listing('resource-pack');
  verifyListing(l3, 'resource-pack');
  const m3 = modelOf(l3);
  const serverZip = new Map([
    ['assets/pmpack/textures/block/shared.png', png1(21)],
    ['assets/pmpack/models/block/shared.json', json({ layer: 'server' })],
    ['assets/pmpack/models/block/server_only.json', json({ layer: 'server' })],
  ]);
  const ok3 = [...serverZip].every(([p, b]) => m3.get(p)?.hash === fileHash(b));
  check(`local resource-pack zip is on top of the folders (re-index seen after ${c3.ms} ms, busy seen: ${c3.sawBusy})`, ok3);
  const g3 = await call('pack.get', { paths: ['assets/pmpack/models/block/shared.json'] });
  check('whole-file replacement: the server pack file, not a merge', g3.ok && Buffer.from(g3.data.files[0].data).toString() === '{"layer":"server"}');

  // ---- layer 4: the pack matching resource-pack-sha1 (remote URL only; the local zip is found by SHA-1)
  const sentSha1 = createHash('sha1').update(readFileSync(join(zips, 'sent.zip'))).digest('hex');
  setProps({ 'resource-pack': 'https://example.invalid/pm-test-pack.zip', 'resource-pack-sha1': sentSha1 });
  const c4 = await changed(l3.hash, 'sha1');
  const l4 = await listing('sha1');
  verifyListing(l4, 'sent pack');
  const m4 = modelOf(l4);
  check(`pack matching resource-pack-sha1 is the top layer (seen after ${c4.ms} ms)`, m4.get('assets/pmpack/models/block/shared.json')?.hash === fileHash(json({ layer: 'sent' }))
    && m4.has('assets/pmpack/models/block/sent_only.json') && !m4.has('assets/pmpack/models/block/server_only.json'),
  'remote-only URL: the local resource-pack zip no longer counts');
  check('lower layers still show through where the top pack has no file', m4.get('assets/pmpack/textures/block/shared.png')?.hash === fileHash(lowSharedPng));
  results.fetch4 = await fetchAll(l4, 'sent pack');

  // ---- live changes: edit, add, delete
  const f = join(base, 'low', 'assets/pmpack/models/block/lowblock.json');
  const before = l4.hash;
  writeFileSync(f, json({ parent: 'minecraft:block/cube_all', textures: { all: 'pmpack:block/lowblock2' } }));
  const ce = await changed(before, 'edit');
  const le = await listing('edit');
  check(`edited file: new hash after ${ce.ms} ms`, modelOf(le).get('assets/pmpack/models/block/lowblock.json')?.hash === fileHash(readFileSync(f)));
  put(join(base, 'low'), 'assets/pmpack/textures/block/added.png', png1(77));
  const ca = await changed(le.hash, 'add');
  check(`added file listed after ${ca.ms} ms`, (await listing('add')).files.some((x) => x.path === 'assets/pmpack/textures/block/added.png'));
  rmSync(join(base, 'low', 'assets/pmpack/textures/block/added.png'));
  const cd = await changed((await listing('x')).hash, 'delete');
  check(`deleted file gone after ${cd.ms} ms`, !(await listing('delete')).files.some((x) => x.path === 'assets/pmpack/textures/block/added.png'));
  results.changeLatencyMs = { edit: ce.ms, add: ca.ms, delete: cd.ms };

  setProps({ 'resource-pack': null, 'resource-pack-sha1': null });
}

if (MODS) {
  const tmp = resolve(EXTRACT ?? join(root, '.pack-hook-extract'));
  rmSync(tmp, { recursive: true, force: true });
  mkdirSync(tmp, { recursive: true });
  const out = { map: new Map(), counter: 0, nested: 0 };
  const jars = readdirSync(MODS).filter((n) => n.endsWith('.jar'));
  for (const j of jars) jarCandidates(join(MODS, j), tmp, out);
  for (const [p, b] of walkPack(join(root, 'pm-test-pack'))) {
    if (!out.map.has(p)) out.map.set(p, new Set());
    out.map.get(p).add(fileHash(b));
  }
  rmSync(tmp, { recursive: true, force: true });
  const got = new Map(first.files.map((f) => [f.path, f]));
  const missing = [...out.map.keys()].filter((p) => !got.has(p));
  const extra = [...got.keys()].filter((p) => !out.map.has(p));
  const wrong = [...got].filter(([p, f]) => out.map.has(p) && !out.map.get(p).has(f.hash)).map(([p]) => p);
  const ns = new Set(first.files.map((f) => f.path.split('/')[1]));
  console.log(`mods: ${jars.length} top-level jars, ${out.nested} nested jars, ${out.map.size} candidate paths, ${ns.size} namespaces: ${[...ns].slice(0, 12).join(', ')}`);
  check('mods: every valid asset path of the jars (nested included) is listed', missing.length === 0, missing.slice(0, 3).join('; '));
  check('mods: nothing is listed that no jar or pack ships', extra.length === 0, extra.slice(0, 3).join('; '));
  check('mods: every listed hash is the hash of one of that path\'s files in the jars', wrong.length === 0, wrong.slice(0, 3).join('; '));
  results.mods = { jars: jars.length, nested: out.nested, candidates: out.map.size, namespaces: ns.size };
}

// ---- final consistency: a fresh full read after all of the above
const last = await listing('final');
verifyListing(last, 'final');
results.final = { files: last.files.length, hash: last.hash };
c.close?.();
console.log(`\n${passed} passed, ${failed} failed`);
if (jsonOut) writeFileSync(jsonOut, JSON.stringify({ passed, failed, results }, null, 2));
process.exit(failed ? 1 : 0);
