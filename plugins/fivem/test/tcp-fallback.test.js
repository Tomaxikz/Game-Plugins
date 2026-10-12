'use strict';
// Unix listen fails (path too long for AF_UNIX) -> TCP fallback with token auth.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const fake = require('./fake-fxserver');
const { connect, sleep } = require('./helpers');

let base = fs.mkdtempSync(path.join(os.tmpdir(), 'pmt-'));
let root = base;
while (Buffer.byteLength(path.join(root, '.playermanager', 'bridge.sock')) < 140) root = path.join(root, 'x'.repeat(40));
fs.mkdirSync(path.join(root, '.playermanager'), { recursive: true });
fs.writeFileSync(path.join(root, '.playermanager', 'config.json'), JSON.stringify({ tcp_fallback_port: 25691 }));

fake.install();
process.env.PM_BRIDGE_ROOT = root;
require('../playermanager_bridge/server/main.js');

let doc;
test('bridge.json switches to tcp with the fallback port', async () => {
  for (let i = 0; i < 50 && !fs.existsSync(path.join(root, '.playermanager', 'bridge.json')); i++) await sleep(20);
  doc = JSON.parse(fs.readFileSync(path.join(root, '.playermanager', 'bridge.json'), 'utf8'));
  assert.equal(doc.transport, 'tcp');
  assert.equal(doc.port, 25691);
  assert.equal(doc.socket, null);
});

test('token file is 32 random bytes as lowercase hex with mode 0600', () => {
  const tok = fs.readFileSync(path.join(root, '.playermanager', 'token'), 'utf8').trim();
  assert.match(tok, /^[0-9a-f]{64}$/);
  if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(root, '.playermanager', 'token')).mode & 0o777, 0o600);
});

test('first request without the token is unauthorized and the connection is closed', async () => {
  const c = await connect({ host: '127.0.0.1', port: 25691 });
  const r = await c.call('hello', { panel_version: '1.0.0' });
  assert.equal(r.error.code, 'unauthorized');
  await sleep(50);
  assert.equal(c.sock.destroyed, true);
});

test('wrong token is unauthorized', async () => {
  const c = await connect({ host: '127.0.0.1', port: 25691 });
  const r = await c.call('hello', { panel_version: '1.0.0', token: '0'.repeat(64) });
  assert.equal(r.error.code, 'unauthorized');
});

test('correct token in the first hello succeeds', async () => {
  const tok = fs.readFileSync(path.join(root, '.playermanager', 'token'), 'utf8').trim();
  const c = await connect({ host: '127.0.0.1', port: 25691 });
  const r = await c.call('hello', { panel_version: '1.0.0', token: tok });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.data.game, 'fivem');
  const s = await c.call('server.info');
  assert.equal(s.ok, true);
  c.close();
});

test('cleanup', async () => {
  fake.fireEvent('onResourceStop', undefined, 'playermanager_bridge');
  await sleep(30);
});
