'use strict';
// bridge.json (atomic), token file (0600), paths under the server root.
const path = require('path');
const crypto = require('crypto');

function paths(dir) {
  return {
    dir,
    socket: path.join(dir, 'bridge.sock'),
    json: path.join(dir, 'bridge.json'),
    token: path.join(dir, 'token'),
    config: path.join(dir, 'config.json'),
    cache: path.join(dir, 'cache'),
  };
}

function writeAtomic(fs, file, data, mode) {
  const tmp = file + '.tmp-' + process.pid;
  fs.writeFileSync(tmp, data, mode ? { mode } : undefined);
  fs.renameSync(tmp, file);
}

function ensureToken(fs, file) {
  try {
    const t = fs.readFileSync(file, 'utf8').trim();
    if (/^[0-9a-f]{64}$/.test(t)) return t;
  } catch (e) { /* create below */ }
  const token = crypto.randomBytes(32).toString('hex');
  writeAtomic(fs, file, token + '\n', 0o600);
  try { fs.chmodSync(file, 0o600); } catch (e) { /* best effort on non-POSIX */ }
  return token;
}

function resolveServerRoot(fs, { cwd, resourcePath, override }) {
  if (override) return override;
  if (cwd && fs.existsSync(path.join(cwd, 'resources'))) return cwd;
  if (resourcePath) {
    const parts = path.resolve(resourcePath).split(path.sep);
    const idx = parts.lastIndexOf('resources');
    if (idx > 0) return parts.slice(0, idx).join(path.sep) || path.sep;
  }
  return cwd;
}

module.exports = { paths, writeAtomic, ensureToken, resolveServerRoot };
