// biomes.list and blocks.list (operations.md 6d, 6e; games/minecraft.md 6b, 6c; NOTES.md D31 naming).

import { Check, fail, Skip, isStr, isIntV, isHex } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { hash16 } from '../lib/canonical.mjs';
import { biomeListErrors, blocksListErrors, blocksHash, BLOCK_FLAGS } from '../lib/registry.mjs';
import { stateId, VOLUME_AIR_IDS } from '../lib/volume.mjs';
import { okData, errCode, callWait } from './helpers.mjs';
import { resolveTarget, baseArgs, ring } from './chunks.mjs';

const MAX_PAGES = 500;

/** A target for cross-checks against the map ops, or null when no world is known. */
async function targetOrNull(ctx) {
  try {
    return await resolveTarget(ctx);
  } catch (err) {
    if (err instanceof Skip) return null;
    throw err;
  }
}

/** Reads every blocks.list page. Restarts once when the hash changes between pages (registry changed, 6e). */
async function blocksAll(ctx) {
  for (let restart = 0; restart < 2; restart++) {
    const blocks = [];
    const pageSizes = [];
    let cursor;
    let hash = null;
    let changed = false;
    for (let page = 0; page < MAX_PAGES; page++) {
      const d = okData(await callWait(ctx, 'blocks.list', cursor === undefined ? {} : { cursor }), `blocks.list page ${page}`);
      if (!isHex(d.hash, 16)) fail(`page ${page}: hash must be 16 lowercase hex chars, got ${JSON.stringify(d.hash)}`);
      if (d.unchanged !== undefined) fail(`page ${page}: unchanged is only for a matching since_hash`);
      if (!Array.isArray(d.blocks)) fail(`page ${page}: blocks must be an array`);
      if (d.blocks.length === 0) fail(`page ${page}: blocks is empty (a page holds at least one whole entry)`);
      if (hash !== null && d.hash !== hash) {
        changed = true;
        break;
      }
      hash = d.hash;
      blocks.push(...d.blocks);
      pageSizes.push(d.blocks.length);
      if (d.next === undefined) return { hash, blocks, pageSizes };
      if (!isStr(d.next) || d.next === '') fail(`page ${page}: next must be a non-empty string or omitted on the last page, got ${JSON.stringify(d.next)}`);
      cursor = d.next;
    }
    if (!changed) fail(`blocks.list did not end after ${MAX_PAGES} pages`);
  }
  fail('the blocks.list hash kept changing between pages (restarted once)');
  return null;
}

export const tests = [
  {
    id: 'biomes.list.schema',
    group: 'registry',
    title: 'biomes.list: sorted unique ids, temperature/downfall numbers, 24-bit water_color, colour overrides int|null, modifier enum, minecraft:plains present; stable; covers the biomes the map ops report',
    needs: ['op:biomes.list', 'hello'],
    async run(ctx) {
      const d = okData(await ctx.call('biomes.list', { pm_conformance_unknown_arg: 1 }), 'biomes.list');
      const c = new Check('biomes.list');
      for (const m of biomeListErrors(d.biomes)) c.errs.push(m);
      c.done();
      const ids = new Set(d.biomes.map((b) => b.id));
      if (ctx.game === 'minecraft_java') {
        if (!ids.has('minecraft:plains')) fail('minecraft:plains is missing from the registry (every Minecraft server has it)');
        if (d.biomes.length < 1) fail('empty registry');
      }
      const again = okData(await ctx.call('biomes.list', {}), 'biomes.list again');
      if (hash16(again.biomes) !== hash16(d.biomes)) fail('the registry is frozen at startup (T9) but two answers differ');
      // Ids the map ops report must be registry ids.
      let seenIds = 0;
      const t = await targetOrNull(ctx);
      if (t) {
        const req = ring(t.cx, t.cz, 2);
        const used = new Set();
        if (ctx.has('map.chunks')) {
          const m = okData(await ctx.call('map.chunks', baseArgs(t, { chunks: req })), 'map.chunks');
          for (const s of m.chunks ?? []) for (const b of s.biomes ?? []) used.add(b);
        }
        if (ctx.has('map.volume')) {
          const m = okData(await ctx.call('map.volume', baseArgs(t, { chunks: req })), 'map.volume');
          for (const v of m.chunks ?? []) for (const b of v.biome_ids ?? []) used.add(b);
        }
        for (const b of used) if (!ids.has(b)) c.errs.push(`biome ${b} is reported by the map ops but is not in biomes.list`);
        seenIds = used.size;
        c.done();
      }
      return `${d.biomes.length} biomes${seenIds ? `, ${seenIds} map biome ids all listed` : ''}`;
    },
  },
  {
    id: 'blocks.list.schema',
    group: 'registry',
    title: 'blocks.list: every page: sorted unique ids, map_color int|null, flags/light ranges, emits_light agrees with light, states sorted by properties and differing from the default; air blocks carry the air flag',
    needs: ['op:blocks.list', 'hello'],
    async run(ctx) {
      const all = await blocksAll(ctx);
      const { errors, unknownBits } = blocksListErrors(all.blocks);
      const c = new Check('blocks.list');
      for (const m of errors) c.errs.push(m);
      const byId = new Map(all.blocks.map((b) => [b.id, b]));
      if (ctx.game === 'minecraft_java') {
        for (const id of VOLUME_AIR_IDS) {
          const b = byId.get(id);
          c.ok(b !== undefined, `${id} is missing (air blocks are in the registry list)`);
          if (b && isIntV(b.flags)) c.ok((num(b.flags) & BLOCK_FLAGS.air) !== 0, `${id} must carry the air flag (1), flags ${num(b.flags)}`);
        }
        const stone = byId.get('minecraft:stone');
        c.ok(stone !== undefined, 'minecraft:stone is missing');
        if (stone && isIntV(stone.flags)) {
          const f = num(stone.flags);
          c.ok((f & BLOCK_FLAGS.air) === 0 && (f & BLOCK_FLAGS.full_cube) !== 0 && (f & BLOCK_FLAGS.can_occlude) !== 0, `minecraft:stone must be can_occlude + full_cube and not air, flags ${f}`);
        }
        const water = byId.get('minecraft:water');
        c.ok(water !== undefined, 'minecraft:water is missing');
        if (water && isIntV(water.flags)) c.ok((num(water.flags) & BLOCK_FLAGS.liquid) !== 0, `minecraft:water must carry the liquid flag (2), flags ${num(water.flags)}`);
      }
      c.done();
      const states = all.blocks.reduce((n, b) => n + (b.states?.length ?? 0), 0);
      return `${all.blocks.length} blocks, ${states} listed states, ${all.pageSizes.length} page(s)${unknownBits ? `, ${unknownBits} entries use flag bits above the documented ten (allowed)` : ''}`;
    },
  },
  {
    id: 'blocks.list.hash',
    group: 'registry',
    title: 'blocks.list: the same hash on every page; it equals the first 16 hex of SHA-256 over canonical MessagePack of all pages joined (6e); stable across calls',
    needs: ['op:blocks.list', 'hello'],
    async run(ctx) {
      const a = await blocksAll(ctx);
      const re = blocksHash(a.blocks);
      if (re !== a.hash) fail(`hash ${a.hash} != recomputed ${re} over ${a.blocks.length} blocks in ${a.pageSizes.length} page(s)`);
      const b = await blocksAll(ctx);
      if (b.hash !== a.hash) fail(`hash changed between two complete reads: ${a.hash} then ${b.hash} (it only changes when the registry does)`);
      return `hash ${re} over ${a.blocks.length} blocks, ${a.pageSizes.length} page(s)`;
    },
  },
  {
    id: 'blocks.list.since-hash',
    group: 'registry',
    title: 'blocks.list since_hash equal to the current hash returns {hash, unchanged: true} with no blocks; another value returns the listing; since_hash is ignored when a cursor is given',
    needs: ['op:blocks.list', 'hello'],
    async run(ctx) {
      const a = await blocksAll(ctx);
      const same = okData(await callWait(ctx, 'blocks.list', { since_hash: a.hash }), 'blocks.list since_hash');
      if (same.unchanged !== true) fail('unchanged must be true for a matching since_hash');
      if (same.hash !== a.hash) fail('the unchanged answer must carry the hash');
      if ('blocks' in same || 'next' in same) fail('the unchanged answer carries no blocks and no next');
      const other = okData(await callWait(ctx, 'blocks.list', { since_hash: '0000000000000001' }), 'blocks.list other since_hash');
      if (other.unchanged === true) fail('unchanged is true for a since_hash that does not match');
      if (!Array.isArray(other.blocks)) fail('a since_hash that does not match must return the listing');
      let note = 'single page: the cursor rule was not exercised';
      const first = okData(await callWait(ctx, 'blocks.list', {}), 'blocks.list first page');
      if (isStr(first.next)) {
        const second = okData(await callWait(ctx, 'blocks.list', { cursor: first.next, since_hash: a.hash }), 'blocks.list cursor + since_hash');
        if (second.unchanged === true || !Array.isArray(second.blocks)) fail('with a cursor, since_hash is ignored: the next page must come back');
        note = 'since_hash ignored with a cursor';
      }
      return `unchanged shortcut works; ${note}`;
    },
  },
  {
    id: 'blocks.list.paging',
    group: 'registry',
    title: 'blocks.list paging: each page holds whole entries, next is an opaque cursor that continues the listing; pages joined give each id once, in order',
    needs: ['op:blocks.list', 'hello'],
    async run(ctx) {
      const all = await blocksAll(ctx);
      const ids = all.blocks.map((b) => b.id);
      if (new Set(ids).size !== ids.length) fail('an id appears on two pages');
      if (all.pageSizes.length === 1) return `single page (${all.blocks.length} blocks fitted one frame): cursor paging not exercised`;
      // A second read through the cursor chain must give the same entries (cursors are repeatable).
      const b = await blocksAll(ctx);
      if (hash16(b.blocks) !== hash16(all.blocks)) fail('two reads through the cursor chain returned different entries');
      return `${all.pageSizes.length} pages of ${all.pageSizes.join('/')} blocks`;
    },
  },
  {
    id: 'blocks.list.args',
    group: 'registry',
    title: 'blocks.list: since_hash or cursor that is not a string, and a cursor this bridge never issued, are invalid_args; unknown args are ignored',
    needs: ['op:blocks.list', 'hello'],
    async run(ctx) {
      okData(await callWait(ctx, 'blocks.list', { pm_conformance_unknown_arg: 1 }), 'blocks.list with an unknown arg');
      errCode(await ctx.call('blocks.list', { since_hash: 5 }), 'invalid_args', 'since_hash is an int');
      errCode(await ctx.call('blocks.list', { since_hash: ['efeed82267412635'] }), 'invalid_args', 'since_hash is an array');
      errCode(await ctx.call('blocks.list', { cursor: 5 }), 'invalid_args', 'cursor is an int');
      errCode(await ctx.call('blocks.list', { cursor: { next: 1 } }), 'invalid_args', 'cursor is a map');
      errCode(await ctx.call('blocks.list', { cursor: 'pm-conformance-no-such-cursor' }), 'invalid_args', 'cursor this bridge never issued');
      return 'invalid_args as specified';
    },
  },
  {
    id: 'blocks.list.matches-map',
    group: 'registry',
    title: 'every block id the map ops report (map.chunks palette, map.volume palette ids) is in blocks.list',
    needs: ['op:blocks.list', 'hello'],
    async run(ctx) {
      if (!ctx.has('map.chunks') && !ctx.has('map.volume')) throw new Skip('neither map.chunks nor map.volume advertised');
      const t = await targetOrNull(ctx);
      if (!t) throw new Skip('no world known');
      const all = await blocksAll(ctx);
      const ids = new Set(all.blocks.map((b) => b.id));
      const used = new Set();
      const req = ring(t.cx, t.cz, 2);
      if (ctx.has('map.chunks')) {
        const m = okData(await ctx.call('map.chunks', baseArgs(t, { chunks: req })), 'map.chunks');
        for (const s of m.chunks ?? []) for (const id of s.palette ?? []) used.add(id);
      }
      if (ctx.has('map.volume')) {
        const m = okData(await ctx.call('map.volume', baseArgs(t, { chunks: req })), 'map.volume');
        for (const v of m.chunks ?? []) for (const s of v.palette ?? []) if (isStr(s)) used.add(stateId(s));
      }
      if (used.size === 0) throw new Skip('no loaded chunk around the target');
      const missing = [...used].filter((id) => !ids.has(id));
      if (missing.length) fail(`${missing.length} block ids used by the map ops are not in blocks.list: ${missing.slice(0, 6).join(', ')}`);
      return `${used.size} map block ids, all in the ${ids.size}-block registry`;
    },
  },
];
