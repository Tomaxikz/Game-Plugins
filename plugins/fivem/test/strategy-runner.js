'use strict';
// Child process for strategies.test.js: boots the real entry point under one simulated FXServer write policy,
// then prints one line "RESULT {json}" describing what was written and whether the socket answers hello.
const fs = require('fs');
const path = require('path');
const fake = require('./fake-fxserver');
const { connect, sleep } = require('./helpers');

const SCENARIOS = {
  'root-open':  { absoluteWrites: 'none', aliasWrites: 'ok', unlink: 'ok', native: true },
  'alias':      { absoluteWrites: 'outside-resource', aliasWrites: 'ok', unlink: 'ok', native: true },
  'absolute':   { absoluteWrites: 'outside-resource', aliasWrites: 'enoent', unlink: 'ok', native: true },
  'native':     { absoluteWrites: 'all', aliasWrites: 'eperm', unlink: 'eperm', native: true, preDir: true },
  'unlink':     { absoluteWrites: 'outside-resource', aliasWrites: 'ok', unlink: 'eperm', native: true, preSock: true },
  'none':       { absoluteWrites: 'all', aliasWrites: 'eperm', unlink: 'eperm', native: false },
};

async function main() {
  const [scenarioName, root, resourceDir] = process.argv.slice(2);
  const sc = SCENARIOS[scenarioName];
  if (!sc) throw new Error('unknown scenario ' + scenarioName);
  fs.mkdirSync(resourceDir, { recursive: true });
  if (sc.preDir || sc.preSock) fs.mkdirSync(path.join(resourceDir, '.playermanager'), { recursive: true });
  if (sc.preSock) fs.writeFileSync(path.join(resourceDir, '.playermanager', 'bridge.sock'), 'stale');

  fake.install();
  fake.state.resourcePath = resourceDir;
  if (sc.native) fake.installNative({ resourceDir });
  const policy = fake.installFsPolicy({ resourceName: 'playermanager_bridge', resourceDir, absoluteWrites: sc.absoluteWrites, aliasWrites: sc.aliasWrites, unlink: sc.unlink });
  process.env.PM_BRIDGE_ROOT = root;
  require('../playermanager_bridge/server/main.js');

  const candidates = [path.join(root, '.playermanager', 'bridge.json'), path.join(resourceDir, '.playermanager', 'bridge.json')];
  let found = null;
  for (let i = 0; i < 100 && !found; i++) {
    await sleep(40);
    found = candidates.find((c) => fs.existsSync(c)) || null;
  }
  const result = { scenario: scenarioName, denied: policy.denied.length };
  if (!found) {
    result.disabled = true;
  } else {
    result.doc = JSON.parse(fs.readFileSync(found, 'utf8'));
    result.bridgeJsonDir = path.dirname(found);
    result.socketPath = path.join(root, result.doc.socket || '');
    if (result.doc.transport === 'unix') {
      try {
        const c = await connect(result.socketPath);
        const r = await c.call('hello', { panel_version: '1.0.0' });
        result.hello = !!(r && r.ok);
        c.close();
      } catch (e) { result.hello = 'error: ' + e.message; }
    }
    const logFile = path.join(path.dirname(found), 'bridge.log');
    result.log = fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8') : null;
  }
  fake.fireEvent('onResourceStop', undefined, 'playermanager_bridge');
  await sleep(50);
  result.socketRemoved = result.socketPath ? !fs.existsSync(result.socketPath) : null;
  console.log('RESULT ' + JSON.stringify(result));
  process.exit(0);
}

main().catch((e) => { console.log('RESULT ' + JSON.stringify({ error: e.stack })); process.exit(1); });
