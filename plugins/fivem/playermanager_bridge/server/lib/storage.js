'use strict';
// Write-strategy selection (FXServer Node filesystem policy, see fsview.js and NOTES-section.md).
// Strategies are probed in order; the first that verifiably writes, reads back and compares wins.
//  1. server-root      plain fs, <server root>/.playermanager (contract location)
//  2. resource-alias   plain fs, resource dir, writes via the '@<resource>/' alias (FXServer VFS form)
//  3. resource-absolute plain fs, resource dir, absolute paths
//  4. save-resource-file  SaveResourceFile/LoadResourceFile natives (no Node fs at all)
const path = require('path');
const { makeFsView } = require('./fsview');

const PROBE_NAME = 'probe.txt';

function errOf(code, msg) { return Object.assign(new Error(msg), { code }); }

// Strategy 4: every write goes through SaveResourceFile. No rename, no unlink, no chmod, no mode bits.
function makeNativeView({ g, resourceName, resourceDir }) {
  const pending = new Map(); // temp path -> content (write-then-rename emulation)
  const rel = (p) => {
    const r = path.relative(path.resolve(resourceDir), path.resolve(String(p)));
    if (!r || r.startsWith('..') || path.isAbsolute(r)) throw errOf('EPERM', 'outside resource folder: ' + p);
    return r.split(path.sep).join('/');
  };
  const load = (r) => {
    if (typeof g.LoadResourceFile !== 'function') return null;
    const v = g.LoadResourceFile(resourceName, r);
    return typeof v === 'string' ? v : null;
  };
  const save = (r, content) => {
    if (typeof g.SaveResourceFile !== 'function') throw errOf('ENOSYS', 'SaveResourceFile not available');
    const ok = g.SaveResourceFile(resourceName, r, content, Buffer.byteLength(content));
    if (ok === false || ok === null || ok === undefined) throw errOf('EIO', 'SaveResourceFile refused ' + r);
  };
  const view = {
    mkdirSync() { /* the directory must exist (panel install or the probe fails) */ },
    writeFileSync(p, data, opts) {
      let content = String(data);
      if (opts && typeof opts === 'object' && opts.flag === 'a') content = (load(rel(p)) || '') + content;
      if (String(p).includes('.tmp-')) { pending.set(String(p), content); return; }
      save(rel(p), content);
    },
    renameSync(a, b) {
      const content = pending.has(String(a)) ? pending.get(String(a)) : load(rel(a));
      if (content === null || content === undefined) throw errOf('ENOENT', 'nothing to rename: ' + a);
      pending.delete(String(a));
      save(rel(b), content);
    },
    readFileSync(p) {
      const v = pending.has(String(p)) ? pending.get(String(p)) : load(rel(p));
      if (v === null || v === undefined) throw errOf('ENOENT', 'no such resource file: ' + p);
      return v;
    },
    existsSync(p) {
      try { return view.readFileSync(p) !== null; } catch (e) { return false; }
    },
    statSync(p) { return { size: Buffer.byteLength(view.readFileSync(p)) }; },
    unlinkSync() { throw errOf('EPERM', 'unlink is not available through SaveResourceFile'); },
    chmodSync() { /* mode bits cannot be set through the native: recorded in NOTES */ },
  };
  return view;
}

// Probe = create the directory, write a probe file, read it back, compare. Throws on any mismatch.
function probe(view, dir, tag) {
  view.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, PROBE_NAME);
  const payload = 'pm-probe ' + tag + ' ' + process.pid + '\n';
  view.writeFileSync(file, payload);
  const back = view.readFileSync(file, 'utf8');
  if (String(back) !== payload) throw errOf('EIO', 'read-back mismatch');
}

function chooseStorage({ g, fs, root, resourceName, resourceDir, log = () => {} }) {
  const absView = makeFsView({ fs, resourceName, resourceDir, aliasWrites: false });
  const aliasView = makeFsView({ fs, resourceName, resourceDir, aliasWrites: true });
  const localDir = path.join(resourceDir, '.playermanager');
  const strategies = [
    { name: 'server-root', view: absView, dir: path.join(root, '.playermanager') },
    { name: 'resource-alias', view: aliasView, dir: localDir },
    { name: 'resource-absolute', view: absView, dir: localDir },
    { name: 'save-resource-file', view: makeNativeView({ g, resourceName, resourceDir }), dir: localDir },
  ];
  const attempts = [];
  for (const s of strategies) {
    try {
      probe(s.view, s.dir, s.name);
      attempts.push(s.name + ': ok');
      log('write strategy ' + s.name + ' selected at ' + s.dir + ' (attempts: ' + attempts.join('; ') + ')');
      return { name: s.name, view: s.view, dir: s.dir, attempts };
    } catch (e) {
      attempts.push(s.name + ': ' + (e && (e.code || e.message)));
    }
  }
  log('no write strategy worked (attempts: ' + attempts.join('; ') + ')');
  return null;
}

module.exports = { chooseStorage, makeNativeView, probe, PROBE_NAME };
