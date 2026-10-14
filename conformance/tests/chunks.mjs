// Map chunk surfaces: map.chunks, chunks.subscribe, chunks.unsubscribe
// (operations.md 6b, 7.3, 8; games/minecraft.md 6a; NOTES.md D23).

import { Check, fail, Skip, isIntV, isObj } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { chunkSurfaceErrors, MAX_CHUNKS_FRAME, MAX_CHUNKS_REQUEST } from '../lib/chunks.mjs';
import { okData, errCode, sleep } from './helpers.mjs';

const RING = 3; // map.chunks.schema requests (2*RING+1)^2 chunks around the target
const DIRECT_PING_OK = (f) => isObj(f.data) && f.data.type === 'ping';
// Chunks tolerated in the "nothing changes" window: genuine surface changes on a live server (flowing water in a
// fresh world, chunks loading around players). An initial dump sends every loaded chunk, so the limit is the larger
// of this floor and half the chunks loaded around the target.
const ORGANIC_TOLERANCE = 4;
// Receipt-time jitter allowed on the 2 s per-chunk cooldown.
const COOLDOWN_SLACK_MS = 250;

const chunkKey = (c) => `${num(c.cx)},${num(c.cz)}`;

/** World/dimension/position the chunk tests use: an online player if there is one, else the first world. */
async function resolveTarget(ctx) {
  if (ctx._chunkTarget) return ctx._chunkTarget;
  let t = null;
  const player = ctx.has('player.get') ? await ctx.getPlayer() : null;
  if (player) {
    const resp = await ctx.call('player.get', { id: player.id });
    const d = resp.ok === true ? resp.data : null;
    const pos = d?.position;
    if (d && typeof d.world === 'string' && pos && typeof ctx.n(pos.x) === 'number') {
      t = {
        player,
        world: d.world,
        dimension: typeof d.dimension === 'string' ? d.dimension : undefined,
        x: ctx.n(pos.x),
        y: ctx.n(pos.y),
        z: ctx.n(pos.z),
      };
      t.cx = Math.floor(t.x / 16);
      t.cz = Math.floor(t.z / 16);
    }
  }
  if (!t && ctx.has('server.info')) {
    const resp = await ctx.call('server.info', {});
    const w = resp.ok === true ? resp.data?.worlds?.[0] : null;
    if (w && typeof w.world === 'string') {
      t = { player: null, world: w.world, dimension: typeof w.dimension === 'string' ? w.dimension : undefined, cx: 0, cz: 0 };
    }
  }
  if (!t) throw new Skip('no world known (server.info has no worlds and no player is online)');
  ctx._chunkTarget = t;
  return t;
}

const baseArgs = (t, extra = {}) => ({ world: t.world, ...(t.dimension !== undefined ? { dimension: t.dimension } : {}), ...extra });

function ring(cx, cz, r) {
  const out = [];
  for (let dz = -r; dz <= r; dz++) for (let dx = -r; dx <= r; dx++) out.push([cx + dx, cz + dz]);
  return out;
}

function pairKey(p) {
  return `${num(p[0])},${num(p[1])}`;
}

/** Validates the frames collected on a chunks subscription; returns problems. */
function frameErrors(sub, { want, area }) {
  const e = [];
  if (sub.gaps.length) e.push(`seq gaps: ${JSON.stringify(sub.gaps.slice(0, 3))}`);
  const last = new Map(); // key -> { at, hash }
  sub.frames.forEach((f, i) => {
    if (num(f.seq) !== i) e.push(`frame ${i} has seq ${f.seq}`);
    if (DIRECT_PING_OK(f)) return;
    const d = f.data;
    if (!isObj(d)) return e.push(`frame ${i}: data is not an object`);
    for (const k of Object.keys(d)) if (k !== 'chunks' && k !== 'dropped') e.push(`frame ${i}: unexpected key ${k}`);
    if (!Array.isArray(d.chunks)) return e.push(`frame ${i}: chunks must be an array`);
    if (d.chunks.length > MAX_CHUNKS_FRAME) e.push(`frame ${i}: ${d.chunks.length} chunks, at most ${MAX_CHUNKS_FRAME} per frame`);
    if ('dropped' in d && !(isIntV(d.dropped) && num(d.dropped) > 0)) e.push(`frame ${i}: dropped must be a positive int, got ${JSON.stringify(d.dropped)}`);
    if (d.chunks.length === 0 && !('dropped' in d)) e.push(`frame ${i}: empty chunks without dropped`);
    const seen = new Set();
    for (const c of d.chunks) {
      for (const m of chunkSurfaceErrors(c, want)) e.push(`frame ${i}: ${m}`);
      if (!isObj(c) || !isIntV(c.cx) || !isIntV(c.cz)) continue;
      const k = chunkKey(c);
      if (seen.has(k)) e.push(`frame ${i}: chunk ${k} twice in one frame`);
      seen.add(k);
      if (area) {
        const x = num(c.cx);
        const z = num(c.cz);
        if (x < area.min_cx || x > area.max_cx || z < area.min_cz || z > area.max_cz) e.push(`frame ${i}: chunk ${k} is outside the subscribed area`);
      }
      const prev = last.get(k);
      const at = sub.times[i];
      if (prev) {
        if (at - prev.at < 2000 - COOLDOWN_SLACK_MS) e.push(`chunk ${k} sent again after ${at - prev.at} ms (at most one frame per chunk per 2 s)`);
        if (prev.hash === c.hash) e.push(`chunk ${k} resent with an unchanged hash ${c.hash}`);
      }
      last.set(k, { at, hash: c.hash });
    }
  });
  return e;
}

const chunkCount = (sub) => sub.frames.reduce((n, f) => n + (!DIRECT_PING_OK(f) && Array.isArray(f.data?.chunks) ? f.data.chunks.length : 0), 0);

async function teleport(client, t, x, y, z) {
  const resp = await client.request('action.run', { action: 'teleport', target: t.player.id, args: { x, y, z } }, { timeout: 7000 });
  const d = okData(resp, 'teleport');
  if (d.ok === false) fail(`teleport was refused: ${d.message ?? '(no message)'}`);
}

export const tests = [
  {
    id: 'chunks.capability-consistency',
    group: 'chunks',
    title: 'features map.chunks <=> map.chunks op; chunks.stream <=> chunks.subscribe + chunks.unsubscribe',
    needs: ['hello'],
    run(ctx) {
      const { ops, features } = ctx.caps;
      const c = new Check('chunk capabilities');
      c.ok(features.includes('map.chunks') === ops.includes('map.chunks'), 'map.chunks feature must match the map.chunks op');
      const stream = ops.includes('chunks.subscribe') && ops.includes('chunks.unsubscribe');
      c.ok(features.includes('chunks.stream') === stream, 'chunks.stream feature must match chunks.subscribe + chunks.unsubscribe ops');
      c.ok(ops.includes('chunks.subscribe') === ops.includes('chunks.unsubscribe'), 'chunks.subscribe and chunks.unsubscribe must be advertised together');
      c.done();
      return `map.chunks=${ops.includes('map.chunks')}, chunks.stream=${stream}`;
    },
  },
  {
    id: 'map.chunks.schema',
    group: 'chunks',
    title: 'map.chunks: ChunkSurface shape, bin sizes, index ranges, floor/water/air rules, D23 hash',
    needs: ['op:map.chunks', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const req = ring(t.cx, t.cz, RING);
      const d = okData(await ctx.call('map.chunks', baseArgs(t, { chunks: req })), 'map.chunks');
      if (!Array.isArray(d.chunks) || !Array.isArray(d.missing)) fail('data must be {chunks: [], missing: [[cx, cz]]}');
      const c = new Check('map.chunks');
      for (const m of d.chunks.flatMap((x) => chunkSurfaceErrors(x, { world: t.world, dimension: t.dimension }))) c.errs.push(m);
      const reqKeys = new Set(req.map(pairKey));
      const got = new Map();
      for (const ch of d.chunks) {
        if (!isObj(ch) || !isIntV(ch.cx)) continue;
        const k = chunkKey(ch);
        c.ok(reqKeys.has(k), `chunk ${k} was not requested`);
        c.ok(!got.has(k), `chunk ${k} returned twice`);
        got.set(k, ch);
      }
      for (const m of d.missing) {
        c.ok(Array.isArray(m) && m.length === 2 && isIntV(m[0]) && isIntV(m[1]), `missing entry ${JSON.stringify(m)} is not [cx, cz]`);
      }
      c.done();
      if (t.player && d.chunks.length === 0) fail(`no chunk returned around player ${t.player.name} at chunk [${t.cx},${t.cz}]; their own chunk is loaded`);
      if (t.player && !got.has(`${t.cx},${t.cz}`)) fail(`the player's own chunk [${t.cx},${t.cz}] is not in chunks`);
      let voids = 0;
      for (const ch of d.chunks) if (ch.void === true) voids++;
      return `${d.chunks.length} chunks (${voids} void), ${d.missing.length} missing, around ${t.player ? t.player.name : 'chunk [0,0]'}`;
    },
  },
  {
    id: 'map.chunks.missing',
    group: 'chunks',
    title: 'map.chunks: unloaded chunks are listed in missing, never in chunks; each requested chunk is in exactly one list',
    needs: ['op:map.chunks', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const far = [
        [1000000, 1000000],
        [-1000000, 7],
        [3, -1000000],
      ];
      const near = t.player ? [[t.cx, t.cz]] : [];
      const req = [...near, ...far];
      const d = okData(await ctx.call('map.chunks', baseArgs(t, { chunks: req })), 'map.chunks');
      if (!Array.isArray(d.chunks) || !Array.isArray(d.missing)) fail('data must be {chunks: [], missing: [[cx, cz]]}');
      if (d.more === true) throw new Skip('response is paged (more: true); coverage not checked');
      const inChunks = new Map();
      for (const ch of d.chunks) inChunks.set(chunkKey(ch), (inChunks.get(chunkKey(ch)) ?? 0) + 1);
      const inMissing = new Map();
      for (const m of d.missing) inMissing.set(pairKey(m), (inMissing.get(pairKey(m)) ?? 0) + 1);
      const c = new Check('map.chunks');
      for (const p of req) {
        const k = pairKey(p);
        const n = (inChunks.get(k) ?? 0) + (inMissing.get(k) ?? 0);
        c.ok(n === 1, `requested chunk ${k} appears ${n} times across chunks + missing (must be exactly 1)`);
      }
      for (const p of far) c.ok(inMissing.get(pairKey(p)) === 1 && !inChunks.has(pairKey(p)), `far chunk ${pairKey(p)} must be missing, not loaded (the bridge must never load or generate chunks, T7)`);
      c.done();
      return `${far.length} far chunks missing; ${d.chunks.length} loaded`;
    },
  },
  {
    id: 'map.chunks.args',
    group: 'chunks',
    title: 'map.chunks: 257 chunks and missing/malformed chunks are invalid_args; unknown world is not_found',
    needs: ['op:map.chunks', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const many = Array.from({ length: MAX_CHUNKS_REQUEST + 1 }, (_, i) => [i, 0]);
      errCode(await ctx.call('map.chunks', baseArgs(t, { chunks: many })), 'invalid_args', '257 chunks');
      errCode(await ctx.call('map.chunks', baseArgs(t, {})), 'invalid_args', 'missing chunks');
      errCode(await ctx.call('map.chunks', baseArgs(t, { chunks: 'all' })), 'invalid_args', 'chunks is a string');
      errCode(await ctx.call('map.chunks', baseArgs(t, { chunks: [[1]] })), 'invalid_args', 'chunk pair of one number');
      errCode(await ctx.call('map.chunks', { world: 'pm_conformance_no_such_world', chunks: [[0, 0]] }), 'not_found', 'unknown world');
      errCode(await ctx.call('map.chunks', { chunks: [[0, 0]] }), 'invalid_args', 'missing world');
      return '257 chunks, missing/malformed chunks, missing world: invalid_args; unknown world: not_found';
    },
  },
  {
    id: 'chunks.subscribe.ack',
    group: 'chunks',
    title: 'chunks.subscribe acks {sub} with no initial dump; second chunks subscription is busy; unsubscribe returns {}, unknown sub is not_found',
    needs: ['op:chunks.subscribe', 'op:chunks.unsubscribe', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const { client } = await ctx.open({ hello: true });
      const { resp, sub } = await client.subscribe('chunks.subscribe', baseArgs(t));
      const ack = okData(resp, 'chunks.subscribe');
      if (!isIntV(ack.sub) || !sub) fail('chunks.subscribe ack must be {sub: int}');
      // Same order as the panel: subscribe, then map.chunks for the visible area.
      const near = okData(await ctx.call('map.chunks', { ...baseArgs(t), chunks: ring(t.cx, t.cz, RING) }), 'map.chunks');
      const loadedNear = Array.isArray(near.chunks) ? near.chunks.length : 0;
      const limit = Math.max(ORGANIC_TOLERANCE, Math.floor(loadedNear / 2));
      await sleep(2500);
      const n = chunkCount(sub);
      if (n > limit) fail(`${n} chunks arrived within 2.5 s of subscribing without any change (limit ${limit}, ${loadedNear} loaded nearby): looks like an initial dump (the panel calls map.chunks instead)`);
      for (const f of sub.frames) if (!DIRECT_PING_OK(f) && !(isObj(f.data) && Array.isArray(f.data.chunks))) fail('frame without chunks array');
      errCode(await client.request('chunks.subscribe', baseArgs(t)), 'busy', 'second chunks.subscribe');
      const un = okData(await client.request('chunks.unsubscribe', { sub: sub.sub }), 'chunks.unsubscribe');
      if (Object.keys(un).length) fail('chunks.unsubscribe data must be {}');
      errCode(await client.request('chunks.unsubscribe', { sub: sub.sub }), 'not_found', 'second unsubscribe');
      errCode(await client.request('chunks.unsubscribe', { sub: 4242 }), 'not_found', 'unknown sub');
      // The slot is free again.
      const again = await client.subscribe('chunks.subscribe', baseArgs(t));
      okData(again.resp, 'chunks.subscribe after unsubscribe');
      client.close();
      return `no initial dump (${n} chunks in 2.5 s, limit ${limit}), busy, unsubscribe ok`;
    },
  },
  {
    id: 'chunks.subscribe.args',
    group: 'chunks',
    title: 'chunks.subscribe: missing world is invalid_args, unknown world is not_found, malformed area is invalid_args',
    needs: ['op:chunks.subscribe', 'hello'],
    async run(ctx) {
      const t = await resolveTarget(ctx);
      const { client } = await ctx.open({ hello: true });
      errCode(await client.request('chunks.subscribe', {}), 'invalid_args', 'missing world');
      errCode(await client.request('chunks.subscribe', { world: 'pm_conformance_no_such_world' }), 'not_found', 'unknown world');
      errCode(await client.request('chunks.subscribe', baseArgs(t, { area: 'everything' })), 'invalid_args', 'area is a string');
      errCode(await client.request('chunks.subscribe', baseArgs(t, { area: { min_cx: 0, min_cz: 0 } })), 'invalid_args', 'area without max_*');
      client.close();
      return 'invalid_args / not_found as specified';
    },
  },
  {
    id: 'chunks.subscribe.frames',
    group: 'chunks',
    title: 'chunks.subscribe frames after a teleport of ~3000 blocks: schema, <= 64 per frame, 2 s cooldown, seq, dropped; area filter (needs --allow-actions)',
    needs: ['op:chunks.subscribe', 'op:chunks.unsubscribe', 'op:action.run', 'actions', 'player', 'hello'],
    timeout: 60000,
    async run(ctx) {
      const t = await resolveTarget(ctx);
      if (!t.player || t.x === undefined) throw new Skip('needs an online player with a position');
      if (!ctx.caps.actions.some((a) => a.id === 'teleport')) throw new Skip('teleport action not advertised');
      const { client } = await ctx.open({ hello: true });
      const home = { x: t.x, y: t.y, z: t.z };
      const want = { world: t.world, dimension: t.dimension };
      let moved = false;
      // Teleporting 3000 blocks at the same Y can drop the player into the air or a wall; a death would empty the
      // inventory and break later tests. Use creative mode (no damage) for the trip when the bridge offers `gamemode`.
      let restoreMode = null;
      if (ctx.caps.actions.some((a) => a.id === 'gamemode')) {
        const g = await client.request('player.get', { id: t.player.id });
        const mode = g.ok && typeof g.data.game_mode === 'string' ? g.data.game_mode : null;
        if (mode && mode !== 'creative' && mode !== 'spectator') {
          const r = await client.request('action.run', { action: 'gamemode', target: t.player.id, args: { mode: 'creative' } });
          if (r.ok && r.data.ok !== false) restoreMode = mode;
        }
      }
      try {
        // Phase 1: no area.
        const a = await client.subscribe('chunks.subscribe', baseArgs(t));
        okData(a.resp, 'chunks.subscribe');
        moved = true;
        await teleport(client, t, home.x + 3000, home.y, home.z);
        await sleep(8000);
        const errs1 = frameErrors(a.sub, { want });
        if (errs1.length) fail(errs1.slice(0, 8).join('; '));
        const n1 = chunkCount(a.sub);
        if (n1 === 0) fail('no chunk frame arrived within 8 s of teleporting the player 3000 blocks away');
        const dropped = a.sub.frames.filter((f) => isObj(f.data) && 'dropped' in f.data).length;
        // Every chunk now loaded around the destination was loaded after subscribing (it is 3000 blocks from where the
        // player was), so it must have been streamed, unless the bridge reported discarding chunks (load mode).
        const destRing = ring(Math.floor((home.x + 3000) / 16), Math.floor(home.z / 16), RING);
        const nowLoaded = okData(await client.request('map.chunks', { ...want, chunks: destRing }), 'map.chunks at destination').chunks ?? [];
        const streamed = new Set();
        for (const f of a.sub.frames) for (const ch of (isObj(f.data) && Array.isArray(f.data.chunks) ? f.data.chunks : [])) streamed.add(`${ch.cx},${ch.cz}`);
        const notStreamed = nowLoaded.filter((ch) => !streamed.has(`${ch.cx},${ch.cz}`));
        if (!dropped && notStreamed.length) {
          fail(`${notStreamed.length} of ${nowLoaded.length} chunks loaded at the destination after subscribing were never streamed and no frame reported dropped (e.g. ${notStreamed.slice(0, 3).map((c) => `[${c.cx},${c.cz}]`).join(' ')})`);
        }
        const loadNote = nowLoaded.length < 9
          ? `; only ${nowLoaded.length} chunk(s) loaded at the destination: the server did not load the area around the teleported player (the client probably did not confirm the teleport), so load streaming was barely exercised`
          : `; ${nowLoaded.length} loaded at the destination, all streamed${dropped ? ' or covered by dropped' : ''}`;
        okData(await client.request('chunks.unsubscribe', { sub: a.sub.sub }), 'chunks.unsubscribe');

        // Phase 2: area-restricted subscription at a second location.
        const dest = { x: home.x - 3000, y: home.y, z: home.z };
        const ccx = Math.floor(dest.x / 16);
        const ccz = Math.floor(dest.z / 16);
        const area = { min_cx: ccx - 1, min_cz: ccz - 1, max_cx: ccx + 1, max_cz: ccz + 1 };
        const b = await client.subscribe('chunks.subscribe', baseArgs(t, { area }));
        okData(b.resp, 'chunks.subscribe with area');
        await teleport(client, t, dest.x, dest.y, dest.z);
        await sleep(6000);
        const errs2 = frameErrors(b.sub, { want, area });
        if (errs2.length) fail(`area subscription: ${errs2.slice(0, 8).join('; ')}`);
        const n2 = chunkCount(b.sub);
        if (n2 === 0) fail('area subscription received no chunk although the player arrived inside the area');
        return `no area: ${n1} chunks in ${a.sub.frames.length} frames (${dropped} with dropped)${loadNote}; area: ${n2} chunks, all inside`;
      } finally {
        if (moved) {
          try {
            await teleport(client, t, home.x, home.y, home.z);
          } catch {
            /* best effort: the player may need to be moved back by hand */
          }
        }
        if (restoreMode) {
          try {
            await client.request('action.run', { action: 'gamemode', target: t.player.id, args: { mode: restoreMode } });
          } catch {
            /* best effort */
          }
        }
        client.close();
      }
    },
  },
];
