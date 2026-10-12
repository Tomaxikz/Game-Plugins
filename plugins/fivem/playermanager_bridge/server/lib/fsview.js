'use strict';
// Filesystem view used for every bridge write.
// FXServer's Node filesystem policy (citizenfx/fivem, FilesystemPermissions.cpp GetResourcePath lines 55-66 and
// ScriptingFilesystemAllowWrite lines 113-117; NodeScriptRuntime.cpp lines 152-180) allows a write only when the
// path is '@<own resource>/...'. An absolute path is refused because GetResourcePath() returns no resource name for it.
// Reads are not gated by that check (absolute paths resolve through FindDevice, NodeScriptRuntime.cpp 166-175).
// So when the resource-local directory is used, writes are rewritten to the '@' alias; reads stay absolute.
const path = require('path');

const WRITE_OPS = ['writeFileSync', 'mkdirSync', 'unlinkSync', 'appendFileSync', 'rmSync'];

function makeFsView({ fs, resourceName, resourceDir, aliasWrites }) {
  const alias = (p) => {
    if (!aliasWrites || typeof p !== 'string' || !resourceDir) return p;
    const rel = path.relative(path.resolve(resourceDir), path.resolve(p));
    if (!rel || rel.startsWith('..') || path.isAbsolute(rel)) return p;
    return '@' + resourceName + '/' + rel.split(path.sep).join('/');
  };
  const view = {};
  for (const k of Object.keys(fs)) {
    if (typeof fs[k] === 'function') view[k] = (...a) => fs[k](...a);
  }
  for (const op of WRITE_OPS) view[op] = (p, ...rest) => fs[op](alias(p), ...rest);
  view.renameSync = (a, b) => fs.renameSync(alias(a), alias(b));
  return view;
}

// Creates the directory and proves a write + rename + unlink works. Throws the underlying error on failure.
function probeWritable(view, dir) {
  view.mkdirSync(dir, { recursive: true });
  const a = path.join(dir, '.probe-' + process.pid + '.tmp');
  const b = a + '.2';
  view.writeFileSync(a, 'probe\n');
  view.renameSync(a, b);
  view.unlinkSync(b);
}

module.exports = { makeFsView, probeWritable, WRITE_OPS };
