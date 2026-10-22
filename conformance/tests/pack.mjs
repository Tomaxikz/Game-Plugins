// pack.manifest / pack.get (operations.md 10a, 10b; items-and-assets.md 11).

import { Check, fail, Skip, isObj, isStr, isBin, isHex } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { PACK_MAX_PATHS, PACK_TOO_LARGE, fileHash, listingHash, manifestFileErrors, packPathError } from '../lib/pack.mjs';
import { okData, errCode, callWait } from './helpers.mjs';

const MAX_PAGES = 500;
const MAX_FRAME = 4194304;
const GET_ROUNDS = 40;
const MISSING_PATH = 'assets/pm_conformance/textures/block/no_such_file.png';

/** Reads every pack.manifest page; restarts once when the hash changes between pages (the pack changed). */
async function manifestAll(ctx) {
  for (let restart = 0; restart < 2; restart++) {
    const files = [];
    let cursor;
    let hash = null;
    let pages = 0;
    let changed = false;
    for (let page = 0; page < MAX_PAGES; page++) {
      const d = okData(await callWait(ctx, 'pack.manifest', cursor === undefined ? {} : { cursor }), `pack.manifest page ${page}`);
      if (!isHex(d.hash, 16)) fail(`page ${page}: hash must be 16 lowercase hex chars, got ${JSON.stringify(d.hash)}`);
      if (!Array.isArray(d.files)) fail(`page ${page}: files must be an array`);
      if (hash !== null && d.hash !== hash) {
        changed = true;
        break;
      }
      hash = d.hash;
      pages++;
      files.push(...d.files);
      if (d.next === undefined || d.next === null) return { hash, files, pages };
      if (!isStr(d.next) || d.next === '') fail(`page ${page}: next must be a non-empty string, or omitted/null on the last page, got ${JSON.stringify(d.next)}`);
      if (d.files.length === 0) fail(`page ${page}: has a next cursor but no files (no progress)`);
      cursor = d.next;
    }
    if (!changed) fail(`pack.manifest did not end after ${MAX_PAGES} pages`);
  }
  fail('the pack.manifest hash kept changing between pages (restarted once)');
  return null;
}

/** One pack.get; returns the data with the envelope shape checked. */
async function getPaths(ctx, paths) {
  const d = okData(await callWait(ctx, 'pack.get', { paths }), 'pack.get');
  if (!Array.isArray(d.files) || !Array.isArray(d.missing)) fail('data must be {files: [{path, data}], missing: [path]}');
  for (const k of ['deferred', 'too_large']) if (d[k] !== undefined && !(Array.isArray(d[k]) && d[k].every(isStr))) fail(`${k} must be a string[] when present`);
  for (const k of Object.keys(d)) if (!['files', 'missing', 'deferred', 'too_large'].includes(k)) fail(`unexpected key ${k}`);
  for (const f of d.files) {
    if (!isObj(f) || !isStr(f.path) || !isBin(f.data)) fail('files entries must be {path: string, data: bin}');
    for (const k of Object.keys(f)) if (k !== 'path' && k !== 'data') fail(`files entry has unexpected key ${k}`);
  }
  if (!d.missing.every(isStr)) fail('missing must be a string[]');
  return d;
}

/** Accounting + order rules of one pack.get answer; returns problems. */
function accountingErrors(req, d, sizeOf) {
  const e = [];
  const want = [...new Set(req)];
  const wantSet = new Set(want);
  const lists = { files: d.files.map((f) => f.path), missing: d.missing, deferred: d.deferred ?? [], too_large: d.too_large ?? [] };
  const where = new Map();
  for (const [name, list] of Object.entries(lists)) {
    for (const p of list) {
      if (!wantSet.has(p)) e.push(`${name} has ${p}, which was not requested`);
      where.set(p, [...(where.get(p) ?? []), name]);
    }
  }
  for (const p of want) {
    const w = where.get(p) ?? [];
    if (w.length !== 1) e.push(`requested path ${p} is in ${w.length ? w.join(' + ') : 'none of files, missing, deferred, too_large'} (must be in exactly one)`);
  }
  // Request order within each list, and "whole files in request order until the next one does not fit": every
  // deferred path comes after every served path in the request.
  const pos = new Map(want.map((p, i) => [p, i]));
  for (const [name, list] of Object.entries(lists)) {
    for (let i = 1; i < list.length; i++) {
      if ((pos.get(list[i - 1]) ?? -1) >= (pos.get(list[i]) ?? -1)) {
        e.push(`${name} is not in request order (${list[i - 1]} before ${list[i]})`);
        break;
      }
    }
  }
  if (lists.deferred.length && lists.files.length) {
    const lastFile = Math.max(...lists.files.map((p) => pos.get(p) ?? -1));
    const firstDeferred = Math.min(...lists.deferred.map((p) => pos.get(p) ?? Infinity));
    if (firstDeferred < lastFile) e.push(`deferred ${want[firstDeferred]} precedes the served ${want[lastFile]}: files are filled in request order until one does not fit`);
  }
  for (const p of lists.too_large) {
    const size = sizeOf.get(p);
    if (size !== undefined && size <= PACK_TOO_LARGE) e.push(`${p} (${size} bytes) is reported too_large but is not larger than 3 MiB`);
  }
  for (const f of d.files) {
    const size = sizeOf.get(f.path);
    if (size !== undefined && size >= MAX_FRAME) e.push(`${f.path} (${size} bytes) cannot fit one frame but was served`);
  }
  return e;
}

export const tests = [
  {
    id: 'pack.manifest.schema',
    group: 'pack',
    title: 'pack.manifest: files {path, hash, size}, every path matches the 10a path rule, sorted bytewise and unique, 16-hex hashes, int sizes',
    needs: ['op:pack.manifest', 'op:pack.get', 'hello'],
    timeout: 40000,
    async run(ctx) {
      const m = await manifestAll(ctx);
      const c = new Check('pack.manifest');
      for (const x of manifestFileErrors(m.files)) c.errs.push(x);
      c.done();
      const bytes = m.files.reduce((n, f) => n + num(f.size), 0);
      return `${m.files.length} files (${bytes} bytes) in ${m.pages} page(s)`;
    },
  },
  {
    id: 'pack.manifest.hash',
    group: 'pack',
    title: 'pack.manifest: one hash on every page; it equals the first 16 hex of SHA-256 over canonical MessagePack of the sorted [[path, hash], ...] list; stable across calls',
    needs: ['op:pack.manifest', 'op:pack.get', 'hello'],
    timeout: 40000,
    async run(ctx) {
      const a = await manifestAll(ctx);
      const re = listingHash(a.files);
      if (re !== a.hash) fail(`hash ${a.hash} != recomputed ${re} over ${a.files.length} files`);
      const b = await manifestAll(ctx);
      if (b.hash !== a.hash) fail(`hash changed between two reads: ${a.hash} then ${b.hash} (nothing changed in between)`);
      return `hash ${re} over ${a.files.length} files, ${a.pages} page(s)${a.files.length === 0 ? ' (empty effective pack)' : ''}${a.pages === 1 ? '' : ', paging verified'}`;
    },
  },
  {
    id: 'pack.manifest.args',
    group: 'pack',
    title: 'pack.manifest: a cursor that is not a string, or that this bridge never issued, is invalid_args',
    needs: ['op:pack.manifest', 'op:pack.get', 'hello'],
    timeout: 40000,
    async run(ctx) {
      await callWait(ctx, 'pack.manifest', {});
      errCode(await ctx.call('pack.manifest', { cursor: 5 }), 'invalid_args', 'cursor is an int');
      errCode(await ctx.call('pack.manifest', { cursor: 'pm-conformance-no-such-cursor' }), 'invalid_args', 'cursor this bridge never issued');
      return 'invalid_args as specified';
    },
  },
  {
    id: 'pack.get.schema',
    group: 'pack',
    title: 'pack.get: the bytes of listed files hash to the manifest hash and have the manifest size; entries are {path, data} in request order',
    needs: ['op:pack.manifest', 'op:pack.get', 'hello'],
    timeout: 40000,
    async run(ctx) {
      let errs = [];
      let served = 0;
      for (let attempt = 0; attempt < 3; attempt++) {
        errs = [];
        const m = await manifestAll(ctx);
        if (m.files.length === 0) throw new Skip('the effective pack is empty (nothing to fetch)');
        const small = m.files.filter((f) => num(f.size) <= 512 * 1024).slice(0, 8);
        if (small.length === 0) throw new Skip('every listed file is larger than 512 KiB');
        const paths = small.map((f) => f.path);
        const d = await getPaths(ctx, paths);
        const byPath = new Map(m.files.map((f) => [f.path, f]));
        served = d.files.length;
        if (d.files.length === 0) errs.push('no file served although the first requested file fits on its own');
        for (const f of d.files) {
          const entry = byPath.get(f.path);
          if (!entry) continue;
          if (f.data.length !== num(entry.size)) errs.push(`${f.path}: ${f.data.length} bytes served, manifest size ${num(entry.size)}`);
          const h = fileHash(f.data);
          if (h !== entry.hash) errs.push(`${f.path}: SHA-256 of the served bytes is ${h}, the manifest hash is ${entry.hash}`);
        }
        const again = await manifestAll(ctx);
        if (again.hash !== m.hash) continue; // the pack changed between the calls: try again
        if (errs.length === 0) return `${served} file(s) served, hash and size match the manifest`;
        break;
      }
      fail(errs.slice(0, 6).join('; ') || 'the pack kept changing');
      return '';
    },
  },
  {
    id: 'pack.get.accounting',
    group: 'pack',
    title: 'pack.get: every requested path (duplicates once) is in exactly one of files, missing, deferred, too_large; deferred paths can be requested again; too_large only above 3 MiB; unknown path is missing',
    needs: ['op:pack.manifest', 'op:pack.get', 'hello'],
    timeout: 60000,
    async run(ctx) {
      const m = await manifestAll(ctx);
      const sizeOf = new Map(m.files.map((f) => [f.path, num(f.size)]));
      const hashOf = new Map(m.files.map((f) => [f.path, f.hash]));
      const listed = m.files.slice(0, PACK_MAX_PATHS - 3).map((f) => f.path);
      // listed paths, one unknown path, a duplicate of the first path (or of the unknown one on an empty pack)
      const req = [...listed, MISSING_PATH, listed[0] ?? MISSING_PATH];
      let pending = [...req];
      const served = new Set();
      const tooLarge = new Set();
      const missing = new Set();
      let deferredRounds = 0;
      for (let round = 0; round < GET_ROUNDS && pending.length; round++) {
        const d = await getPaths(ctx, pending);
        const errs = accountingErrors(pending, d, sizeOf);
        if (errs.length) fail(`round ${round + 1}: ${errs.slice(0, 5).join('; ')}`);
        const firstPath = pending[0];
        if (sizeOf.has(firstPath) && sizeOf.get(firstPath) <= PACK_TOO_LARGE && d.files.length === 0) fail(`round ${round + 1}: the first requested file (${firstPath}, ${sizeOf.get(firstPath)} bytes) fits on its own but nothing was served`);
        for (const f of d.files) {
          served.add(f.path);
          const h = hashOf.get(f.path);
          if (h !== undefined && fileHash(f.data) !== h) fail(`${f.path}: served bytes hash ${fileHash(f.data)}, manifest ${h}`);
        }
        for (const p of d.too_large ?? []) tooLarge.add(p);
        for (const p of d.missing) missing.add(p);
        pending = d.deferred ?? [];
        if (pending.length) deferredRounds++;
        if (pending.length && d.files.length === 0 && (d.too_large ?? []).length === 0 && d.missing.length === 0) fail(`round ${round + 1}: only deferred paths came back: no progress`);
      }
      if (pending.length) fail(`${pending.length} paths still deferred after ${GET_ROUNDS} rounds`);
      if (!missing.has(MISSING_PATH)) fail(`${MISSING_PATH} is not in the pack and must be in missing`);
      const lost = listed.filter((p) => !served.has(p) && !tooLarge.has(p) && !missing.has(p));
      if (lost.length) fail(`${lost.length} listed paths never ended in files, too_large or missing: ${lost.slice(0, 3).join(', ')}`);
      const gone = listed.filter((p) => missing.has(p));
      if (gone.length) {
        const again = await manifestAll(ctx);
        if (again.hash === m.hash) fail(`${gone.length} paths listed by pack.manifest are reported missing by pack.get (${gone.slice(0, 2).join(', ')}) although the pack did not change`);
      }
      return `${listed.length} listed paths + 1 unknown + 1 duplicate: ${served.size} served, ${tooLarge.size} too_large, ${deferredRounds} round(s) with deferred paths`;
    },
  },
  {
    id: 'pack.get.args',
    group: 'pack',
    title: 'pack.get: no paths, an empty list, more than 64 paths, a non-array, a non-string or any path that breaks the path rule (nothing served): invalid_args',
    needs: ['op:pack.manifest', 'op:pack.get', 'hello'],
    timeout: 40000,
    async run(ctx) {
      await callWait(ctx, 'pack.manifest', {});
      const good = 'assets/pm_conformance/textures/block/ok.png';
      if (packPathError(good)) fail('suite error: the sample path must be valid');
      errCode(await ctx.call('pack.get', {}), 'invalid_args', 'missing paths');
      errCode(await ctx.call('pack.get', { paths: [] }), 'invalid_args', 'empty paths');
      errCode(await ctx.call('pack.get', { paths: good }), 'invalid_args', 'paths is a string');
      errCode(await ctx.call('pack.get', { paths: [good, 5] }), 'invalid_args', 'a path that is an int');
      errCode(await ctx.call('pack.get', { paths: Array.from({ length: PACK_MAX_PATHS + 1 }, (_, i) => `assets/pm_conformance/textures/block/k${i}.png`) }), 'invalid_args', '65 paths');
      const bad = {
        sounds: 'assets/pm_conformance/sounds/a.ogg',
        lang: 'assets/pm_conformance/lang/en_us.json',
        items: 'assets/pm_conformance/items/ruby.json',
        uppercase: 'assets/pm_conformance/textures/block/Ruby.png',
        'dot-dot': 'assets/pm_conformance/models/../../x.json',
        'not under assets': 'data/pm_conformance/models/a.json',
        absolute: '/assets/pm_conformance/models/a.json',
        backslash: 'assets\\pm_conformance\\models\\a.json',
        'png model': 'assets/pm_conformance/models/block/a.png',
        'json texture': 'assets/pm_conformance/textures/block/a.json',
        'empty segment': 'assets/pm_conformance/textures/block//a.png',
        'too long': `assets/pm_conformance/textures/${'a'.repeat(520)}.png`,
        'namespace only': 'assets/pm_conformance',
      };
      for (const [label, p] of Object.entries(bad)) {
        if (!packPathError(p)) fail(`suite error: ${label} should break the path rule`);
        errCode(await ctx.call('pack.get', { paths: [p] }), 'invalid_args', `${label} alone`);
        errCode(await ctx.call('pack.get', { paths: [good, p] }), 'invalid_args', `${label} after a valid path (nothing is served)`);
      }
      return `${Object.keys(bad).length} bad path forms rejected, alone and after a valid path`;
    },
  },
];
