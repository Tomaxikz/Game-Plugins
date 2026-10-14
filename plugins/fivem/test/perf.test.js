'use strict';
// Main-thread cost features: encoder equivalence, read coalescing, sampler scheduling, idle behaviour, offload fallback.
const test = require('node:test');
const assert = require('node:assert/strict');
const fake = require('./fake-fxserver');
const ref = require('./ref-msgpack');
const lib = '../playermanager_bridge/server/lib/';
const cur = require(lib + 'msgpack');
const { createFiveMGame } = require(lib + 'fivem-game');
const { BridgeCore } = require(lib + 'bridge');
const { DEFAULTS, sanitise } = require(lib + 'config');
const { Offload } = require(lib + 'offload');
const { canonicalBytes } = require(lib + 'canonical');

test('encoder output is byte-identical to the reference encoder (fuzz)', () => {
  let seed;
  let F = cur.f;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  const ints = [0, 1, 127, 128, 255, 256, 65535, 65536, 2 ** 32 - 1, 2 ** 32, 2 ** 40, -1, -32, -33, -128, -129, -32768, -32769, -(2 ** 31), -(2 ** 31) - 1, -(2 ** 40)];
  const strs = ['', 'a', 'x'.repeat(31), 'x'.repeat(32), 'y'.repeat(255), 'z'.repeat(256), 'q'.repeat(65540), 'héllo', '日本語', '😀 emoji', 'a'.repeat(40) + 'é'];
  const gen = (d) => {
    const t = Math.floor(rnd() * (d > 3 ? 7 : 10));
    switch (t) {
      case 0: return null; case 1: return rnd() < 0.5; case 2: return ints[Math.floor(rnd() * ints.length)];
      case 3: return (rnd() - 0.5) * 1e6; case 4: return strs[Math.floor(rnd() * strs.length)];
      case 5: return Buffer.alloc(Math.floor(rnd() * 300), 7); case 6: return F(Math.floor(rnd() * 9));
      case 7: return Array.from({ length: Math.floor(rnd() * 20) }, () => gen(d + 1));
      default: { const o = {}; const n = Math.floor(rnd() * 20); for (let i = 0; i < n; i++) o[strs[Math.floor(rnd() * 5) + (rnd() < 0.3 ? 6 : 0)] + i] = rnd() < 0.1 ? undefined : gen(d + 1); return o; }
    }
  };
  for (let i = 0; i < 150; i++) {
    seed = 1000 + i; F = ref.f; const rv = gen(0);
    seed = 1000 + i; F = cur.f; const v = gen(0);
    for (const o of [undefined, { sortKeys: true, canonical: true }]) {
      assert.equal(Buffer.compare(ref.encode(rv, o), cur.encode(v, o)), 0, 'case ' + i);
    }
    // framed form == 4-byte length + body
    const body = cur.encode(v), framed = cur.encodeFramed(v);
    assert.equal(Buffer.compare(framed, Buffer.concat([Buffer.from([body.length >>> 24, (body.length >>> 16) & 255, (body.length >>> 8) & 255, body.length & 255]), body])), 0);
  }
  // surrogate-pair key ordering must follow UTF-8 byte order (U+FF5E < U+1F600 in bytes, > in UTF-16)
  const o = { '\u{1F600}': 1, '～': 2, a: 3 };
  assert.equal(Buffer.compare(ref.encode(o, { sortKeys: true }), cur.encode(o, { sortKeys: true })), 0);
});

function makeCore() {
  fake.install();
  const game = createFiveMGame({ g: globalThis, fs: require('fs'), resourceName: 'playermanager_bridge', platform: 'fivem' });
  let now = 1000;
  const calls = { findOnline: 0, onlineCount: 0, roster: 0 };
  for (const k of Object.keys(calls)) { const orig = game[k]; game[k] = (...a) => { calls[k]++; return orig(...a); }; }
  const onMain = (fn) => Promise.resolve().then(fn);
  const core = new BridgeCore({ game, config: sanitise(DEFAULTS), bridgeVersion: '1.0.0', onMain, monoMs: () => now });
  core.caps = core.computeCaps();
  const conn = { id: 1, hello: true, positions: null, events: null, nextSub() { return 1; }, sendRaw() {} };
  return { core, game, calls, conn, advance: (ms) => { now += ms; } };
}

test('read coalescing: hit, expiry, sensitive kept apart, invalidation', async () => {
  const { core, calls, conn, advance } = makeCore();
  fake.onEmit.fn = (event, target, payload) => setImmediate(() => core.handleAck(target, payload.req, true, null));
  const get = (args) => core.handle(conn, { op: 'player.get', args });
  const id = 'license:aaaa1111bbbb2222';
  const a = await get({ id });
  const b = await get({ id });
  assert.equal(calls.findOnline, 1, 'second identical read is served from the shared result');
  assert.deepEqual(cur.encode(a), cur.encode(b));
  // concurrent identical reads share one computation (in flight)
  const [c, d] = await Promise.all([get({ id, fields: ['health'] }), get({ fields: ['health'], id })]);
  assert.equal(calls.findOnline, 2, 'argument order does not matter; in-flight reads are shared');
  assert.deepEqual(c, d);
  // sensitive vs non-sensitive never share
  const s = await get({ id, include_sensitive: true });
  assert.equal(calls.findOnline, 3);
  assert.ok(s.ip && !a.ip);
  // different field selections never share
  await get({ id, fields: ['ping'] });
  assert.equal(calls.findOnline, 4);
  // expiry
  advance(501);
  await get({ id });
  assert.equal(calls.findOnline, 5);
  // action invalidates that player's entries
  await get({ id });
  assert.equal(calls.findOnline, 5);
  await core.handle(conn, { op: 'action.run', args: { action: 'heal', target: id, args: { health: 100 } } }).catch(() => {});
  await get({ id });
  assert.equal(calls.findOnline > 5, true, 'cache dropped by action.run');
  const n = calls.findOnline;
  // disconnect invalidation
  core.invalidateReads(id);
  await get({ id });
  assert.equal(calls.findOnline, n + 1);
  // another player is untouched by invalidating this one
  const id2 = 'license:cccc2222dddd3333';
  await get({ id: id2 });
  const m = calls.findOnline;
  core.invalidateReads(id);
  await get({ id: id2 });
  assert.equal(calls.findOnline, m, 'per-player invalidation');
  // errors are not cached
  await assert.rejects(get({ id: 'license:ffffffffffffffff' }));
  await assert.rejects(get({ id: 'license:ffffffffffffffff' }));
  assert.equal(calls.findOnline, m + 2);
});

test('sampler: no timer without subscribers, per-subscriber intervals, delta frames', () => {
  const { core, conn, advance } = makeCore();
  assert.equal(core.samplerTimer, null);
  const sent = [];
  conn.sendRaw = (o) => sent.push(o);
  core.positionsSubscribe(conn, { interval_ms: 1000 });
  assert.equal(core.samplerEvery, 1000);
  assert.ok(core.samplerTimer);
  clearInterval(core.samplerTimer);
  assert.equal(core.sampleTick(), true);                 // keyframe
  assert.equal(sent.length, 1);
  assert.equal(sent[0].data.key, true);
  advance(300);
  assert.equal(core.sampleTick(), false, 'interval not elapsed: no native reads, no frame');
  advance(700);
  assert.equal(core.sampleTick(), true);
  assert.equal(sent.length, 1, 'nothing moved: no frame (ping only after 60 s)');
  fake.state.players[1].coords = [5, 5, 5];
  advance(1000);
  core.sampleTick();
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[1].data.players.map((p) => p.id), ['license:aaaa1111bbbb2222']);
  core.detach(conn);
  assert.equal(core.samplerTimer, null, 'sampler stops with the last subscriber');
});

test('offload: falls back cleanly when worker threads are refused', async () => {
  const logs = [];
  const off = new Offload({ log: (m) => logs.push(m), loader: () => ({ Worker: class { constructor() { throw new Error('worker spawn not allowed'); } } }) });
  assert.equal(await off.encodeFramed({ a: 1 }), null);
  assert.equal(off.available, false);
});

test('offload: a real worker produces the same frame as the synchronous encoder', async () => {
  const off = new Offload();
  const obj = { id: 5, ok: true, data: { players: Array.from({ length: 300 }, (_, i) => ({ id: 'license:' + i, name: 'é' + i, ping: i, groups: [], game: { server_id: i } })) } };
  let frame = await off.encodeFramed(obj);
  for (let i = 0; i < 100 && frame === null; i++) { await new Promise((r) => setTimeout(r, 20)); frame = await off.encodeFramed(obj); }
  assert.ok(frame, 'worker became ready');
  assert.equal(Buffer.compare(frame, cur.encodeFramed(obj)), 0);
  off.stop();
});

test('hash vector unchanged by the fast encoder', () => {
  assert.equal(canonicalBytes({ id: 'minecraft:stone' }).toString('hex'), '81a269 64af6d696e6563726166743a73746f6e65'.replace(' ', ''));
});
