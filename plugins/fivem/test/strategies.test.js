'use strict';
// Write-strategy selection under each simulated FXServer policy. Each scenario runs in its own child process.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const RUNNER = path.join(__dirname, 'strategy-runner.js');

function run(scenario) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pms-'));
  const resourceDir = path.join(root, 'resources', '[playermanager]', 'playermanager_bridge');
  let out;
  try {
    out = execFileSync(process.execPath, [RUNNER, scenario, root, resourceDir], { encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    out = (e.stdout || '') + (e.stderr || '');
  }
  const line = out.split('\n').filter((l) => l.startsWith('RESULT ')).pop();
  if (!line) throw new Error('no RESULT from ' + scenario + ': ' + out.slice(-2000));
  return { ...JSON.parse(line.slice(7)), root, resourceDir };
}

const RESOURCE_REL = 'resources/[playermanager]/playermanager_bridge/.playermanager';

test('server root writable: plain fs at the contract location (strategy 1)', () => {
  const r = run('root-open');
  assert.equal(r.error, undefined, r.error);
  assert.equal(r.doc.dir, '.playermanager');
  assert.equal(r.doc.socket, '.playermanager/bridge.sock');
  assert.equal(r.bridgeJsonDir, path.join(r.root, '.playermanager'));
  assert.match(r.log, /write strategy server-root selected/);
  assert.equal(r.hello, true);
  assert.equal(r.socketRemoved, true);
});

test('server root denied, @alias works: resource folder via the alias (strategy 2)', () => {
  const r = run('alias');
  assert.equal(r.error, undefined, r.error);
  assert.equal(r.doc.dir, RESOURCE_REL);
  assert.equal(r.doc.socket, RESOURCE_REL + '/bridge.sock');
  assert.match(r.log, /write strategy resource-alias selected/);
  assert.match(r.log, /server-root: EPERM/);
  assert.equal(r.hello, true);
});

test('server root and @alias fail, absolute resource path works (strategy 3)', () => {
  const r = run('absolute');
  assert.equal(r.error, undefined, r.error);
  assert.equal(r.doc.dir, RESOURCE_REL);
  assert.match(r.log, /write strategy resource-absolute selected/);
  assert.match(r.log, /resource-alias: ENOENT/);
  assert.equal(r.hello, true);
});

test('only SaveResourceFile works (strategy 4): bridge.json written through the native', () => {
  const r = run('native');
  assert.equal(r.error, undefined, r.error);
  assert.equal(r.doc.dir, RESOURCE_REL);
  assert.equal(r.doc.socket, RESOURCE_REL + '/bridge.sock');
  assert.match(r.log, /write strategy save-resource-file selected/);
  assert.match(r.log, /resource-absolute: EPERM/);
  assert.ok(fs.existsSync(path.join(r.resourceDir, '.playermanager', 'bridge.json')));
  assert.equal(r.hello, true, 'socket bind does not need an fs write');
});

test('stale socket cannot be unlinked: bridge binds bridge-<pid>.sock and bridge.json names it', () => {
  const r = run('unlink');
  assert.equal(r.error, undefined, r.error);
  assert.match(r.log, /write strategy resource-alias selected/);
  assert.match(r.doc.socket, /^resources\/\[playermanager\]\/playermanager_bridge\/\.playermanager\/bridge-\d+\.sock$/);
  assert.match(r.log, /stale socket could not be removed/);
  assert.equal(r.hello, true);
});

test('no strategy works: bridge disabled, nothing written, server unaffected', () => {
  const r = run('none');
  assert.equal(r.error, undefined, r.error);
  assert.equal(r.disabled, true);
  assert.equal(r.doc, undefined);
});
