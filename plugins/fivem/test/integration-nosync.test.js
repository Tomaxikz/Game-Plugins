'use strict';
// OneSync off: the bridge still runs but reports the whole platform as unsupported at hello (games/fivem.md section 9).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const fake = require('./fake-fxserver');
const { connect, sleep } = require('./helpers');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pmn-'));
fake.install();
fake.state.convars.onesync = 'off';
process.env.PM_BRIDGE_ROOT = tmp;
require('../playermanager_bridge/server/main.js');

test('hello reports unsupported when OneSync is off', async () => {
  const sock = path.join(tmp, '.playermanager', 'bridge.sock');
  for (let i = 0; i < 50 && !fs.existsSync(sock); i++) await sleep(20);
  const c = await connect(sock);
  const r = await c.call('hello', { panel_version: '1.0.0' });
  assert.equal(r.ok, true);
  assert.deepEqual(r.data.capabilities.ops, ['hello']);
  assert.deepEqual(r.data.capabilities.actions, []);
  assert.match(r.data.unsupported_reason, /OneSync/);
  const s = await c.call('server.info');
  assert.equal(s.error.code, 'unsupported');
  c.close();
  fake.fireEvent('onResourceStop', undefined, 'playermanager_bridge');
  await sleep(30);
});
