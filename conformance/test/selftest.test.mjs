// Self-test: `node --test 'conformance/test/*.test.mjs'`
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
import { canonicalBytes, hash16, TEST_VECTOR, sha256Hex, renderSubsetOf, assetHash } from '../lib/canonical.mjs';
import { encodeFrame, FrameReader, frameHeader, FrameError, MAX_FRAME } from '../lib/framing.mjs';
import { buildSurface, chunkSurfaceErrors, surfaceHash, voidHash, packU16LE, packI16LE, unpackU16LE, unpackI16LE, NO_FLOOR } from '../lib/chunks.mjs';
import { itemErrors, containersErrors, blocksErrors, findKeyPaths, gameSectionErrors } from '../lib/schema.mjs';
import { stateErrors, normaliseState, volumeChunkErrors, surfaceColumns, u16Array, highestNonAir, cellState } from '../lib/volume.mjs';
import { blocksHash, blocksListErrors, biomeListErrors, propertiesErrors } from '../lib/registry.mjs';
import { fileHash, listingHash, packPathError, manifestFileErrors } from '../lib/pack.mjs';
import { assembleAssets } from '../lib/assetchunks.mjs';

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
  test('MC render components are part of the render subset (verifiable from the record)', () => {
    const r = renderSubsetOf({ id: 'minecraft:leather_chestplate', components: { 'minecraft:dyed_color': { rgb: 1 } } });
    assert.equal(r.verifiable, true);
    assert.deepEqual(r.subset.render_components, { 'minecraft:dyed_color': { rgb: 1 } });
  });
  test('bundle/container render components use the nested item hashes', () => {
    const r = renderSubsetOf({ id: 'minecraft:bundle', components: { 'minecraft:bundle_contents': [] }, contents: [{ hash: 'aaaaaaaaaaaaaaaa' }] });
    assert.deepEqual(r.subset.render_components['minecraft:bundle_contents'], ['aaaaaaaaaaaaaaaa']);
  });
  test('legacy snbt items stay format-only; non-render components do not enter the subset', () => {
    assert.equal(renderSubsetOf({ id: 'minecraft:stone', components: { snbt: '{}' } }).verifiable, false);
    const s = renderSubsetOf({ id: 'minecraft:stone', components: { 'minecraft:custom_data': {} } });
    assert.deepEqual(s.subset, { id: 'minecraft:stone' });
  });
});

describe('contract rules (schema)', () => {
  const H = '0123456789abcdef';
  const F = '0'.repeat(64);
  const rec = (o) => ({ count: 1, hash: H, fingerprint: F, ...o });
  test('item encoding is Minecraft-only; top-level records have no slot; contents need int slots', () => {
    assert.ok(itemErrors(rec({ id: 'water', encoding: 'components' }), 'x', { game: 'fivem' }).some((m) => /encoding/.test(m)));
    assert.equal(itemErrors(rec({ id: 'minecraft:stone', encoding: 'components' }), 'x', { game: 'minecraft_java' }).length, 0);
    assert.ok(itemErrors(rec({ id: 'a:b', slot: 0 }), 'x', { game: 'minecraft_java' }).some((m) => /top-level/.test(m)));
    assert.ok(itemErrors(rec({ id: 'a:b', contents: [rec({ id: 'a:c', slot: '0' })] }), 'x', { game: 'minecraft_java' }).some((m) => /int slot/.test(m)));
  });
  test('Rust: platform data in components only; skin omitted when 0', () => {
    assert.ok(itemErrors(rec({ id: 'rifle.ak', shortname: 'rifle.ak' }), 'x', { game: 'rust' }).some((m) => /shortname/.test(m)));
    assert.ok(itemErrors(rec({ id: 'rifle.ak', skin: 0 }), 'x', { game: 'rust' }).some((m) => /skin/.test(m)));
    assert.equal(itemErrors(rec({ id: 'rifle.ak', components: { uid: 1, definition_id: 2 } }), 'x', { game: 'rust' }).length, 0);
    assert.ok(itemErrors(rec({ id: 'rifle.ak', components: { label: 'x' } }), 'x', { game: 'rust' }).length > 0);
  });
  test('FiveM: one main container with 1-based slots; components label/weight/metadata', () => {
    const cont = [{ id: 'main', size: 50, slots: [{ slot: 1, item: rec({ id: 'water', count: 3, components: { label: 'Water', weight: 0.5, metadata: {} } }) }] }];
    assert.deepEqual(containersErrors(cont, { game: 'fivem', slotBase: 1 }), []);
    assert.ok(containersErrors([{ id: 'main', size: 50, slots: [{ slot: 0, item: rec({ id: 'water' }) }] }], { game: 'fivem', slotBase: 1 }).length > 0);
    assert.ok(itemErrors(rec({ id: 'water', components: { color: 'red' } }), 'x', { game: 'fivem' }).length > 0);
  });
  test('MC blocks: data length and index range are checked', () => {
    const palette = ['minecraft:air', 'minecraft:stone', 'minecraft:grass_block[snowy=false]'];
    const good = { origin: { x: 1, y: 2, z: 3 }, size: { x: 2, y: 2, z: 2 }, order: 'yzx', palette, bits: 2, data: new Uint8Array([0x19, 0x06]) };
    assert.deepEqual(blocksErrors(good), []);
    assert.ok(blocksErrors({ ...good, data: new Uint8Array([0x19]) }).some((m) => /bytes/.test(m)));
    assert.ok(blocksErrors({ ...good, data: new Uint8Array([0xff, 0xff]) }).some((m) => />= palette/.test(m)));
    assert.ok(blocksErrors({ ...good, order: 'xyz' }).length > 0);
  });
  test('address keys are found anywhere in a payload; common keys in game are flagged', () => {
    assert.deepEqual(findKeyPaths({ a: [{ address: 'x' }] }, 'address'), ['$.a[0].address']);
    assert.ok(gameSectionErrors({ position: {}, xp_level: 3 }, { mc: true }).length === 2);
  });
});


describe('chunk surfaces (D23)', () => {
  const col = (o = {}) => ({ top: 'minecraft:stone', y: 64, depth: 0, floor: null, biome: 'minecraft:plains', ...o });
  const build = (f) => buildSurface({ cx: 1, cz: 2, world: 'world', dimension: 'minecraft:overworld', columns: Array.from({ length: 256 }, (_, i) => col(f ? f(i) : {})) });
  const errs = (s, mut) => {
    const c = { ...s };
    mut(c);
    return chunkSurfaceErrors(c, {});
  };

  test('hash vectors: void {void:true} and a uniform stone chunk (bytes cross-checked with an independent encoder)', () => {
    assert.equal(toHex(canonicalBytes({ void: true })), '81a4766f6964c3');
    assert.equal(voidHash(), '11e5dda2e5e537e8');
    const s = build();
    assert.equal(s.hash, 'ef2a5e5db2918792');
    assert.equal(surfaceHash(s), 'ef2a5e5db2918792');
  });

  test('hash input is exactly {biome, biomes, floor, palette, top, water_depth, y}; coordinates and world are not hashed', () => {
    const a = build();
    const b = { ...a, cx: 99, cz: -3, world: 'other', dimension: 'minecraft:the_end' };
    assert.equal(surfaceHash(b), a.hash);
    assert.notEqual(surfaceHash({ ...a, palette: ['minecraft:dirt'] }), a.hash);
  });

  test('u16/i16 little-endian helpers round-trip (negative y)', () => {
    assert.deepEqual(unpackU16LE(packU16LE([0, 1, 255, 256, 0xffff])), [0, 1, 255, 256, 0xffff]);
    assert.deepEqual(unpackI16LE(packI16LE([-64, 0, 319, -32768, 32767])), [-64, 0, 319, -32768, 32767]);
    assert.deepEqual([...packI16LE([-64])], [0xc0, 0xff]);
    assert.equal(packU16LE(new Array(256).fill(1)).length, 512);
  });

  test('a good surface passes; an all-air chunk becomes the void form and passes', () => {
    assert.deepEqual(chunkSurfaceErrors(build(), {}), []);
    const v = buildSurface({ cx: 0, cz: 0, world: 'w', dimension: 'minecraft:overworld', columns: Array.from({ length: 256 }, () => col({ top: 'minecraft:air', y: -64 })) });
    assert.deepEqual(Object.keys(v).sort(), ['cx', 'cz', 'dimension', 'hash', 'void', 'world']);
    assert.deepEqual(chunkSurfaceErrors(v, {}), []);
    assert.ok(chunkSurfaceErrors({ ...v, hash: '0'.repeat(16) }, {}).some((m) => /void chunk hash/.test(m)));
    assert.ok(chunkSurfaceErrors({ ...v, top: new Uint8Array(512) }, {}).some((m) => /must not carry top/.test(m)));
  });

  test('an all-air column in a non-void chunk is minecraft:air / min Y / depth 0 / floor 0xFFFF and passes', () => {
    const s = build((i) => (i === 17 ? { top: 'minecraft:air', y: -64 } : {}));
    assert.deepEqual(chunkSurfaceErrors(s, {}), []);
    const withCaveAir = build((i) => (i === 17 ? { top: 'minecraft:cave_air' } : {}));
    assert.ok(chunkSurfaceErrors(withCaveAir, {}).some((m) => /air block/.test(m)));
    const air2 = build((i) => (i === 17 ? { top: 'minecraft:air', y: -63 } : {}));
    assert.ok(chunkSurfaceErrors(air2, {}).some((m) => /all-air column/.test(m)));
  });

  test('water rules: top must be water when depth > 0; waterlogged surface is not water; floor 0xFFFF with depth 0; water to bottom allowed', () => {
    const ok = build((i) => (i === 0 ? { top: 'minecraft:water', y: 62, depth: 3, floor: 'minecraft:sand' } : {}));
    assert.deepEqual(chunkSurfaceErrors(ok, {}), []);
    const bottom = build((i) => (i === 0 ? { top: 'minecraft:water', y: 62, depth: 127, floor: null } : {}));
    assert.deepEqual(chunkSurfaceErrors(bottom, {}), []);
    const waterlogged = build((i) => (i === 0 ? { top: 'minecraft:oak_stairs', depth: 1, floor: 'minecraft:sand' } : {}));
    assert.ok(chunkSurfaceErrors(waterlogged, {}).some((m) => /not a water block/.test(m)));
    const floorNoWater = build((i) => (i === 0 ? { floor: 'minecraft:sand' } : {}));
    assert.ok(chunkSurfaceErrors(floorNoWater, {}).some((m) => /floor must be 0xFFFF/.test(m)));
    const waterFloor = build((i) => (i === 0 ? { top: 'minecraft:water', y: 62, depth: 2, floor: 'minecraft:kelp' } : {}));
    assert.ok(chunkSurfaceErrors(waterFloor, {}).some((m) => /floor .* is a water block/.test(m)));
  });

  test('shape violations are reported: bin sizes, index ranges, states in palette, hash, extra keys, world echo', () => {
    const s = build();
    assert.ok(errs(s, (c) => (c.top = c.top.subarray(0, 510))).some((m) => /exactly 512 bytes/.test(m)));
    assert.ok(errs(s, (c) => (c.water_depth = new Uint8Array(255))).some((m) => /exactly 256 bytes/.test(m)));
    assert.ok(errs(s, (c) => (c.biome = packU16LE(new Array(256).fill(5)))).some((m) => /biome index/.test(m)));
    assert.ok(errs(s, (c) => (c.top = packU16LE(new Array(256).fill(9)))).some((m) => /top index/.test(m)));
    assert.ok(errs(s, (c) => (c.floor = packU16LE(new Array(256).fill(7)))).some((m) => /floor/.test(m)));
    assert.ok(errs(s, (c) => (c.palette = ['minecraft:stone[x=1]'])).some((m) => /block states/.test(m)));
    assert.ok(errs(s, (c) => (c.palette = ['stone'])).some((m) => /namespaced/.test(m)));
    assert.ok(errs(s, (c) => (c.hash = 'f'.repeat(16))).some((m) => /recomputed/.test(m)));
    assert.ok(errs(s, (c) => (c.extra = 1)).some((m) => /unexpected key/.test(m)));
    assert.ok(chunkSurfaceErrors(s, { world: 'x' }).some((m) => /requested/.test(m)));
    assert.equal(NO_FLOOR, 0xffff);
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

describe('map.volume validator (operations.md 6c)', () => {
  const cells = (levels) => packU16LE(levels.flatMap((fn) => Array.from({ length: 256 }, (_, i) => fn(i))));
  const good = () => ({
    cx: 3,
    cz: -2,
    y0: 60,
    height: 2,
    palette: ['minecraft:grass_block[snowy=false]', 'minecraft:dirt'],
    blocks: cells([() => 1, (i) => (i === 5 ? 0xffff : 0)]),
    biome_ids: ['minecraft:plains'],
    biome: packU16LE(new Array(256).fill(0)),
  });
  const errs = (mut) => {
    const c = good();
    mut(c);
    return volumeChunkErrors(c);
  };

  test('a good volume passes; helpers read cells and the highest non-air level', () => {
    const c = good();
    assert.deepEqual(volumeChunkErrors(c), []);
    const b = u16Array(c.blocks);
    assert.equal(cellState(c, b, 0, 61, 0), 'minecraft:grass_block[snowy=false]');
    assert.equal(cellState(c, b, 5, 61, 0), null);
    assert.equal(cellState(c, b, 0, 59, 0), null);
    assert.equal(highestNonAir(c, b), 61);
  });

  test('a chunk with height 0 has an empty palette and an empty blocks bin', () => {
    const c = { ...good(), height: 0, palette: [], blocks: new Uint8Array(0) };
    assert.deepEqual(volumeChunkErrors(c), []);
    assert.ok(errs((x) => ((x.height = 0), (x.blocks = new Uint8Array(0)))).some((m) => /empty palette/.test(m)));
  });

  test('air in the palette, unsorted or duplicate properties, duplicate entries and empty brackets are reported', () => {
    assert.ok(errs((c) => c.palette.push('minecraft:cave_air')).some((m) => /palette contains air/.test(m)));
    assert.ok(errs((c) => c.palette.push('minecraft:air')).some((m) => /palette contains air/.test(m)));
    assert.ok(errs((c) => (c.palette[0] = 'minecraft:oak_stairs[waterlogged=false,facing=east]')).some((m) => /not sorted/.test(m)));
    assert.ok(errs((c) => (c.palette[0] = 'minecraft:oak_stairs[facing=east,facing=west]')).some((m) => /twice/.test(m)));
    assert.ok(errs((c) => (c.palette[1] = c.palette[0])).some((m) => /appears twice/.test(m)));
    assert.ok(errs((c) => (c.palette[0] = 'minecraft:stone[]')).some((m) => /empty property list/.test(m)));
    assert.ok(errs((c) => (c.palette[0] = 'stone')).some((m) => /namespaced/.test(m)));
    assert.deepEqual(stateErrors('minecraft:light[level=10,waterlogged=false]'), []);
    assert.deepEqual(stateErrors('minecraft:light'), []);
    assert.equal(normaliseState('minecraft:oak_stairs', { waterlogged: 'false', facing: 'east' }), 'minecraft:oak_stairs[facing=east,waterlogged=false]');
    assert.equal(normaliseState('minecraft:stone', {}), 'minecraft:stone');
  });

  test('sizes and indices: blocks length, biome length, index ranges, height cap, extra keys', () => {
    assert.ok(errs((c) => (c.blocks = c.blocks.subarray(0, 1022))).some((m) => /1024 bytes/.test(m)));
    assert.ok(errs((c) => (c.biome = c.biome.subarray(0, 510))).some((m) => /512 bytes/.test(m)));
    assert.ok(errs((c) => (c.blocks = cells([() => 7, () => 0]))).some((m) => /neither a palette index/.test(m)));
    assert.ok(errs((c) => (c.biome = packU16LE(new Array(256).fill(1)))).some((m) => /biome index/.test(m)));
    assert.ok(errs((c) => (c.height = 161)).some((m) => /outside 0\.\.160/.test(m)));
    assert.ok(errs((c) => (c.extra = 1)).some((m) => /unexpected key extra/.test(m)));
    assert.ok(errs((c) => (c.y0 = 1.5)).some((m) => /y0 must be an int/.test(m)));
  });

  test('surfaceColumns reads a map.chunks surface: surface, water, floor, biome; void chunks have no surface', () => {
    const col = (o = {}) => ({ top: 'minecraft:stone', y: 64, depth: 0, floor: null, biome: 'minecraft:plains', ...o });
    const s = buildSurface({ cx: 0, cz: 0, world: 'w', dimension: 'minecraft:overworld', columns: Array.from({ length: 256 }, (_, i) => (i === 3 ? col({ top: 'minecraft:water', y: 62, depth: 2, floor: 'minecraft:sand', biome: 'minecraft:river' }) : i === 4 ? col({ top: 'minecraft:air', y: -64 }) : col())) });
    const cols = surfaceColumns(s);
    assert.deepEqual([cols[3].surface, cols[3].y, cols[3].depth, cols[3].floorId, cols[3].biome], [true, 62, 2, 'minecraft:sand', 'minecraft:river']);
    assert.equal(cols[4].surface, false);
    assert.equal(cols[4].y, -64);
    const v = buildSurface({ cx: 0, cz: 0, world: 'w', dimension: 'minecraft:overworld', columns: Array.from({ length: 256 }, () => col({ top: 'minecraft:air', y: -64 })) });
    assert.ok(surfaceColumns(v).every((c) => !c.surface));
  });
});

describe('biomes.list and blocks.list validators (operations.md 6d, 6e)', () => {
  // The two test vectors of operations.md 6e (literal lists).
  const THREE = [
    { id: 'minecraft:air', map_color: null, flags: 177, light: 0 },
    { id: 'minecraft:furnace', map_color: 7368816, flags: 268, light: 0, states: [
      { properties: 'facing=east,lit=true', flags: 332 },
      { properties: 'facing=north,lit=true', flags: 332 },
      { properties: 'facing=south,lit=true', flags: 332 },
      { properties: 'facing=west,lit=true', flags: 332 },
    ] },
    { id: 'minecraft:stone', map_color: 7368816, flags: 12, light: 0 },
  ];
  const AIR = [{ id: 'minecraft:air', map_color: null, flags: 177, light: 0 }];

  test('blocks hash vectors: the three-entry example is efeed82267412635, the single air entry is 4fa84a813b94a02a (documented bytes)', () => {
    assert.equal(blocksHash(THREE), 'efeed82267412635');
    assert.equal(toHex(canonicalBytes(AIR)), '9183a5666c616773ccb1a26964ad6d696e6563726166743a616972a56c6967687400');
    assert.equal(toHex(canonicalBytes(AIR)), '91 83 a5 66 6c 61 67 73 cc b1 a2 69 64 ad 6d 69 6e 65 63 72 61 66 74 3a 61 69 72 a5 6c 69 67 68 74 00'.replace(/ /g, ''));
    assert.equal(blocksHash(AIR), '4fa84a813b94a02a');
    assert.notEqual(blocksHash([{ ...AIR[0], map_color: 0 }]), '4fa84a813b94a02a');
    assert.equal(blocksHash(THREE.slice().reverse()), blocksHash(THREE.slice().reverse()));
    assert.notEqual(blocksHash(THREE.slice().reverse()), 'efeed82267412635');
  });

  test('the documented examples pass the blocks validator; unknown flag bits are counted, not failed', () => {
    assert.deepEqual(blocksListErrors(THREE), { errors: [], unknownBits: 0 });
    const r = blocksListErrors([{ id: 'a:b', map_color: 1, flags: 1 << 12, light: 0 }]);
    assert.deepEqual(r, { errors: [], unknownBits: 1 });
  });

  test('blocks rules: sorted unique ids, field types, light vs emits_light, states sorted and differing', () => {
    const errs = (list) => blocksListErrors(list).errors;
    const blk = (o = {}) => ({ id: 'a:b', map_color: 5, flags: 12, light: 0, ...o });
    assert.ok(errs([blk({ id: 'a:z' }), blk({ id: 'a:b' })]).some((m) => /not sorted/.test(m)));
    assert.ok(errs([blk(), blk()]).some((m) => /appears twice/.test(m)));
    assert.ok(errs([blk({ map_color: undefined })]).some((m) => /map_color must be present/.test(m)));
    assert.ok(errs([blk({ map_color: 0x1000000 })]).some((m) => /24-bit/.test(m)));
    assert.ok(errs([blk({ flags: -1 })]).some((m) => /flags/.test(m)));
    assert.ok(errs([blk({ light: 16 })]).some((m) => /light/.test(m)));
    assert.ok(errs([blk({ flags: 12 | 64, light: 0 })]).some((m) => /emits_light/.test(m)));
    assert.ok(errs([blk({ flags: 12, light: 5 })]).some((m) => /emits_light/.test(m)));
    assert.deepEqual(errs([blk({ flags: 76, light: 15 })]), []);
    const st = (props, o = {}) => ({ properties: props, flags: 13, ...o });
    assert.ok(errs([blk({ states: [st('b=1'), st('a=1')] })]).some((m) => /not sorted/.test(m)));
    assert.ok(errs([blk({ states: [st('a=1'), st('a=1')] })]).some((m) => /or duplicate/.test(m)));
    assert.ok(errs([blk({ states: [st('a=1', { flags: 12 })] })]).some((m) => /does not differ/.test(m)));
    assert.deepEqual(errs([blk({ states: [st('a=1', { flags: 12, map_color: 9 })] })]), []);
    assert.deepEqual(errs([blk({ states: [st('a=1', { flags: 12, map_color: null })] })]), []);
    assert.ok(errs([blk({ states: [st('a=1', { map_color: 5 })] })]).some((m) => /equals the block map_color/.test(m)));
    assert.ok(errs([blk({ states: [] })]).some((m) => /non-empty array/.test(m)));
    assert.ok(errs([blk({ states: [st('b=1,a=2')] })]).some((m) => /keys not sorted/.test(m)));
    assert.ok(errs([blk({ states: [{ properties: 'a=1' }] })]).some((m) => /flags must be/.test(m)));
    assert.deepEqual(propertiesErrors(''), []);
    assert.deepEqual(propertiesErrors('axis=x,waterlogged=false'), []);
  });

  test('biomes rules: sorted unique ids, 24-bit water_color, override int|null, modifier enum', () => {
    const bm = (o = {}) => ({ id: 'minecraft:plains', temperature: 0.8, downfall: 0.4, grass_color: null, foliage_color: null, dry_foliage_color: null, water_color: 4159204, grass_color_modifier: 'none', ...o });
    assert.deepEqual(biomeListErrors([bm()]), []);
    assert.deepEqual(biomeListErrors([bm({ id: 'a:b' }), bm({ id: 'minecraft:swamp', grass_color: 5, grass_color_modifier: 'swamp' })]), []);
    assert.ok(biomeListErrors([bm({ id: 'minecraft:z' }), bm({ id: 'minecraft:a' })]).some((m) => /not sorted/.test(m)));
    assert.ok(biomeListErrors([bm(), bm()]).some((m) => /twice/.test(m)));
    assert.ok(biomeListErrors([bm({ water_color: 0xff3f76e4 })]).some((m) => /24-bit/.test(m)));
    assert.ok(biomeListErrors([bm({ water_color: null })]).some((m) => /water_color/.test(m)));
    assert.ok(biomeListErrors([bm({ grass_color: -1 })]).some((m) => /grass_color/.test(m)));
    assert.ok(biomeListErrors([bm({ foliage_color: undefined })]).some((m) => /must be present/.test(m)));
    assert.ok(biomeListErrors([bm({ grass_color_modifier: 'tropical' })]).some((m) => /grass_color_modifier/.test(m)));
    assert.ok(biomeListErrors([bm({ temperature: 'hot' })]).some((m) => /temperature/.test(m)));
    assert.ok(biomeListErrors([bm({ extra: 1 })]).some((m) => /unexpected key/.test(m)));
    assert.ok(biomeListErrors('x').length === 1);
  });
});

describe('pack.* validators (operations.md 10a, 10b; items-and-assets.md 11)', () => {
  test('hash vectors: bytes 01 02 03 -> 039058c6f2c0cb49; the two-file listing -> d664ed1c29d33d64; the empty listing -> 9e076ceaf246b600', () => {
    assert.equal(fileHash(Uint8Array.from([1, 2, 3])), '039058c6f2c0cb49');
    const files = [
      { path: 'assets/examplemod/models/block/ruby_block.json', hash: '0123456789abcdef', size: 412 },
      { path: 'assets/examplemod/textures/item/ruby.png', hash: '039058c6f2c0cb49', size: 3 },
    ];
    assert.equal(listingHash(files), 'd664ed1c29d33d64');
    assert.equal(listingHash([]), '9e076ceaf246b600');
    assert.notEqual(listingHash(files.slice().reverse()), 'd664ed1c29d33d64');
    assert.deepEqual(manifestFileErrors(files), []);
  });

  test('path rule: assets/<ns>/<kind>/<path> with the right extension; everything else is rejected', () => {
    for (const ok of [
      'assets/examplemod/blockstates/ruby_block.json',
      'assets/minecraft/models/block/cube_all.json',
      'assets/a.b-c_d/textures/item/x/y.png',
      'assets/ns/textures/block/water_still.png.mcmeta',
      `assets/ns/textures/${'a'.repeat(480)}.png`,
    ]) assert.equal(packPathError(ok), null, ok);
    for (const bad of [
      'assets/ns/sounds/a.ogg',
      'assets/ns/lang/en_us.json',
      'assets/ns/items/a.json',
      'assets/ns/atlases/blocks.json',
      'assets/ns/textures/block/A.png',
      'assets/NS/textures/block/a.png',
      'assets/ns/models/../a.json',
      'assets/ns/models/./a.json',
      'assets/../models/a.json',
      'assets/ns/models/block/a.png',
      'assets/ns/textures/block/a.json',
      'assets/ns/models/block/a.json.bak',
      'assets/ns/textures/block//a.png',
      '/assets/ns/models/a.json',
      'data/ns/models/a.json',
      'assets/ns/models',
      'assets\\ns\\models\\a.json',
      `assets/ns/textures/${'a'.repeat(520)}.png`,
      5,
    ]) assert.notEqual(packPathError(bad), null, String(bad));
  });

  test('manifest rules: sorted unique paths, hash and size types, no extra keys', () => {
    const f = (path, o = {}) => ({ path, hash: '0123456789abcdef', size: 1, ...o });
    assert.deepEqual(manifestFileErrors([f('assets/a/models/a.json'), f('assets/a/models/b.json')]), []);
    assert.ok(manifestFileErrors([f('assets/a/models/b.json'), f('assets/a/models/a.json')]).some((m) => /not sorted/.test(m)));
    assert.ok(manifestFileErrors([f('assets/a/models/a.json'), f('assets/a/models/a.json')]).some((m) => /twice/.test(m)));
    assert.ok(manifestFileErrors([f('assets/a/sounds/a.ogg')]).some((m) => /path rule/.test(m)));
    assert.ok(manifestFileErrors([f('assets/a/models/a.json', { hash: 'XYZ' })]).some((m) => /hash/.test(m)));
    assert.ok(manifestFileErrors([f('assets/a/models/a.json', { size: 1.5 })]).some((m) => /size/.test(m)));
    assert.ok(manifestFileErrors([f('assets/a/models/a.json', { extra: 1 })]).some((m) => /unexpected key/.test(m)));
  });
});

describe('chunked assets.get answers: reassembly and bookkeeping', () => {
  const bytes = (n, seed) => Uint8Array.from({ length: n }, (_, i) => (i * 7 + seed) & 0xff);
  const whole = {
    key: 'item:x:big',
    render: 'static',
    textures: {
      icon: { png: bytes(7, 1), width: 16, height: 16 },
      layers: [
        { png: bytes(5, 2), width: 16, height: 16 },
        { png: bytes(4, 3), width: 16, height: 16, tint_index: 1, emissive: { png: bytes(6, 4), width: 16, height: 16 } },
      ],
    },
  };
  const hash = assetHash(whole);
  // Slices of at most 3 bytes in wire order: icon, layers[0], layers[1] base, layers[1] overlay.
  function slices() {
    const meta = (o) => Object.fromEntries(Object.entries(o).filter(([k]) => k !== 'png' && k !== 'emissive'));
    const parts = [
      { layer: null, key: 'icon', obj: whole.textures.icon },
      { layer: 0, key: 'layers', obj: whole.textures.layers[0] },
      { layer: 1, key: 'layers', obj: whole.textures.layers[1] },
      { layer: 1, key: 'layers', obj: whole.textures.layers[1].emissive, overlay: true },
    ];
    const out = [];
    for (const p of parts) {
      for (let off = 0; off < p.obj.png.length; off += 3) {
        const t = { ...meta(p.obj), png: p.obj.png.slice(off, off + 3), png_offset: off, png_total: p.obj.png.length };
        const tex = p.overlay ? { emissive: t } : t;
        const e = { key: whole.key, hash, render: 'static', chunk: { index: 0, total: 0 }, textures: p.layer === null ? { [p.key]: tex } : { layers: [tex] } };
        if (p.layer !== null) e.chunk.layer = p.layer;
        out.push(e);
      }
    }
    out.forEach((e, i) => ((e.chunk.index = i), (e.chunk.total = out.length)));
    return out;
  }
  const problems = (mut) => {
    const l = slices();
    mut(l);
    return assembleAssets(l).problems;
  };

  test('slices of a plain texture, two layers and an emissive overlay join to the asset whose D29 hash is on every slice', () => {
    const l = slices();
    assert.equal(l.length, 3 + 2 + 2 + 2);
    const r = assembleAssets(l);
    assert.deepEqual(r.problems, []);
    assert.equal(r.sliced, 1);
    assert.equal(r.slices, 9);
    assert.equal(r.assets.length, 1);
    assert.equal(assetHash(r.assets[0]), hash);
    assert.deepEqual([...r.assets[0].textures.layers[1].emissive.png], [...whole.textures.layers[1].emissive.png]);
  });

  test('slices may arrive in any order (index order wins); whole assets and unavailable entries pass through in order', () => {
    const l = slices().reverse();
    const mixed = [{ key: 'item:x:a', hash: '0'.repeat(16), render: 'unavailable', unavailable_reason: 'not_found' }, ...l, { key: 'item:x:z', hash: '0'.repeat(16), render: 'unavailable', unavailable_reason: 'not_found' }];
    const r = assembleAssets(mixed);
    assert.deepEqual(r.problems, []);
    assert.deepEqual(r.assets.map((a) => a.key), ['item:x:a', 'item:x:big', 'item:x:z']);
  });

  test('bookkeeping violations are reported: index gap, png_offset gap, wrong layer, bad or differing hash, bad total', () => {
    assert.ok(problems((l) => l.splice(4, 1)).some((m) => /indices must be 0\.\./.test(m)));
    assert.ok(problems((l) => (l[4].chunk.index = 7)).some((m) => /indices must be/.test(m)));
    assert.ok(problems((l) => (l[4].textures.layers[0].png_offset += 1)).some((m) => /png_offset/.test(m)));
    assert.ok(problems((l) => l.forEach((e) => e.chunk.layer === 1 && (e.chunk.layer = 0))).length > 0);
    assert.ok(problems((l) => l.forEach((e) => (e.hash = '0123456789abcdef'))).some((m) => /recomputed/.test(m)));
    assert.ok(problems((l) => (l[l.length - 1].hash = '0'.repeat(16))).some((m) => /hash differs between slices/.test(m)));
    assert.ok(problems((l) => l.forEach((e) => (e.chunk.total += 1))).some((m) => /indices must be/.test(m)));
    assert.ok(problems((l) => (l[1].textures.icon.png_total += 1)).some((m) => /add up|png_total/.test(m)));
    assert.ok(problems((l) => delete l[5].chunk.layer).some((m) => /needs chunk\.layer/.test(m)));
    assert.ok(problems((l) => (l[0].chunk.layer = 0)).some((m) => /chunk\.layer on a slice without layers/.test(m)));
    assert.ok(problems((l) => l.splice(0, 1, ...[])).length > 0);
  });
});

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


describe('chunk surfaces against the mock', () => {
  const IDS = 'chunks.capability-consistency,map.chunks.schema,map.chunks.missing,map.chunks.args,chunks.subscribe.ack,chunks.subscribe.args,chunks.subscribe.frames';
  for (const [name, extra, cli] of [
    ['unix', [], []],
    ['unix with --chunk-churn (changed chunks resent only after the 2 s cooldown)', ['--chunk-churn'], []],
    ['tcp', ['--tcp'], []],
  ]) {
    test(`${name}: all chunk tests pass (frames test with --allow-actions)`, async () => {
      const root = tempRoot();
      const mock = await startMock(root, extra);
      try {
        const r = await cliReport(root, ['--root', root, '--allow-actions', '--only', IDS, ...cli], 'chunks');
        for (const id of IDS.split(',')) assert.equal(r.byId.get(id)?.status, 'pass', `${id}: ${r.byId.get(id)?.reason}`);
        assert.equal(r.code, 0, r.out);
      } finally {
        await mock.stop();
        rmSync(root, { recursive: true, force: true });
      }
    });
  }

  test('without --allow-actions the frames test is skipped', async () => {
    const root = tempRoot();
    const mock = await startMock(root);
    try {
      const r = await cliReport(root, ['--root', root, '--only', 'chunks.subscribe.frames'], 'noactions');
      assert.equal(r.byId.get('chunks.subscribe.frames').status, 'skip');
    } finally {
      await mock.stop();
      rmSync(root, { recursive: true, force: true });
    }
  });
});

describe('3D map ops against the mock (map.volume, biomes.list, blocks.list, pack.*)', () => {
  const IDS = [
    'map3d.capability-consistency', 'map.volume.schema', 'map.volume.band', 'map.volume.y-range', 'map.volume.missing', 'map.volume.paging', 'map.volume.args',
    'biomes.list.schema', 'blocks.list.schema', 'blocks.list.hash', 'blocks.list.since-hash', 'blocks.list.paging', 'blocks.list.args', 'blocks.list.matches-map',
    'pack.manifest.schema', 'pack.manifest.hash', 'pack.manifest.args', 'pack.get.schema', 'pack.get.accounting', 'pack.get.args',
  ];
  for (const [name, extra] of [
    ['unix', []],
    ['tcp', ['--tcp']],
    ['unix with --chunk-churn (volume and surface stay consistent while a chunk changes)', ['--chunk-churn']],
    ['unix, indices still being built for 3 s (busy is retried)', ['--busy-ms', '3000']],
  ]) {
    test(`${name}: all 3D map tests pass`, async () => {
      const root = tempRoot();
      const mock = await startMock(root, extra);
      try {
        const r = await cliReport(root, ['--root', root, '--only', IDS.join(',')], 'map3d');
        for (const id of IDS) assert.equal(r.byId.get(id)?.status, 'pass', `${id}: ${r.byId.get(id)?.reason}`);
        assert.equal(r.code, 0, r.out);
      } finally {
        await mock.stop();
        rmSync(root, { recursive: true, force: true });
      }
    });
  }

  test('without the optional ops (--no-optional-ops) every op test is skipped, the capability check passes and unadvertised ops answer unsupported', async () => {
    const root = tempRoot();
    const mock = await startMock(root, ['--no-optional-ops']);
    try {
      const ids = [...IDS, 'ops.unadvertised-unsupported', 'ops.advertised-not-unsupported', 'hello.schema'];
      const r = await cliReport(root, ['--root', root, '--only', ids.join(',')], 'noopt');
      for (const id of IDS) {
        const want = id === 'map3d.capability-consistency' ? 'pass' : 'skip';
        assert.equal(r.byId.get(id)?.status, want, `${id}: ${r.byId.get(id)?.reason}`);
      }
      assert.equal(r.byId.get('ops.unadvertised-unsupported').status, 'pass', r.byId.get('ops.unadvertised-unsupported').reason);
      assert.match(r.byId.get('ops.unadvertised-unsupported').reason, /unadvertised ops/);
      assert.equal(r.byId.get('hello.schema').status, 'pass', r.byId.get('hello.schema').reason);
      assert.equal(r.code, 0, r.out);
    } finally {
      await mock.stop();
      rmSync(root, { recursive: true, force: true });
    }
  });
});

describe('move_item / move_item_live tests against the mock (D24)', () => {
  const MOVE_IDS = [
    'move.advertised', 'move.empty-destination', 'move.empty-destination.item', 'move.swap', 'move.swap.item', 'move.stack-full', 'move.stack-full.item',
    'move.stack-remainder', 'move.stack-remainder.item', 'move.full-destination-swaps', 'move.full-destination-swaps.item', 'move.stale-destination',
    'move.stale-destination.item', 'move.no-destination-expectation', 'move.no-destination-expectation.item', 'move.same-slot', 'move.same-slot.item',
    'move.armor-mismatch', 'move.armor-fit', 'move.item.destination-checks',
  ];
  for (const [name, extra] of [['unix', []], ['tcp', ['--tcp']]]) {
    test(`${name}: every move test passes with --allow-actions and leaves the inventory as it was`, async () => {
      const root = tempRoot();
      const mock = await startMock(root, extra);
      try {
        const r = await cliReport(root, ['--root', root, '--allow-actions', '--only', MOVE_IDS.join(',')], 'move');
        for (const id of MOVE_IDS) assert.equal(r.byId.get(id)?.status, 'pass', `${id}: ${r.byId.get(id)?.reason}`);
        assert.equal(r.code, 0, r.out);
        // a second run sees the same free slots again (nothing was left behind)
        const r2 = await cliReport(root, ['--root', root, '--allow-actions', '--only', 'move.swap,player.inventory.digests'], 'move2');
        assert.equal(r2.byId.get('move.swap').status, 'pass');
        assert.equal(r2.byId.get('player.inventory.digests').status, 'pass');
      } finally {
        await mock.stop();
        rmSync(root, { recursive: true, force: true });
      }
    });
  }

  test('without --allow-actions only move.advertised runs', async () => {
    const root = tempRoot();
    const mock = await startMock(root);
    try {
      const r = await cliReport(root, ['--root', root, '--only', 'move.advertised,move.swap,move.item.destination-checks'], 'move-noact');
      assert.equal(r.byId.get('move.advertised').status, 'pass');
      assert.equal(r.byId.get('move.swap').status, 'skip');
      assert.equal(r.byId.get('move.item.destination-checks').status, 'skip');
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
  { brk: 'common-key-in-game', mode: 'unix', fail: ['player.get.common-keys-not-in-game'] },
  { brk: 'inventory-in-player-get', mode: 'unix', fail: ['player.get.common-keys-not-in-game'] },
  { brk: 'xp-keys-in-game', mode: 'unix', fail: ['profile.mc.player-game'] },
  { brk: 'slot-on-top-level', mode: 'unix', fail: ['player.inventory.schema'] },
  { brk: 'address-present', mode: 'unix', fail: ['envelope.no-address'] },
  { brk: 'skip-render-components', mode: 'unix', fail: ['player.inventory.digests'] },
  { brk: 'mainhand-slot', mode: 'unix', fail: ['profile.mc.containers'] },
  { brk: 'bad-block-data', mode: 'unix', fail: ['player.nearby.blocks-mc'] },
  { brk: 'block-index-oob', mode: 'unix', fail: ['player.nearby.blocks-mc'] },
  { brk: 'chunk-bad-length', mode: 'unix', fail: ['map.chunks.schema'] },
  { brk: 'chunk-wrong-hash', mode: 'unix', fail: ['map.chunks.schema'] },
  { brk: 'chunk-waterlogged-as-water', mode: 'unix', fail: ['map.chunks.schema'] },
  { brk: 'chunk-missing-wrong', mode: 'unix', fail: ['map.chunks.missing'] },
  { brk: 'chunk-initial-dump', mode: 'unix', fail: ['chunks.subscribe.ack'] },
  { brk: 'chunk-no-coalesce', mode: 'unix', actions: true, fail: ['chunks.subscribe.frames'] },
  { brk: 'chunk-oversize-frame', mode: 'unix', actions: true, fail: ['chunks.subscribe.frames'] },
  { brk: 'asset-slice-gap', mode: 'unix', fail: ['assets.get.chunks'] },
  { brk: 'asset-slice-wrong-layer', mode: 'unix', fail: ['assets.get.chunks'] },
  { brk: 'asset-slice-bad-hash', mode: 'unix', fail: ['assets.get.chunks'] },
  { brk: 'asset-slice-hash-differs', mode: 'unix', fail: ['assets.get.chunks'] },
  { brk: 'move-no-swap', mode: 'unix', actions: true, fail: ['move.swap', 'move.swap.item', 'move.stack-full', 'move.stack-full.item', 'move.stack-remainder', 'move.full-destination-swaps', 'move.item.destination-checks'] },
  { brk: 'move-overstack', mode: 'unix', actions: true, fail: ['move.stack-remainder', 'move.stack-remainder.item', 'move.full-destination-swaps', 'move.full-destination-swaps.item'] },
  { brk: 'move-armor-unchecked', mode: 'unix', actions: true, fail: ['move.armor-mismatch'] },
  { brk: 'move-stale-ok', mode: 'unix', actions: true, fail: ['move.empty-destination', 'move.stale-destination', 'move.stale-destination.item', 'move.no-destination-expectation', 'move.no-destination-expectation.item', 'move.item.destination-checks'] },
  { brk: 'max-connections-4', mode: 'unix', fail: ['conn.eight-concurrent'] },
  { brk: 'feature-biomes-missing', mode: 'unix', fail: ['map3d.capability-consistency'] },
  { brk: 'feature-pack-half', mode: 'unix', fail: ['map3d.capability-consistency'] },
  { brk: 'volume-air-in-palette', mode: 'unix', fail: ['map.volume.schema'] },
  { brk: 'volume-wrong-band', mode: 'unix', fail: ['map.volume.band'] },
  { brk: 'volume-unsorted-state', mode: 'unix', fail: ['map.volume.schema'] },
  { brk: 'volume-bad-length', mode: 'unix', fail: ['map.volume.schema'] },
  { brk: 'volume-biome-mismatch', mode: 'unix', fail: ['map.volume.band'] },
  { brk: 'volume-dup-answer', mode: 'unix', fail: ['map.volume.missing'] },
  { brk: 'volume-lose-on-page', mode: 'unix', fail: ['map.volume.paging'] },
  { brk: 'volume-ignore-y', mode: 'unix', fail: ['map.volume.y-range'] },
  { brk: 'volume-too-many-ok', mode: 'unix', fail: ['map.volume.args'] },
  { brk: 'volume-missing-wrong', mode: 'unix', fail: ['map.volume.missing'] },
  { brk: 'volume-surface-air', mode: 'unix', fail: ['map.volume.band'] },
  { brk: 'volume-bad-y-order', mode: 'unix', fail: ['map.volume.args'] },
  { brk: 'biomes-unsorted', mode: 'unix', fail: ['biomes.list.schema'] },
  { brk: 'biomes-bad-color', mode: 'unix', fail: ['biomes.list.schema'] },
  { brk: 'biomes-bad-modifier', mode: 'unix', fail: ['biomes.list.schema'] },
  { brk: 'biomes-no-plains', mode: 'unix', fail: ['biomes.list.schema'] },
  { brk: 'biomes-missing-chunk-biome', mode: 'unix', fail: ['biomes.list.schema'] },
  { brk: 'blocks-wrong-hash', mode: 'unix', fail: ['blocks.list.hash'] },
  { brk: 'blocks-unsorted', mode: 'unix', fail: ['blocks.list.schema'] },
  { brk: 'blocks-state-same', mode: 'unix', fail: ['blocks.list.schema'] },
  { brk: 'blocks-since-ignored', mode: 'unix', fail: ['blocks.list.since-hash'] },
  { brk: 'blocks-bad-cursor-ok', mode: 'unix', fail: ['blocks.list.args'] },
  { brk: 'blocks-page-hash', mode: 'unix', fail: ['blocks.list.hash'] },
  { brk: 'blocks-bad-light', mode: 'unix', fail: ['blocks.list.schema'] },
  { brk: 'pack-bad-hash', mode: 'unix', fail: ['pack.get.schema', 'pack.get.accounting'] },
  { brk: 'pack-listing-hash', mode: 'unix', fail: ['pack.manifest.hash'] },
  { brk: 'pack-unsorted', mode: 'unix', fail: ['pack.manifest.schema'] },
  { brk: 'pack-path-rule', mode: 'unix', fail: ['pack.manifest.schema'] },
  { brk: 'pack-accounting', mode: 'unix', fail: ['pack.get.accounting'] },
  { brk: 'pack-deferred-lost', mode: 'unix', fail: ['pack.get.accounting'] },
  { brk: 'pack-dup-twice', mode: 'unix', fail: ['pack.get.accounting'] },
  { brk: 'pack-get-limit', mode: 'unix', fail: ['pack.get.args'] },
  { brk: 'pack-bad-path-served', mode: 'unix', fail: ['pack.get.args'] },
  { brk: 'pack-too-large-served', mode: 'unix', fail: ['pack.get.accounting'] },
];
if (SLOW_OK) BREAK_CASES.push({ brk: 'no-ping', mode: 'unix', slow: true, fail: ['ping.keepalive-60s'] });

describe('mock breaks are detected', () => {
  for (const c of BREAK_CASES) {
    test(`--break ${c.brk} (${c.mode}) fails ${c.fail.join(', ')}`, async () => {
      const root = tempRoot();
      const extra = [...(c.mode === 'tcp' ? ['--tcp'] : []), '--busy-ms', '0', '--break', c.brk, ...(c.slow ? ['--fast-timers'] : [])];
      const mock = await startMock(root, extra);
      try {
        const args = ['--root', root, '--only', [...c.fail, ...(c.slow ? [] : [])].join(',')];
        if (c.slow) args.push('--slow');
        if (c.actions) args.push('--allow-actions');
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

describe('asset hash with layers, emissive and vanilla references (NOTES.md D29, D30)', () => {
  // Vectors computed independently with a minimal canonical msgpack encoder in Python.
  const png = (...b) => Uint8Array.from(b);
  test('an asset without the new keys hashes as before', () => {
    assert.equal(assetHash({ render: 'static', textures: { icon: { png: png(1, 2, 3), width: 16, height: 16 } } }), '6344382965f9dfa3');
  });
  test('layers, tint_index, emissive and approximate are covered', () => {
    const a = {
      render: 'static', approximate: true,
      textures: {
        icon: { png: png(1, 2, 3), width: 16, height: 16 },
        layers: [
          { png: png(4), width: 16, height: 16 },
          { png: png(5), width: 16, height: 16, tint_index: 1, emissive: { png: png(6), width: 16, height: 16 } },
        ],
      },
    };
    assert.equal(assetHash(a), '1169035018238026');
  });
  test('model_ref covers vanilla_textures (NOTES.md D30)', () => {
    const a = { render: 'model_ref', model: { textures: { all: 'minecraft:block/stone' } }, vanilla_textures: ['minecraft:block/stone'], textures: {} };
    assert.equal(assetHash(a), 'e97db73138fe84c1');
  });
});
