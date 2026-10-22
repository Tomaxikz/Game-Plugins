// map.volume (operations.md 6c, games/minecraft.md 6b) and the capability check for the optional 3D map ops
// (map.volume, biomes.list, blocks.list, pack.manifest, pack.get).

import { Check, fail, Skip, isIntV, isObj, isStr } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import {
  MAX_VOLUME_CHUNKS,
  MAX_VOLUME_HEIGHT,
  volumeChunkErrors,
  u16Array,
  cellState,
  highestNonAir,
  volumeBiomes,
  surfaceColumns,
  stateId,
  isWaterId,
} from '../lib/volume.mjs';
import { okData, errCode } from './helpers.mjs';
import { resolveTarget, baseArgs, ring } from './chunks.mjs';

const pairKey = (p) => `${num(p[0])},${num(p[1])}`;
const chunkKey = (c) => `${num(c.cx)},${num(c.cz)}`;
const ATTEMPTS = 3; // cross-checks compare two live reads; a live server may change a block in between

/** Chunks of an (2r)x(2r) square whose south-west corner is (cx - r, cz - r), r = 4 gives 64. */
function square64(cx, cz) {
  const out = [];
  for (let dz = -4; dz < 4; dz++) for (let dx = -4; dx < 4; dx++) out.push([cx + dx, cz + dz]);
  return out;
}

/** Follows `cursor` until the last page. Returns { chunks, missing, pages }. Fails on a malformed page envelope. */
async function volumeAll(ctx, t, chunks, extra = {}) {
  const args0 = baseArgs(t, { chunks, ...extra });
  const outChunks = [];
  const missing = [];
  let cursor;
  let pages = 0;
  for (;;) {
    const d = okData(await ctx.call('map.volume', cursor === undefined ? args0 : { ...args0, cursor }), `map.volume page ${pages}`);
    pages++;
    if (!Array.isArray(d.chunks) || !Array.isArray(d.missing)) fail('data must be {chunks: [], missing: [[cx, cz]]}');
    for (const m of d.missing) if (!(Array.isArray(m) && m.length === 2 && isIntV(m[0]) && isIntV(m[1]))) fail(`missing entry ${JSON.stringify(m)} is not [cx, cz]`);
    outChunks.push(...d.chunks);
    missing.push(...d.missing);
    if (d.more === true) {
      if (!isStr(d.cursor) || d.cursor === '') fail(`page ${pages} has more: true but no cursor string`);
      if (d.chunks.length === 0 && d.missing.length === 0) fail(`page ${pages} has more: true but carries nothing (no progress)`);
      cursor = d.cursor;
      if (pages > 200) fail('paging did not terminate after 200 pages');
    } else {
      if ('more' in d && d.more !== false) fail(`more must be a bool, got ${JSON.stringify(d.more)}`);
      return { chunks: outChunks, missing, pages };
    }
  }
}

/** Every requested chunk exactly once across chunks + missing (duplicates in the request count once); request order. */
function coverageErrors(req, res) {
  const e = [];
  const want = [...new Set(req.map(pairKey))];
  const wantSet = new Set(want);
  const count = new Map();
  for (const c of res.chunks) {
    if (!isObj(c) || !isIntV(c.cx) || !isIntV(c.cz)) continue;
    count.set(chunkKey(c), (count.get(chunkKey(c)) ?? 0) + 1);
  }
  for (const m of res.missing) count.set(pairKey(m), (count.get(pairKey(m)) ?? 0) + 1);
  for (const k of want) {
    const n = count.get(k) ?? 0;
    if (n !== 1) e.push(`requested chunk ${k} appears ${n} times across chunks + missing, pages included (must be exactly 1)`);
  }
  for (const [k, n] of count) if (!wantSet.has(k)) e.push(`chunk ${k} was not requested (${n}x)`);
  const idx = new Map(want.map((k, i) => [k, i]));
  let last = -1;
  for (const c of res.chunks) {
    if (!isObj(c) || !isIntV(c.cx) || !isIntV(c.cz)) continue;
    const i = idx.get(chunkKey(c));
    if (i === undefined) continue;
    if (i < last) {
      e.push(`chunks are not in request order (${chunkKey(c)} after a later request entry)`);
      break;
    }
    last = i;
  }
  return e;
}

const heightOf = (c) => num(c.height);
const topOf = (c) => num(c.y0) + num(c.height) - 1;

/** Structural errors of every chunk of a result (first few). */
function allShapeErrors(res) {
  return res.chunks.flatMap((c) => volumeChunkErrors(c));
}

/** The band/surface/biome comparison of one volume result with the map.chunks surfaces of the same chunks. */
function bandErrors(vol, surfaces) {
  const e = [];
  const counts = new Map();
  const note = (cat, msg) => {
    const n = counts.get(cat) ?? 0;
    counts.set(cat, n + 1);
    if (n < 2) e.push(msg);
  };
  // World minimum Y from the all-air columns of the surfaces (D23 b: y = world minimum Y).
  const minYs = new Map();
  for (const c of vol.chunks) {
    const sc = surfaces.get(chunkKey(c));
    if (!sc || sc.void === true) continue;
    for (const col of surfaceColumns(sc)) if (!col.surface) minYs.set(col.y, (minYs.get(col.y) ?? 0) + 1);
  }
  const knownMinY = minYs.size === 1 ? [...minYs.keys()][0] : null;
  let compared = 0;
  for (const c of vol.chunks) {
    const k = chunkKey(c);
    const sc = surfaces.get(k);
    if (!sc) continue; // not loaded for map.chunks (live difference): nothing to compare
    if (volumeChunkErrors(c).length) continue; // reported by the schema test
    compared++;
    const cols = surfaceColumns(sc);
    const blocks = u16Array(c.blocks);
    const y0 = num(c.y0);
    const h = heightOf(c);
    const withSurface = cols.filter((x) => x.surface);
    if (withSurface.length === 0) {
      if (h !== 0) note('empty', `chunk ${k}: no column has a surface block, so height must be 0, got ${h}`);
    } else if (h === 0) {
      note('empty', `chunk ${k}: height 0 although ${withSurface.length} columns have a surface block`);
    } else {
      const lowest = Math.min(...withSurface.map((x) => x.y));
      const maxSurface = Math.max(...withSurface.map((x) => x.y));
      const top = topOf(c);
      const hi = highestNonAir(c, blocks);
      if (hi !== top) note('top', `chunk ${k}: the top level y=${top} must hold the highest non-air block, the highest found is ${hi === null ? 'none' : `y=${hi}`}`);
      if (top < maxSurface) note('top', `chunk ${k}: band ends at y=${top} below the surface block at y=${maxSurface}`);
      const m = Math.max(lowest - 4, top - (MAX_VOLUME_HEIGHT - 1));
      if (y0 < m) note('y0', `chunk ${k}: y0 ${y0} < max(lowest surface ${lowest} - 4, highest ${top} - 159) = ${m}`);
      else if (y0 > m) {
        const okMin = knownMinY !== null ? y0 === knownMinY : y0 % 16 === 0 && y0 <= lowest;
        if (!okMin) note('y0', `chunk ${k}: y0 ${y0} is above max(lowest - 4, highest - 159) = ${m} and is not the world minimum Y (${knownMinY ?? 'a multiple of 16'})`);
      }
      for (let i = 0; i < 256; i++) {
        const col = cols[i];
        if (!col.surface) continue;
        const x = i & 15;
        const z = i >> 4;
        const at = `chunk ${k} column (${x},${z})`;
        if (col.y >= y0 && col.y <= top) {
          const s = cellState(c, blocks, x, col.y, z);
          if (s === null) note('surface', `${at}: air at the surface block y=${col.y} (map.chunks says ${col.id})`);
          else if (stateId(s) !== col.id) note('surface', `${at}: block at y=${col.y} is ${s}, map.chunks surface is ${col.id}`);
        }
        for (let d = 1; d < col.depth; d++) {
          const yy = col.y - d;
          if (yy < y0 || yy > top) continue;
          const s = cellState(c, blocks, x, yy, z);
          if (s === null || !isWaterId(stateId(s))) {
            note('water', `${at}: y=${yy} should be water (water_depth ${col.depth} from y=${col.y}), found ${s ?? 'air'}`);
            break;
          }
        }
        if (col.depth > 0 && col.floorId !== null) {
          const yy = col.y - col.depth;
          if (yy >= y0 && yy <= top) {
            const s = cellState(c, blocks, x, yy, z);
            if (s === null || stateId(s) !== col.floorId) note('water', `${at}: floor at y=${yy} should be ${col.floorId}, found ${s ?? 'air'}`);
          }
        }
      }
    }
    if (sc.void !== true) {
      const vb = volumeBiomes(c);
      for (let i = 0; i < 256; i++) {
        if (vb[i] !== cols[i].biome) {
          note('biome', `chunk ${k} column ${i}: volume biome ${vb[i]} != map.chunks biome ${cols[i].biome}`);
          break;
        }
      }
    }
  }
  return { errors: e, compared };
}

/** Expected slice rule 4: y_min/y_max narrow the unfiltered band. Returns problems comparing `narrow` to `base`. */
function narrowErrors(base, narrow, yMin, yMax, label) {
  const e = [];
  const k = chunkKey(base);
  const baseBlocks = u16Array(base.blocks);
  const blocks = u16Array(narrow.blocks);
  let lo = num(base.y0);
  let hi = topOf(base);
  if (heightOf(base) === 0) hi = lo - 1;
  if (yMin !== undefined) lo = Math.max(lo, yMin);
  if (yMax !== undefined) hi = Math.min(hi, yMax);
  const exp = hi >= lo ? hi - lo + 1 : 0;
  if (heightOf(narrow) !== exp) {
    e.push(`${label} chunk ${k}: height ${heightOf(narrow)}, expected ${exp} (levels ${lo}..${hi}; unfiltered ${num(base.y0)}..${topOf(base)})`);
    return e;
  }
  if (exp === 0) return e;
  if (num(narrow.y0) !== lo) e.push(`${label} chunk ${k}: y0 ${num(narrow.y0)}, expected ${lo}`);
  else {
    for (let y = lo; y <= hi && e.length < 2; y++) {
      for (let z = 0; z < 16 && e.length < 2; z++) {
        for (let x = 0; x < 16; x++) {
          const a = cellState(base, baseBlocks, x, y, z);
          const b = cellState(narrow, blocks, x, y, z);
          if (a !== b) {
            e.push(`${label} chunk ${k}: block (${x},${y},${z}) is ${b ?? 'air'} with the range, ${a ?? 'air'} without`);
            break;
          }
        }
      }
    }
  }
  return e;
}

export const tests = [
  {
    id: 'map3d.capability-consistency',
    group: 'map3d',
    title: 'features map.volume, biomes, blocks, pack are advertised exactly when their ops are (pack needs pack.manifest and pack.get)',
    needs: ['hello'],
    run(ctx) {
      const { ops, features } = ctx.caps;
      const c = new Check('3D map capabilities');
      c.ok(features.includes('map.volume') === ops.includes('map.volume'), 'map.volume feature must match the map.volume op');
      c.ok(features.includes('biomes') === ops.includes('biomes.list'), 'biomes feature must match the biomes.list op');
      c.ok(features.includes('blocks') === ops.includes('blocks.list'), 'blocks feature must match the blocks.list op');
      const pack = ops.includes('pack.manifest') && ops.includes('pack.get');
      c.ok(features.includes('pack') === pack, 'pack feature must match pack.manifest + pack.get (both ops, or neither and no feature)');
      c.ok(ops.includes('pack.manifest') === ops.includes('pack.get'), 'pack.manifest and pack.get must be advertised together');
      c.done();
      const on = ['map.volume', 'biomes.list', 'blocks.list', 'pack.manifest', 'pack.get'].filter((o) => ops.includes(o));
      return on.length ? `advertised: ${on.join(', ')}; features consistent` : 'none of the optional 3D map ops advertised; no feature either';
    },
  },
  {
    id: 'map.volume.schema',
    group: 'map3d',
    title: 'map.volume around the player (or [0,0]): ChunkVolume shape, blocks = height x 512 B, biome 512 B, palette strings normalised and without air, indices valid, every chunk once',
    needs: ['op:map.volume', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const req = ring(t.cx, t.cz, 3);
      const res = await volumeAll(ctx, t, req);
      const c = new Check('map.volume');
      for (const m of allShapeErrors(res)) c.errs.push(m);
      for (const m of coverageErrors(req, res)) c.errs.push(m);
      c.done();
      if (t.player && res.chunks.length === 0) fail(`no chunk returned around player ${t.player.name} at chunk [${t.cx},${t.cz}]; their own chunk is loaded`);
      if (t.player && !res.chunks.some((x) => chunkKey(x) === `${t.cx},${t.cz}`)) fail(`the player's own chunk [${t.cx},${t.cz}] is not in chunks`);
      const hs = res.chunks.map(heightOf);
      const flat = res.chunks.filter((x) => heightOf(x) === 0).length;
      return `${res.chunks.length} chunks (${flat} with height 0${hs.length ? `, max height ${Math.max(...hs)}` : ''}), ${res.missing.length} missing, ${res.pages} page(s)`;
    },
  },
  {
    id: 'map.volume.band',
    group: 'map3d',
    title: 'map.volume vs map.chunks for the same chunks: y0 = max(lowest - 4, min Y, highest - 159), top = highest non-air, surface blocks, water/floor and biome per column agree',
    needs: ['op:map.volume', 'op:map.chunks', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const req = ring(t.cx, t.cz, 3);
      let last = null;
      for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
        const vol = await volumeAll(ctx, t, req);
        const sd = okData(await ctx.call('map.chunks', baseArgs(t, { chunks: req })), 'map.chunks');
        if (sd.more === true) throw new Skip('map.chunks answer is paged; the comparison needs one answer');
        const surfaces = new Map((sd.chunks ?? []).map((s) => [chunkKey(s), s]));
        last = bandErrors(vol, surfaces);
        if (last.errors.length === 0) {
          if (last.compared === 0) throw new Skip('no chunk is loaded for both map.volume and map.chunks');
          const tall = vol.chunks.filter((x) => heightOf(x) === MAX_VOLUME_HEIGHT).length;
          return `${last.compared} chunks agree with map.chunks (${tall} at the 160 level cap)`;
        }
      }
      fail(last.errors.slice(0, 6).join('; ') + (last.errors.length > 6 ? ` (+${last.errors.length - 6} more)` : ''));
      return '';
    },
  },
  {
    id: 'map.volume.y-range',
    group: 'map3d',
    title: 'map.volume y_min / y_max narrow the band (y0 = max(y0, y_min), top = min(highest, y_max)), never widen it; blocks equal the unfiltered slice',
    needs: ['op:map.volume', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const req = ring(t.cx, t.cz, 2);
      let errs = [];
      let n = 0;
      for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
        errs = [];
        n = 0;
        const full = await volumeAll(ctx, t, req);
        const valid = full.chunks.filter((x) => volumeChunkErrors(x).length === 0 && heightOf(x) >= 4);
        if (valid.length === 0) throw new Skip('no loaded chunk with a band of at least 4 levels to narrow');
        valid.sort((a, b) => heightOf(b) - heightOf(a));
        const base = valid[0];
        const y0 = num(base.y0);
        const top = topOf(base);
        const cases = [
          { label: 'y_min', yMin: y0 + 2 },
          { label: 'y_max', yMax: top - 2 },
          { label: 'y_min+y_max', yMin: y0 + 1, yMax: top - 1 },
          { label: 'one level', yMin: top - 1, yMax: top - 1 },
          { label: 'no widening', yMin: y0 - 100, yMax: top + 100 },
          { label: 'y_min above the band', yMin: top + 1 },
          { label: 'y_max below the band', yMax: y0 - 1 },
        ];
        const one = [[num(base.cx), num(base.cz)]];
        const ref = (await volumeAll(ctx, t, one)).chunks[0];
        if (!ref) throw new Skip('the chunk is no longer loaded');
        for (const cs of cases) {
          const extra = {};
          if (cs.yMin !== undefined) extra.y_min = cs.yMin;
          if (cs.yMax !== undefined) extra.y_max = cs.yMax;
          const r = await volumeAll(ctx, t, one, extra);
          const nar = r.chunks[0];
          if (!nar) {
            errs.push(`${cs.label}: the chunk is missing from the answer`);
            continue;
          }
          const bad = volumeChunkErrors(nar);
          if (bad.length) errs.push(`${cs.label}: ${bad[0]}`);
          else errs.push(...narrowErrors(ref, nar, cs.yMin, cs.yMax, cs.label));
          n++;
        }
        // A range on a multi-chunk request applies to every chunk.
        const yy = y0 + 2;
        const multi = await volumeAll(ctx, t, req, { y_min: yy, y_max: yy + 1 });
        const refs = new Map(full.chunks.map((x) => [chunkKey(x), x]));
        for (const nar of multi.chunks) {
          const b = refs.get(chunkKey(nar));
          if (!b || volumeChunkErrors(nar).length || volumeChunkErrors(b).length) continue;
          errs.push(...narrowErrors(b, nar, yy, yy + 1, 'multi-chunk range'));
        }
        if (errs.length === 0) return `${n} range cases on chunk ${chunkKey(base)} (band ${y0}..${top}) and a ${multi.chunks.length}-chunk range request`;
      }
      fail(errs.slice(0, 6).join('; ') + (errs.length > 6 ? ` (+${errs.length - 6} more)` : ''));
      return '';
    },
  },
  {
    id: 'map.volume.missing',
    group: 'map3d',
    title: 'map.volume: far (unloaded) chunks are in missing, never in chunks; duplicates in the request are answered once',
    needs: ['op:map.volume', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const far = [
        [1000000, 1000000],
        [-1000000, 7],
      ];
      const near = t.player ? [[t.cx, t.cz]] : [];
      const req = [...near, ...far, ...near, far[0], ...far];
      const res = await volumeAll(ctx, t, req);
      const c = new Check('map.volume');
      for (const m of coverageErrors(req, res)) c.errs.push(m);
      for (const f of far) {
        c.ok(res.missing.filter((m) => pairKey(m) === pairKey(f)).length === 1 && !res.chunks.some((x) => chunkKey(x) === pairKey(f)), `far chunk ${pairKey(f)} must be missing exactly once, not loaded (the bridge must never load or generate chunks, T7)`);
      }
      if (near.length) c.ok(res.chunks.filter((x) => chunkKey(x) === pairKey(near[0])).length === 1, `the player's chunk ${pairKey(near[0])} must be answered exactly once although it is requested twice`);
      c.done();
      return `${req.length} requested entries (duplicates included): ${res.chunks.length} loaded, ${res.missing.length} missing, each distinct chunk once`;
    },
  },
  {
    id: 'map.volume.paging',
    group: 'map3d',
    title: 'map.volume with 64 chunks: pages (more + cursor) continue the same request; every chunk exactly once across all pages, in request order',
    needs: ['op:map.volume', 'hello'],
    timeout: 40000,
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const req = square64(t.cx, t.cz);
      if (req.length !== MAX_VOLUME_CHUNKS) fail('suite error: 64 chunks expected');
      const res = await volumeAll(ctx, t, req);
      const c = new Check('map.volume');
      for (const m of allShapeErrors(res)) c.errs.push(m);
      for (const m of coverageErrors(req, res)) c.errs.push(m);
      c.done();
      return `${req.length} chunks: ${res.chunks.length} loaded, ${res.missing.length} missing, ${res.pages} page(s)${res.pages === 1 ? ' (answer fitted one frame: cursor paging not exercised)' : ''}`;
    },
  },
  {
    id: 'map.volume.args',
    group: 'map3d',
    title: 'map.volume: 65 chunks, missing/malformed chunks, non-integer y_min/y_max, y_min > y_max, missing world: invalid_args; unknown world or dimension: not_found',
    needs: ['op:map.volume', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const many = Array.from({ length: MAX_VOLUME_CHUNKS + 1 }, (_, i) => [t.cx + i, t.cz]);
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: many })), 'invalid_args', '65 chunks');
      errCode(await ctx.call('map.volume', baseArgs(t, {})), 'invalid_args', 'missing chunks');
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: 'all' })), 'invalid_args', 'chunks is a string');
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: [[1]] })), 'invalid_args', 'chunk pair of one number');
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: [['a', 'b']] })), 'invalid_args', 'chunk pair of strings');
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: [[0.5, 1]] })), 'invalid_args', 'chunk pair of a non-integer');
      const one = [[t.cx, t.cz]];
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: one, y_min: 'low' })), 'invalid_args', 'y_min is a string');
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: one, y_max: 'high' })), 'invalid_args', 'y_max is a string');
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: one, y_min: 1.5 })), 'invalid_args', 'y_min is not an integer');
      errCode(await ctx.call('map.volume', baseArgs(t, { chunks: one, y_min: 70, y_max: 60 })), 'invalid_args', 'y_min > y_max');
      errCode(await ctx.call('map.volume', { chunks: one }), 'invalid_args', 'missing world');
      errCode(await ctx.call('map.volume', { world: 'pm_conformance_no_such_world', chunks: one }), 'not_found', 'unknown world');
      errCode(await ctx.call('map.volume', { world: t.world, dimension: 'minecraft:pm_conformance_no_such_dimension', chunks: one }), 'not_found', 'unknown dimension');
      return 'invalid_args / not_found as specified';
    },
  },
];
