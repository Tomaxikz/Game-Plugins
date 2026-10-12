// Self-test: `node --test conformance/test/`
//  - MessagePack round-trips and framing
//  - canonical hash test vector (D17: correct bytes; documented erratum checked separately)
//  - the mock bridge passes the whole suite in unix and tcp mode
//  - each --break mode makes the expected test(s) fail
// Runs the mock and the CLI as child processes in short temp directories.

import { test, describe, before } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, existsSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { encode, decode, PmFloat, isInt, isFloat, toHex } from '../lib/msgpack.mjs';
import { canonicalBytes, hash16, TEST_VECTOR, sha256Hex, renderSubsetOf } from '../lib/canonical.mjs';
import { encodeFrame, FrameReader, frameHeader, FrameError, MAX_FRAME } from '../lib/framing.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const CONF = join(here, '..');
const CLI = join(CONF, 'bin', 'pm-conformance.mjs');
const MOCK = join(CONF, 'mock', 'mock-bridge.mjs');
const SLOW_OK = process.env.PM_SELFTEST_SLOW === '1';

// ------------------------------------------------------------------ codec

describe('msgpack', () => {
  test('canonical test vector: correct MessagePack bytes and hash (D17)', () => {
    const bytes = canonicalBytes(TEST_VECTOR.input);
    assert.equal(toHex(bytes), TEST_VECTOR.bytes);
    assert.equal(TEST_VECTOR.bytes, '81a26964af6d696e6563726166743a73746f6e65');
    assert.equal(hash16(TEST_VECTOR.input), '90d892ebcdb67d59');
    assert.equal(hash16({ id: 'minecraft:stone' }), '90d892ebcdb67d59');
  });

  test('documented erratum: the malformed 0xae bytes hash to 027f2bef638386bd (kept visible, not used)', () => {
    assert.equal(TEST_VECTOR.documentedBytes, '81a26964ae6d696e6563726166743a73746f6e65');
    const docBytes = Buffer.from(TEST_VECTOR.documentedBytes, 'hex');
    assert.equal(sha256Hex(docBytes).slice(0, 16), '027f2bef638386bd');
    // 0xae declares a 14-byte string, so the literal bytes are not valid MessagePack for "minecraft:stone".
    assert.throws(() => decode(docBytes), /trailing|truncated|bytes/);
  });

  test('canonical map keys are sorted by UTF-8 bytes at every level; nil entries dropped', () => {
    assert.equal(toHex(canonicalBytes({ b: 1, a: { z: null, y: 2 } })), toHex(canonicalBytes({ a: { y: 2 }, b: 1 })));
    assert.equal(toHex(canonicalBytes({ b: 1, a: 2 })), '82a16102a162' + '01');
  });

  test('integers use the smallest encoding and round-trip', () => {
    const values = [0, 1, 127, 128, 255, 256, 65535, 65536, 4294967295, 4294967296, Number.MAX_SAFE_INTEGER, -1, -32, -33, -128, -129, -32768, -32769, -2147483648, -2147483649, -Number.MAX_SAFE_INTEGER];
    for (const v of values) {
      const b = encode(v);
      assert.equal(decode(b), v, `int ${v}`);
      assert.ok(isInt(decode(b)), `int ${v} decodes as int`);
    }
    assert.equal(toHex(encode(127)), '7f');
    assert.equal(toHex(encode(128)), 'cc80');
    assert.equal(toHex(encode(-33)), 'd0df');
    assert.equal(toHex(encode(256)), 'cd0100');
    assert.equal(decode(encode(2n ** 64n - 1n)), 2n ** 64n - 1n);
  });

  test('strings: fixstr, str8, str16, str32 round-trip', () => {
    for (const n of [0, 31, 32, 255, 256, 65535, 65536]) {
      const s = 'a'.repeat(n);
      assert.equal(decode(encode(s)), s, `len ${n}`);
    }
    assert.equal(decode(encode('héllo ✓')), 'héllo ✓');
  });

  test('bin decodes to Uint8Array for bin8, bin16, bin32 lengths', () => {
    for (const n of [0, 255, 256, 70000]) {
      const u = new Uint8Array(n).map((_, i) => i & 0xff);
      const d = decode(encode(u));
      assert.ok(d instanceof Uint8Array && !Buffer.isBuffer(d));
      assert.equal(d.length, n);
      assert.deepEqual(Buffer.from(d), Buffer.from(u));
    }
  });

  test('arrays and maps use 16/32-bit headers past 15 entries', () => {
    const arr = Array.from({ length: 70000 }, (_, i) => i % 7);
    assert.deepEqual(decode(encode(arr)), arr);
    const obj = {};
    for (let i = 0; i < 40; i++) obj[`k${i}`] = i;
    assert.deepEqual(decode(encode(obj)), obj);
    assert.equal(decode(encode([])).length, 0);
    assert.deepEqual(decode(encode({})), {});
  });

  test('nil and bool', () => {
    assert.equal(decode(encode(null)), null);
    assert.equal(decode(encode(true)), true);
    assert.equal(decode(encode(false)), false);
    assert.equal(toHex(encode(null)), 'c0');
  });

  test('int and float are distinguishable on decode; float32 decodes', () => {
    assert.ok(isInt(decode(encode(200))));
    assert.ok(isFloat(decode(encode(new PmFloat(200)))), 'integral float64 is a PmFloat');
    assert.ok(isFloat(decode(encode(0.5))), 'non-integral is a float');
    assert.equal(decode(encode(0.5)), 0.5);
    const f32 = decode(Buffer.from('ca3f800000', 'hex'));
    assert.ok(isFloat(f32));
    assert.equal(Number(f32), 1);
  });

  test('map keys must be strings; trailing bytes are rejected', () => {
    assert.throws(() => decode(Buffer.from('81 01 02'.replace(/ /g, ''), 'hex')), /non-string map key/);
    assert.throws(() => decode(Buffer.from('0102', 'hex')), /trailing/);
    assert.throws(() => decode(Buffer.from('a3', 'hex')), /truncated/);
  });

  test('keys named __proto__ are ordinary keys', () => {
    const d = decode(encode(JSON.parse('{"__proto__": 1}')));
    assert.equal(Object.getPrototypeOf(d), Object.prototype);
    assert.equal(d['__proto__'], 1);
  });
});

describe('framing', () => {
  test('frames round-trip across arbitrary chunk boundaries', () => {
    const msgs = [{ id: 1, op: 'server.info', args: {} }, { id: 2, op: 'x', args: { s: 'y'.repeat(5000) } }];
    const wire = Buffer.concat(msgs.map((m) => encodeFrame(m)));
    const reader = new FrameReader();
    const out = [];
    for (let i = 0; i < wire.length; i += 7) {
      for (const body of reader.push(wire.subarray(i, i + 7))) out.push(decode(body));
    }
    assert.deepEqual(out, msgs);
  });

  test('frame body above 4 MiB is refused on encode and on decode', () => {
    assert.throws(() => encodeFrame({ s: 'x'.repeat(MAX_FRAME) }), FrameError);
    const r = new FrameReader();
    assert.throws(() => r.push(frameHeader(MAX_FRAME + 1)), (e) => e instanceof FrameError && e.code === 'frame_too_large');
  });
});

describe('render subset', () => {
  test('MC render components make the hash unverifiable from the record alone', () => {
    const r = renderSubsetOf({ id: 'minecraft:leather_chestplate', components: { 'minecraft:dyed_color': { rgb: 1 } } });
    assert.equal(r.verifiable, false);
    const s = renderSubsetOf({ id: 'minecraft:stone', components: { 'minecraft:custom_data': {} } });
    assert.equal(s.verifiable, true);
    assert.deepEqual(s.subset, { id: 'minecraft:stone' });
  });
});

// ------------------------------------------------------------------ mock + CLI

function tempRoot() {
  return mkdtempSync(join(tmpdir(), 'pmconf-'));
}

async function waitForFile(path, ms = 10000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (existsSync(path)) {
      try {
        return JSON.parse(readFileSync(path, 'utf8'));
      } catch {
        /* partially written: retry */
      }
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`timed out waiting for ${path}`);
}

async function startMock(root, extra = []) {
  mkdirSync(join(root, '.playermanager'), { recursive: true });
  const proc = spawn(process.execPath, [MOCK, '--root', root, ...extra], { stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = '';
  proc.stderr.on('data', (d) => (stderr += d));
  const bj = await waitForFile(join(root, '.playermanager', 'bridge.json'));
  const stop = () =>
    new Promise((resolve) => {
      if (proc.exitCode !== null) return resolve();
      proc.once('exit', () => resolve());
      proc.kill('SIGTERM');
      setTimeout(() => proc.kill('SIGKILL'), 1000).unref();
    });
  return { proc, stop, bridge: bj, stderr: () => stderr };
}

function runCli(args, { timeout = 180000 } = {}) {
  return new Promise((resolve, reject) => {
    const proc = spawn(process.execPath, [CLI, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    proc.stdout.on('data', (d) => (out += d));
    proc.stderr.on('data', (d) => (out += d));
    const timer = setTimeout(() => {
      proc.kill('SIGKILL');
      reject(new Error(`CLI timed out: ${args.join(' ')}`));
    }, timeout);
    proc.on('exit', (code) => {
      clearTimeout(timer);
      resolve({ code, out });
    });
  });
}

async function cliReport(root, args, tag) {
  const jsonPath = join(root, `${tag}.report.json`);
  const r = await runCli([...args, '--json', jsonPath]);
  const report = existsSync(jsonPath) ? JSON.parse(readFileSync(jsonPath, 'utf8')) : null;
  return { ...r, report, byId: new Map((report?.results ?? []).map((x) => [x.id, x])) };
}

describe('mock bridge end to end', () => {
  test('unix mode: the whole suite passes against the mock', async () => {
    const root = tempRoot();
    const mock = await startMock(root);
    try {
      const r = await cliReport(root, ['--root', root], 'unix');
      const failed = r.report.results.filter((x) => x.status === 'fail');
      assert.deepEqual(failed.map((x) => `${x.id}: ${x.reason}`), []);
      assert.equal(r.code, 0, r.out);
      assert.ok(r.report.summary.pass >= 60, `only ${r.report.summary.pass} passed`);
      assert.equal(r.report.transport.mode, 'unix');
    } finally {
      await mock.stop();
      rmSync(root, { recursive: true, force: true });
    }
  });

  test('tcp mode: the whole suite passes against the mock (token from file)', async () => {
    const root = tempRoot();
    const mock = await startMock(root, ['--tcp']);
    try {
      const r = await cliReport(root, ['--root', root], 'tcp');
      const failed = r.report.results.filter((x) => x.status === 'fail');
      assert.deepEqual(failed.map((x) => `${x.id}: ${x.reason}`), []);
      assert.equal(r.code, 0, r.out);
      assert.equal(r.report.transport.mode, 'tcp');
      assert.equal(r.byId.get('hello.tcp-wrong-token').status, 'pass');
      assert.equal(r.byId.get('hello.required-first').status, 'pass');
      assert.match(readFileSync(join(root, '.playermanager', 'token'), 'utf8'), /^[0-9a-f]{64}\n$/);
    } finally {
      await mock.stop();
      rmSync(root, { recursive: true, force: true });
    }
  });

  test('--slow keep-alive and idle tests pass against a fast-timer mock', async () => {
    const root = tempRoot();
    const mock = await startMock(root, ['--fast-timers']);
    try {
      const r = await cliReport(root, ['--root', root, '--slow', '--only', 'ping.keepalive-60s,conn.idle-close-120s'], 'slow');
      assert.equal(r.byId.get('ping.keepalive-60s').status, 'pass', r.byId.get('ping.keepalive-60s').reason);
      assert.equal(r.byId.get('conn.idle-close-120s').status, 'pass', r.byId.get('conn.idle-close-120s').reason);
    } finally {
      await mock.stop();
      rmSync(root, { recursive: true, force: true });
    }
  });

  test('--profile rust against the Minecraft mock fails the platform check', async () => {
    const root = tempRoot();
    const mock = await startMock(root);
    try {
      const r = await cliReport(root, ['--root', root, '--profile', 'rust', '--only', 'profile.platform']);
      assert.equal(r.code, 1);
      assert.equal(r.byId.get('profile.platform').status, 'fail');
    } finally {
      await mock.stop();
      rmSync(root, { recursive: true, force: true });
    }
  });

  test('unknown --only id is a usage error (exit 2)', async () => {
    const root = tempRoot();
    const mock = await startMock(root);
    try {
      const r = await runCli(['--root', root, '--only', 'no.such.test']);
      assert.equal(r.code, 2);
    } finally {
      await mock.stop();
      rmSync(root, { recursive: true, force: true });
    }
  });
});

// Each break must make the named test(s) fail. Transport decides which breaks apply.
const BREAK_CASES = [
  { brk: 'no-hello-check', mode: 'unix', fail: ['hello.required-first'] },
  { brk: 'unix-close-prehello', mode: 'unix', fail: ['hello.required-first'] },
  { brk: 'tcp-no-token-check', mode: 'tcp', fail: ['hello.tcp-wrong-token', 'hello.tcp-missing-token'] },
  { brk: 'wrong-protocol', mode: 'unix', fail: ['hello.protocol-matches-bridge-json'] },
  { brk: 'teleport-player-advertised', mode: 'unix', fail: ['hello.actions'] },
  { brk: 'unsupported-advertised', mode: 'unix', fail: ['ops.advertised-not-unsupported'] },
  { brk: 'unknown-op-ok', mode: 'unix', fail: ['ops.unknown-unsupported', 'ops.never-implemented-unsupported'] },
  { brk: 'no-busy', mode: 'unix', fail: ['positions.busy-second-subscription'] },
  { brk: 'bad-seq', mode: 'unix', fail: ['positions.seq'] },
  { brk: 'no-keyframe-key', mode: 'unix', fail: ['positions.subscribe.ack-keyframe'] },
  { brk: 'float-positions', mode: 'unix', fail: ['positions.frames.schema-quantised'] },
  { brk: 'accept-oversize', mode: 'unix', fail: ['conn.frame-oversize-rejected'] },
  { brk: 'wrong-hash', mode: 'unix', fail: ['player.inventory.digests'] },
  { brk: 'wrong-fingerprint', mode: 'unix', fail: ['player.inventory.digests'] },
  { brk: 'no-groups', mode: 'unix', fail: ['players.list.schema'] },
  { brk: 'bad-groups', mode: 'unix', fail: ['players.list.schema'] },
  { brk: 'ip-always', mode: 'unix', fail: ['players.list.sensitive'] },
  { brk: 'wrong-manifest-hash', mode: 'unix', fail: ['assets.manifest.paging-hash'] },
  { brk: 'reversed-assets', mode: 'unix', fail: ['assets.get.schema', 'assets.get.order-unknown'] },
  { brk: 'accept-hotbar-string', mode: 'unix', fail: ['args.item.get-slot-forms'] },
];
if (SLOW_OK) BREAK_CASES.push({ brk: 'no-ping', mode: 'unix', slow: true, fail: ['ping.keepalive-60s'] });

describe('mock breaks are detected', () => {
  for (const c of BREAK_CASES) {
    test(`--break ${c.brk} (${c.mode}) fails ${c.fail.join(', ')}`, async () => {
      const root = tempRoot();
      const extra = [...(c.mode === 'tcp' ? ['--tcp'] : []), '--break', c.brk, ...(c.slow ? ['--fast-timers'] : [])];
      const mock = await startMock(root, extra);
      try {
        const args = ['--root', root, '--only', [...c.fail, ...(c.slow ? [] : [])].join(',')];
        if (c.slow) args.push('--slow');
        const r = await cliReport(root, args, c.brk);
        for (const id of c.fail) {
          const rec = r.byId.get(id);
          assert.ok(rec, `${id} did not run`);
          assert.equal(rec.status, 'fail', `${id} should fail under --break ${c.brk}: ${rec.reason}`);
        }
        assert.equal(r.code, 1);
      } finally {
        await mock.stop();
        rmSync(root, { recursive: true, force: true });
      }
    });
  }
});

describe('documented erratum output', () => {
  before(() => {});
  test('the selftest records the D17 vector decision', () => {
    assert.equal(TEST_VECTOR.hash, '90d892ebcdb67d59');
    assert.equal(TEST_VECTOR.documentedHash, '027f2bef638386bd');
  });
});
