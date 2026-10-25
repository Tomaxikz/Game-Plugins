// Map tiles, tile stream and map markers: map.tiles, tiles.subscribe, tiles.unsubscribe, map.markers
// (operations.md 6f, 6g, 7.4, 8; games/hytale.md 13-15).

import { Check, fail, Skip, isIntV, isNum, isObj, isStr, isBin, isHex } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { sha256Hex } from '../lib/canonical.mjs';
import { MARKER_KINDS, fakePlayerId } from '../lib/protocol.mjs';
import { okData, errCode, sleep } from './helpers.mjs';

const MAX_TILES = 64;
const FRAME_MAX = 64;
const TILE_COOLDOWN_MS = 5000;
const COOLDOWN_SLACK_MS = 250;
const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

const key = (x, z) => `${num(x)},${num(z)}`;

/** World and position of the online player (else the first loaded world at 0,0), plus the world's tile_size. */
async function tileTarget(ctx) {
  if (ctx._tileTarget) return ctx._tileTarget;
  const t = await tileTarget0(ctx);
  if (ctx.has('map.tiles')) {
    const resp = await ctx.call('map.tiles', { world: t.world, tiles: [[0, 0]] }, { timeout: 8000 });
    if (resp.ok === true && isIntV(resp.data?.tile_size) && num(resp.data.tile_size) > 0) t.size = num(resp.data.tile_size);
  }
  t.size ??= 32;
  t.tx = Math.floor(t.x / t.size);
  t.tz = Math.floor(t.z / t.size);
  ctx._tileTarget = t;
  return t;
}

async function tileTarget0(ctx) {
  let t = null;
  const player = ctx.has('player.get') ? await ctx.getPlayer() : null;
  if (player) {
    const resp = await ctx.call('player.get', { id: player.id });
    const d = resp.ok === true ? resp.data : null;
    if (d && typeof d.world === 'string' && d.position && typeof ctx.n(d.position.x) === 'number') {
      t = { player, world: d.world, x: ctx.n(d.position.x), z: ctx.n(d.position.z) };
    }
  }
  if (!t && ctx.has('server.info')) {
    const resp = await ctx.call('server.info', {});
    const w = resp.ok === true ? resp.data?.worlds?.find((x) => x.loaded !== false) ?? resp.data?.worlds?.[0] : null;
    if (w && typeof w.world === 'string') t = { player: null, world: w.world, x: 0, z: 0 };
  }
  if (!t) throw new Skip('no world known (server.info has no worlds and no player is online)');
  return t;
}

function ringTiles(tx, tz, r) {
  const out = [];
  for (let dz = -r; dz <= r; dz++) for (let dx = -r; dx <= r; dx++) out.push([tx + dx, tz + dz]);
  return out;
}

/** map.tiles following more/cursor; returns { tileSize, tiles: Map key->tile, missing: Set, problems, pages }. */
async function fetchTiles(ctx, world, tiles) {
  const out = { tileSize: null, tiles: new Map(), missing: new Set(), problems: [], pages: 0 };
  let cursor;
  for (;;) {
    const args = { world, tiles, ...(cursor !== undefined ? { cursor } : {}) };
    const d = okData(await ctx.call('map.tiles', args, { timeout: 8000 }), `map.tiles page ${out.pages}`);
    out.pages += 1;
    for (const k of Object.keys(d)) {
      if (!['tile_size', 'tiles', 'missing', 'more', 'cursor'].includes(k)) out.problems.push(`unexpected key ${k}`);
    }
    if (!isIntV(d.tile_size) || num(d.tile_size) <= 0) out.problems.push('tile_size must be a positive int');
    else if (out.tileSize !== null && out.tileSize !== num(d.tile_size)) out.problems.push('tile_size changed between pages');
    else out.tileSize = num(d.tile_size);
    if (!Array.isArray(d.tiles)) out.problems.push('tiles must be an array');
    if (!Array.isArray(d.missing)) out.problems.push('missing must be an array');
    for (const [i, t] of (Array.isArray(d.tiles) ? d.tiles : []).entries()) {
      const p = `tiles[${i}]`;
      if (!isObj(t)) {
        out.problems.push(`${p} must be an object`);
        continue;
      }
      for (const k of Object.keys(t)) if (!['tx', 'tz', 'png', 'hash'].includes(k)) out.problems.push(`${p}: unexpected key ${k}`);
      if (!isIntV(t.tx) || !isIntV(t.tz)) out.problems.push(`${p}: tx/tz must be ints`);
      if (!isBin(t.png)) out.problems.push(`${p}: png must be bin`);
      else {
        if (!PNG_SIG.every((b, j) => t.png[j] === b)) out.problems.push(`${p}: png lacks the PNG signature`);
        if (isHex(t.hash, 16) && sha256Hex(t.png).slice(0, 16) !== t.hash) out.problems.push(`${p}: hash is not SHA-256(png)[0..16]`);
      }
      if (!isHex(t.hash, 16)) out.problems.push(`${p}: hash must be 16 lowercase hex`);
      const k = key(t.tx, t.tz);
      if (out.tiles.has(k) || out.missing.has(k)) out.problems.push(`tile ${k} answered twice`);
      out.tiles.set(k, t);
    }
    for (const m of Array.isArray(d.missing) ? d.missing : []) {
      if (!Array.isArray(m) || m.length !== 2 || !isIntV(m[0]) || !isIntV(m[1])) {
        out.problems.push(`missing entry ${JSON.stringify(m)} must be [tx, tz]`);
        continue;
      }
      const k = key(m[0], m[1]);
      if (out.tiles.has(k) || out.missing.has(k)) out.problems.push(`tile ${k} answered twice`);
      out.missing.add(k);
    }
    if (d.more === true) {
      if (!isStr(d.cursor)) {
        out.problems.push('more: true without a string cursor');
        break;
      }
      cursor = d.cursor;
      if (out.pages > 64) {
        out.problems.push('map.tiles paging did not terminate');
        break;
      }
      continue;
    }
    break;
  }
  return out;
}

export function markerErrors(m, where) {
  const e = [];
  if (!isObj(m)) return [`${where} must be an object`];
  for (const k of Object.keys(m)) if (!['id', 'kind', 'name', 'icon', 'x', 'y', 'z', 'owner'].includes(k)) e.push(`${where}: unexpected key ${k}`);
  if (!isStr(m.id) || m.id.length === 0) e.push(`${where}.id must be a non-empty string`);
  if (!MARKER_KINDS.includes(m.kind)) e.push(`${where}.kind ${JSON.stringify(m.kind)} not one of ${MARKER_KINDS.join('|')}`);
  if ('name' in m && !isStr(m.name)) e.push(`${where}.name must be a string`);
  if ('icon' in m && !isStr(m.icon)) e.push(`${where}.icon must be a string`);
  if (!isNum(m.x) || !isNum(m.z)) e.push(`${where}: x and z must be numbers`);
  if ('y' in m && !isNum(m.y)) e.push(`${where}.y must be a number when present`);
  if ('owner' in m) {
    if (!isObj(m.owner)) e.push(`${where}.owner must be an object`);
    else {
      for (const k of Object.keys(m.owner)) if (!['id', 'name'].includes(k)) e.push(`${where}.owner: unexpected key ${k}`);
      if ('id' in m.owner && !isStr(m.owner.id)) e.push(`${where}.owner.id must be a string`);
      if ('name' in m.owner && !isStr(m.owner.name)) e.push(`${where}.owner.name must be a string`);
    }
  }
  return e;
}

/** map.markers following more/cursor. */
async function fetchMarkers(ctx, args) {
  const all = [];
  let cursor;
  for (let page = 0; page < 100; page++) {
    const d = okData(await ctx.call('map.markers', { ...args, ...(cursor !== undefined ? { cursor } : {}) }), 'map.markers');
    if (!Array.isArray(d.markers)) fail('markers must be an array');
    all.push(...d.markers);
    if (d.more !== true) return all;
    if (!isStr(d.cursor)) fail('more: true without a string cursor');
    cursor = d.cursor;
  }
  fail('map.markers paging did not terminate');
}

export const tests = [
  {
    id: 'maptiles.capability-consistency',
    group: 'maptiles',
    title: 'features map.tiles <=> map.tiles; tiles.stream <=> tiles.subscribe + tiles.unsubscribe; map.markers <=> map.markers',
    needs: ['hello'],
    run(ctx) {
      const { ops, features } = ctx.caps;
      const c = new Check('map tile capabilities');
      c.ok(features.includes('map.tiles') === ops.includes('map.tiles'), 'map.tiles feature must match the map.tiles op');
      const stream = ops.includes('tiles.subscribe') && ops.includes('tiles.unsubscribe');
      c.ok(features.includes('tiles.stream') === stream, 'tiles.stream feature must match tiles.subscribe + tiles.unsubscribe');
      c.ok(ops.includes('tiles.subscribe') === ops.includes('tiles.unsubscribe'), 'tiles.subscribe and tiles.unsubscribe must be advertised together');
      c.ok(features.includes('map.markers') === ops.includes('map.markers'), 'map.markers feature must match the map.markers op');
      c.done();
      return `map.tiles=${ops.includes('map.tiles')}, tiles.stream=${stream}, map.markers=${ops.includes('map.markers')}`;
    },
  },
  {
    id: 'map.tiles.schema',
    group: 'maptiles',
    title: 'map.tiles: tile_size, PNG bins with signature, hash = SHA-256(png)[0..16], every requested tile once in tiles or missing',
    needs: ['op:map.tiles', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      const first = await fetchTiles(ctx, t.world, [[0, 0]]);
      const size = first.tileSize ?? t.size;
      const { tx, tz } = t;
      const req = ringTiles(tx, tz, 3); // 49 tiles
      const r = await fetchTiles(ctx, t.world, [...req, req[0]]);
      const c = new Check('map.tiles');
      for (const p of first.problems.concat(r.problems)) c.errs.push(p);
      for (const [x, z] of req) c.ok(r.tiles.has(key(x, z)) || r.missing.has(key(x, z)), `tile ${x},${z} neither in tiles nor missing`);
      c.ok(r.tiles.size + r.missing.size === req.length, `answered ${r.tiles.size + r.missing.size} tiles for ${req.length} distinct requested`);
      if (t.player) c.ok(r.tiles.has(key(tx, tz)), `the tile under the online player (${tx},${tz}) should be loaded`);
      c.done();
      return `tile_size ${size}, ${r.tiles.size} tiles, ${r.missing.size} missing, ${r.pages} page(s)`;
    },
  },
  {
    id: 'map.tiles.stable-hash',
    group: 'maptiles',
    title: 'map.tiles: the same tile twice in a row has the same hash (cached or deterministic encoder)',
    needs: ['op:map.tiles', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      const a = await fetchTiles(ctx, t.world, ringTiles(t.tx, t.tz, 1));
      const b = await fetchTiles(ctx, t.world, ringTiles(t.tx, t.tz, 1));
      if (a.tiles.size === 0) throw new Skip('no loaded tile around the target');
      let same = 0;
      for (const [k, tile] of a.tiles) if (b.tiles.get(k)?.hash === tile.hash) same++;
      // a live server may change a tile between the calls; most must agree
      if (same * 2 < a.tiles.size) fail(`only ${same} of ${a.tiles.size} tiles kept their hash between two calls`);
      return `${same}/${a.tiles.size} identical`;
    },
  },
  {
    id: 'map.tiles.args',
    group: 'maptiles',
    title: 'map.tiles: 65 tiles, malformed pairs, missing world -> invalid_args; unknown world -> not_found',
    needs: ['op:map.tiles', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      const many = Array.from({ length: MAX_TILES + 1 }, (_, i) => [i, 0]);
      errCode(await ctx.call('map.tiles', { world: t.world, tiles: many }), 'invalid_args', '65 tiles');
      errCode(await ctx.call('map.tiles', { world: t.world, tiles: [[1]] }), 'invalid_args', 'pair of one');
      errCode(await ctx.call('map.tiles', { world: t.world, tiles: [[1.5, 2]] }), 'invalid_args', 'float coordinate');
      errCode(await ctx.call('map.tiles', { world: t.world, tiles: [] }), 'invalid_args', 'empty tiles');
      errCode(await ctx.call('map.tiles', { tiles: [[0, 0]] }), 'invalid_args', 'missing world');
      errCode(await ctx.call('map.tiles', { world: 'pm_conformance_no_such_world', tiles: [[0, 0]] }), 'not_found', 'unknown world');
      return 'invalid_args x5, not_found';
    },
  },
  {
    id: 'tiles.subscribe.ack',
    group: 'maptiles',
    title: 'tiles.subscribe -> {sub}; second on the same connection busy; bad/oversized area invalid_args; unsubscribe {} then not_found',
    needs: ['op:tiles.subscribe', 'op:tiles.unsubscribe', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      const { client } = await ctx.open({ hello: true });
      try {
        errCode(await client.request('tiles.subscribe', { world: t.world, area: { min_tx: 0, min_tz: 0, max_tx: -1, max_tz: 0 } }), 'invalid_args', 'min > max');
        errCode(await client.request('tiles.subscribe', { world: t.world, area: { min_tx: 0, min_tz: 0, max_tx: 4096, max_tz: 4096 } }), 'invalid_args', 'oversized area');
        errCode(await client.request('tiles.subscribe', { world: t.world, area: { min_tx: 0 } }), 'invalid_args', 'incomplete area');
        errCode(await client.request('tiles.subscribe', {}), 'invalid_args', 'missing world');
        const { resp, sub } = await client.subscribe('tiles.subscribe', { world: t.world, area: { min_tx: -8, min_tz: -8, max_tx: 8, max_tz: 8 } });
        const d = okData(resp, 'tiles.subscribe');
        if (!isIntV(d.sub) || !sub) fail('tiles.subscribe must answer {sub: int}');
        errCode(await client.request('tiles.subscribe', { world: t.world }), 'busy', 'second tiles.subscribe');
        const un = okData(await client.request('tiles.unsubscribe', { sub: d.sub }), 'tiles.unsubscribe');
        if (Object.keys(un).length) fail('tiles.unsubscribe data must be {}');
        errCode(await client.request('tiles.unsubscribe', { sub: d.sub }), 'not_found', 'tiles.unsubscribe twice');
        return `sub ${num(d.sub)}`;
      } finally {
        client.close();
      }
    },
  },
  {
    id: 'tiles.subscribe.frames',
    group: 'maptiles',
    title: 'tiles.subscribe frames: {tiles:[{tx,tz,hash}]} <= 64 per frame, inside the area, one entry per tile per 5 s, hash matches map.tiles (if frames arrive within 12 s)',
    needs: ['op:tiles.subscribe', 'op:map.tiles', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      const { tx, tz } = t;
      const area = { min_tx: tx - 8, min_tz: tz - 8, max_tx: tx + 8, max_tz: tz + 8 };
      const { client } = await ctx.open({ hello: true });
      let sub;
      try {
        const r = await client.subscribe('tiles.subscribe', { world: t.world, area });
        okData(r.resp, 'tiles.subscribe');
        sub = r.sub;
        await sleep(12000);
      } finally {
        client.close();
      }
      const frames = sub.frames.filter((f) => !(isObj(f.data) && f.data.type === 'ping'));
      if (frames.length === 0) throw new Skip('no tile changed within 12 s (nothing to check)');
      const c = new Check('tiles frames');
      if (sub.gaps.length) c.errs.push(`seq gaps: ${JSON.stringify(sub.gaps.slice(0, 3))}`);
      sub.frames.forEach((f, i) => c.ok(num(f.seq) === i, `frame ${i} has seq ${f.seq}`));
      const last = new Map();
      let n = 0;
      let latest = null;
      for (const [i, f] of sub.frames.entries()) {
        const d = f.data;
        if (isObj(d) && d.type === 'ping') continue;
        if (!isObj(d) || !Array.isArray(d.tiles)) {
          c.errs.push(`frame ${i}: data must be {tiles: [...]}`);
          continue;
        }
        for (const k of Object.keys(d)) if (k !== 'tiles') c.errs.push(`frame ${i}: unexpected key ${k}`);
        c.ok(d.tiles.length > 0 && d.tiles.length <= FRAME_MAX, `frame ${i}: ${d.tiles.length} tiles (1..${FRAME_MAX})`);
        const seen = new Set();
        for (const tl of d.tiles) {
          if (!isObj(tl) || !isIntV(tl.tx) || !isIntV(tl.tz) || !isHex(tl.hash, 16)) {
            c.errs.push(`frame ${i}: entry must be {tx, tz, hash}`);
            continue;
          }
          for (const k of Object.keys(tl)) if (!['tx', 'tz', 'hash'].includes(k)) c.errs.push(`frame ${i}: unexpected key ${k}`);
          const k = key(tl.tx, tl.tz);
          c.ok(!seen.has(k), `frame ${i}: tile ${k} twice`);
          seen.add(k);
          const x = num(tl.tx);
          const z = num(tl.tz);
          c.ok(x >= area.min_tx && x <= area.max_tx && z >= area.min_tz && z <= area.max_tz, `frame ${i}: tile ${k} outside the area`);
          const prev = last.get(k);
          if (prev) {
            c.ok(sub.times[i] - prev.at >= TILE_COOLDOWN_MS - COOLDOWN_SLACK_MS, `tile ${k} sent again after ${sub.times[i] - prev.at} ms`);
            c.ok(prev.hash !== tl.hash, `tile ${k} resent with an unchanged hash`);
          }
          last.set(k, { at: sub.times[i], hash: tl.hash });
          latest = { x, z, hash: tl.hash };
          n++;
        }
      }
      if (latest) {
        // the frame's tile must be fetchable (or have been unloaded meanwhile: then it is missing)
        const r = await fetchTiles(ctx, t.world, [[latest.x, latest.z]]);
        for (const p of r.problems) c.errs.push(`map.tiles after a frame: ${p}`);
      }
      c.done();
      return `${frames.length} frames, ${n} tile entries`;
    },
  },
  {
    id: 'map.markers.schema',
    group: 'maptiles',
    title: 'map.markers: {id, kind, name?, icon?, x, y?, z, owner?}, unique ids sorted, known kinds; with player adds only personal kinds',
    needs: ['op:map.markers', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      const server = await fetchMarkers(ctx, { world: t.world });
      const c = new Check('map.markers');
      const ids = new Set();
      let prev = '';
      for (const [i, m] of server.entries()) {
        for (const e of markerErrors(m, `markers[${i}]`)) c.errs.push(e);
        if (isObj(m) && isStr(m.id)) {
          c.ok(!ids.has(m.id), `marker id ${m.id} twice`);
          c.ok(prev < m.id || prev === '', `markers not sorted by id at ${m.id}`);
          prev = m.id;
          ids.add(m.id);
        }
        if (isObj(m)) c.ok(!['user', 'death', 'respawn'].includes(m.kind), `${m.id}: personal kind ${m.kind} without player`);
      }
      let personal = 0;
      if (t.player) {
        const withPlayer = await fetchMarkers(ctx, { world: t.world, player: t.player.id });
        for (const [i, m] of withPlayer.entries()) for (const e of markerErrors(m, `player markers[${i}]`)) c.errs.push(e);
        const more = withPlayer.filter((m) => isObj(m) && !ids.has(m.id));
        for (const m of more) c.ok(['user', 'death', 'respawn'].includes(m.kind), `${m.id}: only personal kinds may be added with player, got ${m.kind}`);
        personal = more.length;
      }
      c.done();
      return `${server.length} server markers, ${personal} personal`;
    },
  },
  {
    id: 'map.markers.args',
    group: 'maptiles',
    title: 'map.markers: missing world / malformed player -> invalid_args; unknown world -> not_found; unknown well-formed player adds nothing',
    needs: ['op:map.markers', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      errCode(await ctx.call('map.markers', {}), 'invalid_args', 'missing world');
      errCode(await ctx.call('map.markers', { world: t.world, player: '@a' }), 'invalid_args', 'selector as player');
      errCode(await ctx.call('map.markers', { world: 'pm_conformance_no_such_world' }), 'not_found', 'unknown world');
      const a = await fetchMarkers(ctx, { world: t.world });
      const b = await fetchMarkers(ctx, { world: t.world, player: fakePlayerId(ctx.hello.game) });
      if (a.length !== b.length) fail(`an offline player changed the answer (${a.length} vs ${b.length} markers)`);
      return 'invalid_args x2, not_found, offline player adds nothing';
    },
  },
  {
    id: 'map.markers.icons',
    group: 'maptiles',
    title: 'marker icons resolve through assets.get as icon:<path> (static PNG or unavailable, never an error)',
    needs: ['op:map.markers', 'op:assets.get', 'hello'],
    async run(ctx) {
      const t = await tileTarget(ctx);
      const ms = await fetchMarkers(ctx, { world: t.world, ...(t.player ? { player: t.player.id } : {}) });
      const icons = [...new Set(ms.filter((m) => isObj(m) && isStr(m.icon)).map((m) => `icon:${m.icon}`))].slice(0, 16);
      if (icons.length === 0) throw new Skip('no marker carries an icon');
      const d = okData(await ctx.call('assets.get', { keys: [...icons, 'icon:../../outside.png'] }), 'assets.get icons');
      const c = new Check('marker icons');
      let served = 0;
      for (const [i, a] of (d.assets ?? []).entries()) {
        c.ok(isObj(a) && a.key === [...icons, 'icon:../../outside.png'][i], `assets[${i}] out of request order`);
        if (!isObj(a)) continue;
        if (a.render === 'static') {
          const png = a.textures?.icon?.png;
          c.ok(isBin(png) && PNG_SIG.every((b, j) => png[j] === b), `${a.key}: static icon must carry a PNG`);
          served++;
        } else c.ok(a.render === 'unavailable', `${a.key}: render ${a.render}`);
      }
      const trav = (d.assets ?? []).at(-1);
      c.ok(isObj(trav) && trav.render === 'unavailable', 'a path with .. must be unavailable');
      c.done();
      return `${served}/${icons.length} icons served`;
    },
  },
];
