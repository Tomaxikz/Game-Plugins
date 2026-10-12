// assets.manifest / assets.get (items-and-assets.md sections 4-6).

import { Check, fail, Skip, isObj, isStr, isIntV, isHex, isBin } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { manifestHash, assetHash } from '../lib/canonical.mjs';
import { okData, errCode } from './helpers.mjs';

const RENDERS = ['static', 'model', 'special', 'unavailable'];
const REASONS = ['client_only_renderer', 'vanilla_assets_panel_side', 'no_art_on_server', 'model_unresolved', 'not_found', 'too_large'];
const PNG_SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function bytewiseCompare(a, b) {
  return Buffer.compare(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

/** Full listing via paging with `limit`. Returns { entries, manifest_hash }. */
async function listAll(ctx, limit = 2) {
  let cursor;
  const entries = [];
  let mh = null;
  for (let page = 0; page < 500; page++) {
    const args = { limit };
    if (cursor !== undefined) args.cursor = cursor;
    const d = okData(await ctx.call('assets.manifest', args), `assets.manifest page ${page}`);
    if (!isStr(d.manifest_hash) || !isHex(d.manifest_hash, 16)) fail('manifest_hash must be 16 hex chars');
    if (mh !== null && mh !== d.manifest_hash) fail('manifest_hash changes between pages of one listing');
    mh = d.manifest_hash;
    if (!Array.isArray(d.entries)) fail('entries must be an array');
    if (d.entries.length > limit) fail(`page has ${d.entries.length} entries, limit ${limit}`);
    entries.push(...d.entries);
    if (d.next === undefined || d.next === null) return { entries, manifest_hash: mh };
    cursor = d.next;
  }
  fail('listing did not terminate');
  return null;
}

export const tests = [
  {
    id: 'assets.manifest.schema',
    group: 'assets',
    title: 'assets.manifest entries: key, 16-hex hash, render vocabulary, sorted bytewise, unique keys',
    needs: ['op:assets.manifest'],
    async run(ctx) {
      const d = okData(await ctx.call('assets.manifest', {}), 'assets.manifest');
      const c = new Check('assets.manifest');
      c.ok(Array.isArray(d.entries), 'entries must be an array');
      c.ok(isHex(d.manifest_hash, 16), 'manifest_hash must be 16 hex chars');
      const entries = d.entries ?? [];
      for (const [i, e] of entries.entries()) {
        c.ok(isObj(e) && isStr(e.key) && e.key !== '', `entries[${i}].key must be a string`);
        c.ok(isObj(e) && isHex(e.hash, 16), `entries[${i}].hash must be 16 hex chars`);
        c.ok(isObj(e) && RENDERS.includes(e.render), `entries[${i}].render ${e?.render} not in vocabulary`);
        if (ctx.game === 'minecraft_java' && isObj(e) && isStr(e.key)) {
          // Default vanilla looks resolve panel-side; a vanilla id with a pack model (`@<model>`) is server art.
          c.ok(!(e.key.startsWith('item:minecraft:') && !e.key.includes('@')), `vanilla key ${e.key} must not be listed`);
        }
      }
      const keys = entries.map((e) => e.key);
      for (let i = 1; i < keys.length; i++) {
        c.ok(bytewiseCompare(keys[i - 1], keys[i]) < 0, `keys not strictly sorted at ${keys[i]}`);
      }
      if (d.next !== undefined && d.next !== null) c.ok(isStr(d.next), 'next must be a string cursor');
      c.done();
      return `${entries.length} entries`;
    },
  },
  {
    id: 'assets.manifest.paging-hash',
    group: 'assets',
    title: 'paged listing equals the full listing; manifest_hash recomputes over the full sorted [key,hash] list',
    needs: ['op:assets.manifest'],
    async run(ctx) {
      const full = okData(await ctx.call('assets.manifest', {}), 'assets.manifest');
      const paged = await listAll(ctx, 2);
      const pairs = (list) => JSON.stringify(list.map((e) => [e.key, e.hash]));
      if (!full.next && pairs(paged.entries) !== pairs(full.entries)) fail('paged listing differs from the full listing');
      const recomputed = manifestHash(paged.entries);
      if (recomputed !== paged.manifest_hash) fail(`manifest_hash ${paged.manifest_hash} != recomputed ${recomputed}`);
      return `${paged.entries.length} entries over pages, hash ${recomputed}`;
    },
  },
  {
    id: 'assets.manifest.since-hash',
    group: 'assets',
    title: 'since_hash equal to the current manifest_hash returns unchanged with no entries; a wrong hash returns a listing',
    needs: ['op:assets.manifest'],
    async run(ctx) {
      const a = okData(await ctx.call('assets.manifest', {}), 'assets.manifest');
      const b = okData(await ctx.call('assets.manifest', {}), 'assets.manifest again');
      if (a.manifest_hash !== b.manifest_hash) fail('manifest_hash not stable across two calls');
      const same = okData(await ctx.call('assets.manifest', { since_hash: a.manifest_hash }), 'since_hash');
      if (same.unchanged !== true) fail('unchanged must be true for matching since_hash');
      if (same.manifest_hash !== a.manifest_hash) fail('unchanged reply must carry manifest_hash');
      if (Array.isArray(same.entries) && same.entries.length) fail('unchanged reply must carry no entries');
      const other = okData(await ctx.call('assets.manifest', { since_hash: '0000000000000001' }), 'since_hash wrong');
      if (other.unchanged === true) fail('unchanged true for a wrong since_hash');
      return 'stable; since_hash shortcut works';
    },
  },
  {
    id: 'assets.manifest.empty-namespaces',
    group: 'assets',
    title: 'namespaces filter: an unknown namespace yields no entries',
    needs: ['op:assets.manifest'],
    async run(ctx) {
      const d = okData(await ctx.call('assets.manifest', { namespaces: ['pm_no_such_ns'] }), 'namespaces filter');
      if ((d.entries ?? []).length) fail(`${d.entries.length} entries for an unknown namespace`);
      return 'empty';
    },
  },
  {
    id: 'assets.get.schema',
    group: 'assets',
    title: 'assets.get entries: key/hash/render, static icon PNG (signature, IHDR size), hash recomputes from texture sha',
    needs: ['op:assets.get'],
    async run(ctx) {
      const man = okData(await ctx.call('assets.manifest', {}), 'assets.manifest');
      const keys = (man.entries ?? []).slice(0, 4).map((e) => e.key);
      if (keys.length === 0) throw new Skip('manifest is empty (nothing to fetch)');
      const d = okData(await ctx.call('assets.get', { keys }), 'assets.get');
      const c = new Check('assets.get');
      c.ok(Array.isArray(d.assets) && d.assets.length === keys.length, `expected ${keys.length} assets, got ${d.assets?.length}`);
      let verified = 0;
      for (const [i, a] of (d.assets ?? []).entries()) {
        const p = `assets[${i}]`;
        c.ok(isObj(a) && a.key === keys[i], `${p} must be ${keys[i]} (request order)`);
        c.ok(isObj(a) && isHex(a.hash, 16), `${p}.hash must be 16 hex chars`);
        c.ok(isObj(a) && RENDERS.includes(a.render), `${p}.render ${a?.render} invalid`);
        if (!isObj(a)) continue;
        if (a.render === 'unavailable') {
          c.ok(REASONS.includes(a.unavailable_reason), `${p}.unavailable_reason ${a.unavailable_reason} invalid`);
          continue;
        }
        if (a.render === 'static') {
          const icon = a.textures?.icon;
          c.ok(isObj(icon) && isBin(icon.png), `${p}.textures.icon.png must be bin`);
          if (isObj(icon) && isBin(icon.png)) {
            const sig = PNG_SIG.every((b, j) => icon.png[j] === b);
            c.ok(sig, `${p} icon is not a PNG (signature)`);
            if (sig && icon.frames === undefined) {
              const w = (icon.png[16] << 24) | (icon.png[17] << 16) | (icon.png[18] << 8) | icon.png[19];
              const h = (icon.png[20] << 24) | (icon.png[21] << 16) | (icon.png[22] << 8) | icon.png[23];
              c.ok(w === num(icon.width) && h === num(icon.height), `${p} IHDR ${w}x${h} != declared ${icon.width}x${icon.height}`);
            }
          }
        }
        const recomputed = assetHash(a);
        if (recomputed !== null) {
          verified += 1;
          c.ok(recomputed === a.hash, `${p}.hash ${a.hash} != recomputed ${recomputed}`);
        }
      }
      c.done();
      return `${d.assets.length} assets, ${verified} hashes recomputed`;
    },
  },
  {
    id: 'assets.get.order-unknown',
    group: 'assets',
    title: 'assets.get keeps request order; unknown keys -> unavailable/not_found and the call still succeeds',
    needs: ['op:assets.get'],
    async run(ctx) {
      const man = okData(await ctx.call('assets.manifest', {}), 'assets.manifest');
      const first = man.entries?.[0]?.key;
      const keys = ['item:pm_conformance:no_such_item', ...(first ? [first] : []), 'item:pm_conformance:also_missing'];
      const d = okData(await ctx.call('assets.get', { keys }), 'assets.get');
      const got = d.assets.map((a) => a.key);
      if (JSON.stringify(got) !== JSON.stringify(keys)) fail(`order ${JSON.stringify(got)} != request ${JSON.stringify(keys)}`);
      for (const a of d.assets.filter((x) => x.key.startsWith('item:pm_conformance:'))) {
        if (a.render !== 'unavailable' || a.unavailable_reason !== 'not_found') fail(`${a.key} should be unavailable/not_found, got ${a.render}/${a.unavailable_reason}`);
        if (!isHex(a.hash, 16)) fail(`${a.key} hash missing`);
      }
      return 'order kept; unknown -> not_found';
    },
  },
  {
    id: 'assets.get.too-many-keys',
    group: 'assets',
    title: 'assets.get with 65 keys is invalid_args (checked in args.assets.get-max-keys)',
    needs: ['op:assets.get'],
    async run(ctx) {
      const keys = Array.from({ length: 65 }, (_, i) => `item:pm_conformance:k${i}`);
      errCode(await ctx.call('assets.get', { keys }), 'invalid_args', '65 keys');
      return 'invalid_args';
    },
  },
  {
    id: 'assets.get.cursor',
    group: 'assets',
    title: 'assets.get: when more is true, the cursor continues the same request',
    needs: ['op:assets.get'],
    async run(ctx) {
      const man = okData(await ctx.call('assets.manifest', {}), 'assets.manifest');
      const keys = (man.entries ?? []).map((e) => e.key).slice(0, 64);
      if (keys.length === 0) throw new Skip('manifest is empty');
      const d = okData(await ctx.call('assets.get', { keys }), 'assets.get');
      if (d.more !== true) throw new Skip('response was not split (more is not true)');
      const rest = okData(await ctx.call('assets.get', { keys, cursor: d.cursor }), 'assets.get cursor');
      if (!Array.isArray(rest.assets) || rest.assets.length === 0) fail('cursor returned no assets');
      return `continued with ${rest.assets.length} assets`;
    },
  },
];

export { isIntV };
