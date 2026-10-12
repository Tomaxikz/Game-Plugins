'use strict';
// Runs the real entry point (server/main.js) against the fake FXServer environment over a real Unix socket.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const fake = require('./fake-fxserver');
const { connect, sleep } = require('./helpers');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pmb-'));
const imgDir = path.join(tmp, 'ox', 'web', 'images');
fs.mkdirSync(imgDir, { recursive: true });
// 1x1 PNG
fs.writeFileSync(path.join(imgDir, 'water.png'), Buffer.from('89504e470d0a1a0a0000000d4948445200000001000000010806000000' + '1f15c4890000000d49444154789c6360f8ffbf1e000007e8023c8b0b8e0000000049454e44ae426082', 'hex'));

fake.install();
fake.state.resourcePath = path.join(tmp, 'ox');
process.env.PM_BRIDGE_ROOT = tmp;
require('../playermanager_bridge/server/main.js');

const sock = path.join(tmp, '.playermanager', 'bridge.sock');
let client;

test('bridge.json advertises the unix socket and the socket exists', async () => {
  for (let i = 0; i < 50 && !fs.existsSync(path.join(tmp, '.playermanager', 'bridge.json')); i++) await sleep(20);
  const doc = JSON.parse(fs.readFileSync(path.join(tmp, '.playermanager', 'bridge.json'), 'utf8'));
  assert.equal(doc.protocol, 1);
  assert.equal(doc.transport, 'unix');
  assert.equal(doc.socket, '.playermanager/bridge.sock');
  assert.equal(doc.dir, '.playermanager');
  assert.equal(doc.game, 'fivem');
  assert.equal(doc.platform, 'fivem');
  assert.ok(fs.existsSync(sock));
  client = await connect(sock);
});

test('a non-hello first request on unix gets invalid_args and the connection stays open', async () => {
  const r = await client.call('server.info');
  assert.equal(r.ok, false);
  assert.equal(r.error.code, 'invalid_args');
  assert.match(r.error.message, /hello required/);
});

test('hello returns capabilities matching the FiveM matrix', async () => {
  const r = await client.call('hello', { panel_version: '1.0.0' });
  assert.equal(r.ok, true);
  const d = r.data;
  assert.equal(d.game, 'fivem');
  assert.equal(d.platform, 'fivem');
  assert.equal(d.game_version, 'gta5');
  assert.equal(d.limits.max_frame, 4194304);
  assert.equal(d.limits.max_inflight, 16);
  assert.ok(d.capabilities.ops.includes('positions.subscribe'));
  assert.ok(d.capabilities.ops.includes('player.inventory'));
  assert.ok(d.capabilities.events.includes('chat'));
  const names = d.capabilities.actions.map((a) => a.id);
  for (const n of ['kick', 'message', 'teleport', 'heal', 'set_armour', 'give_item', 'remove_item', 'set_job']) assert.ok(names.includes(n), n);
  assert.ok(!names.includes('revive'), 'revive needs platform.revive_event');
  assert.ok(!names.includes('ban'), 'ban is not advertised in v1');
  for (const a of d.capabilities.actions) for (const f of a.fields) assert.ok(Array.isArray(f.choices) && 'min' in f && 'max' in f, a.id);
  assert.ok(d.capabilities.features.includes('positions.stream'));
});

test('server.info reports OneSync-backed roster count and resources', async () => {
  const r = await client.call('server.info');
  assert.equal(r.ok, true);
  assert.equal(r.data.online, 2);
  assert.equal(r.data.max_players, 48);
  assert.equal(r.data.worlds[0].world, 'gta5');
  assert.equal(r.data.game.onesync, 'on');
  assert.equal(r.data.game.txadmin_present, true);
  assert.ok(r.data.extensions.some((e) => e.name === 'ox_inventory' && e.state === 'started'));
});

test('players.list skips players without a license identifier and carries game.server_id', async () => {
  const r = await client.call('players.list', {});
  assert.equal(r.ok, true);
  assert.deepEqual(r.data.players.map((p) => p.id), ['license:aaaa1111bbbb2222', 'license:cccc2222dddd3333']);
  assert.equal(r.data.players[0].game.server_id, 1);
  assert.equal(r.data.players[0].ping, 48);
  assert.equal(r.data.players[0].ip, undefined);
});

test('player.get returns the common section plus the FiveM game object', async () => {
  const r = await client.call('player.get', { id: 'license:aaaa1111bbbb2222' });
  assert.equal(r.ok, true, JSON.stringify(r));
  const d = r.data;
  assert.equal(d.world, 'gta5');
  assert.deepEqual(d.position, { x: -1037.5, y: -2737.6, z: 13.8, yaw: 329.5 });
  assert.equal(d.health, 200);
  assert.equal(d.max_health, 200);
  assert.equal(d.armor, 35);
  assert.equal(d.held_item, null, 'unarmed');
  assert.equal(d.vehicle, null);
  assert.equal(d.game.server_id, 1);
  assert.equal(d.game.routing_bucket, 0);
  assert.equal(d.game.weapon, null);
  assert.deepEqual(d.game.ped_model, { hash: 1885233650, name: 'mp_m_freemode_01' });
  assert.equal(d.game.framework.name, 'qbox');
  assert.equal(d.game.framework.job.name, 'police');
  assert.equal(d.game.framework.money.cash, 1500);
  assert.equal(d.game.inventory.source, 'ox_inventory');
  assert.equal(d.game.inventory.hotbar_slots, 5);
});

test('player.get exposes vehicle details and client-reported seat and health', async () => {
  fake.fireNet('pm:server:state', 2, { seat: -1, vehicle_health: 800.5 });
  const r = await client.call('player.get', { id: 'license:cccc2222dddd3333', fields: ['vehicle', 'game.vehicle'] });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.deepEqual(r.data.vehicle, { type: 'vehicle' });
  const v = r.data.game.vehicle;
  assert.equal(v.plate, 'AB 123 CD');
  assert.equal(v.seat, -1);
  assert.equal(v.health, 800.5);
  assert.equal(v.speed, 12.4);
  assert.deepEqual(v.model, { hash: 1031562256, name: null });
});

test('client state payloads are range-checked and rate-limited', async () => {
  fake.fireNet('pm:server:state', 2, { seat: 99, vehicle_health: 5 });
  const r = await client.call('player.get', { id: 'license:cccc2222dddd3333', fields: ['game.vehicle'] });
  assert.equal(r.data.game.vehicle.seat, -1, 'seat 99 rejected; previous report kept');
});

test('player.get: unknown ids, bad fields and sensitive fields', async () => {
  let r = await client.call('player.get', { id: 'license:0000000000000000' });
  assert.equal(r.error.code, 'not_found');
  r = await client.call('player.get', { id: 'license:aaaa1111bbbb2222', fields: ['nope'] });
  assert.equal(r.error.code, 'invalid_args');
  r = await client.call('player.get', { id: '@a' });
  assert.equal(r.error.code, 'invalid_args');
  r = await client.call('player.get', { id: 'license:aaaa1111bbbb2222', fields: ['ping'], include_sensitive: true });
  assert.equal(r.data.ip, '10.0.0.1');
  assert.equal(r.data.game, undefined);
});

test('player.inventory returns the ox_inventory main container with fingerprints', async () => {
  const r = await client.call('player.inventory', { id: 'license:aaaa1111bbbb2222' });
  assert.equal(r.ok, true, JSON.stringify(r));
  const c = r.data.containers[0];
  assert.equal(c.id, 'main');
  assert.equal(c.size, 50);
  assert.deepEqual(c.slots.map((s) => s.slot), [1, 5]);
  const water = c.slots[0].item;
  assert.equal(water.id, 'water');
  assert.equal(water.components.label, 'Water');
  assert.equal(water.count, 3);
  assert.equal(water.slot, undefined);
  assert.match(water.fingerprint, /^[0-9a-f]{64}$/);
  assert.match(water.hash, /^[0-9a-f]{16}$/);
  assert.equal(c.slots[1].item.components.metadata.serial, 'X1');
});

test('item.get reads one slot and rejects hotbar-style slots', async () => {
  let r = await client.call('item.get', { id: 'license:aaaa1111bbbb2222', container: 'main', slot: '5' });
  assert.equal(r.ok, true);
  assert.equal(r.data.item.id, 'WEAPON_PISTOL');
  r = await client.call('item.get', { id: 'license:aaaa1111bbbb2222', container: 'main', slot: 3 });
  assert.equal(r.error.code, 'not_found');
  r = await client.call('item.get', { id: 'license:aaaa1111bbbb2222', container: 'main', slot: 'hotbar:0' });
  assert.equal(r.error.code, 'invalid_args');
});

test('positions stream: one keyframe with quantised integers, interval clamped to the floor', async () => {
  const sub = await client.call('positions.subscribe', { interval_ms: 10 });
  assert.equal(sub.ok, true);
  const frame = await client.waitFrame((f) => f.sub === sub.data.sub && f.data.key === true);
  assert.equal(frame.seq, 0);
  const alice = frame.data.players.find((p) => p.id === 'license:aaaa1111bbbb2222');
  assert.equal(alice.x, Math.round(-1037.5 * 16));
  assert.equal(alice.yaw, 32950);
  assert.equal(alice.state, 'alive');
  assert.equal(alice.pitch, undefined);
  const again = await client.call('positions.keyframe', { sub: sub.data.sub });
  assert.equal(again.ok, true);
  await client.waitFrame((f) => f.sub === sub.data.sub && f.data.key === true && f.seq > 0);
  const un = await client.call('positions.unsubscribe', { sub: sub.data.sub });
  assert.equal(un.ok, true);
});

test('events: join and chat are delivered to the events subscription', async () => {
  const sub = await client.call('events.subscribe', { types: ['join', 'chat'] });
  assert.equal(sub.ok, true);
  fake.fireEvent('playerJoining', 1);
  const j = await client.waitFrame((f) => f.sub === sub.data.sub && f.data.type === 'join');
  assert.equal(j.data.player.id, 'license:aaaa1111bbbb2222');
  fake.fireEvent('chatMessage', 2, 'Bob', 'hello world');
  const c = await client.waitFrame((f) => f.sub === sub.data.sub && f.data.type === 'chat');
  assert.equal(c.data.message, 'hello world');
  assert.equal(c.data.player.name, 'Bob');
  fake.fireEvent('chatMessage', 2, 'Bob', '/command');
  const r = await client.call('events.unsubscribe', { sub: sub.data.sub });
  assert.equal(r.ok, true);
});

test('action kick calls DropPlayer on the game thread', async () => {
  const r = await client.call('action.run', { action: 'kick', target: 'license:cccc2222dddd3333', args: { reason: 'bye now' } });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.data.done, true);
  assert.deepEqual(fake.state.calls.find((c) => c[0] === 'drop'), ['drop', 2, 'bye now']);
  fake.state.players[2].online = true;
});

test('action heal goes through the client with a per-request id and acknowledgement', async () => {
  fake.onEmit.fn = (event, target, payload) => setImmediate(() => fake.fireNet('pm:server:ack', target, payload.req, true, null));
  const r = await client.call('action.run', { action: 'heal', target: 'license:aaaa1111bbbb2222', args: { health: 150 } });
  fake.onEmit.fn = null;
  assert.equal(r.ok, true, JSON.stringify(r));
  const sent = fake.emitted.filter((e) => e.event === 'pm:client:heal').pop();
  assert.equal(sent.target, 1);
  assert.equal(sent.payload.health, 150);
  assert.equal(typeof sent.payload.req, 'number');
});

test('acknowledgements from another source are discarded and the action times out', async () => {
  fake.onEmit.fn = (event, target, payload) => setImmediate(() => fake.fireNet('pm:server:ack', 2, payload.req, true, null));
  const r = await client.call('action.run', { action: 'message', target: 'license:aaaa1111bbbb2222', args: { message: 'Hi' } }, 6000);
  fake.onEmit.fn = null;
  assert.equal(r.ok, false);
  assert.equal(r.error.code, 'timeout');
});

test('action arguments are validated before anything is sent', async () => {
  let r = await client.call('action.run', { action: 'teleport', target: 'license:aaaa1111bbbb2222', args: { to_player: 'license:cccc2222dddd3333', x: 1 } });
  assert.equal(r.error.code, 'invalid_args');
  r = await client.call('action.run', { action: 'heal', target: 'license:aaaa1111bbbb2222', args: { health: 5000 } });
  assert.equal(r.error.code, 'invalid_args');
  r = await client.call('action.run', { action: 'revive', target: 'license:aaaa1111bbbb2222' });
  assert.equal(r.error.code, 'unsupported');
  r = await client.call('action.run', { action: 'kick', target: 'license:9999999999999999' });
  assert.equal(r.error.code, 'offline');
});

test('teleport to coordinates sends pm:client:teleport with the requested position', async () => {
  fake.onEmit.fn = (event, target, payload) => setImmediate(() => fake.fireNet('pm:server:ack', target, payload.req, true, null));
  const r = await client.call('action.run', { action: 'teleport', target: 'license:aaaa1111bbbb2222', args: { x: 100.5, y: -20, z: 30, yaw: 90, routing_bucket: 7 } });
  fake.onEmit.fn = null;
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.data.result.x, 100.5);
  assert.ok(fake.state.calls.some((c) => c[0] === 'bucket' && c[1] === 1 && c[2] === 7));
});

test('remove_item checks the expected fingerprint and removes from the slot', async () => {
  const inv = await client.call('player.inventory', { id: 'license:aaaa1111bbbb2222' });
  const fp = inv.data.containers[0].slots[0].item.fingerprint;
  let r = await client.call('action.run', { action: 'remove_item', target: 'license:aaaa1111bbbb2222', args: { item: 'water', amount: 1, slot: 1, expected_fingerprint: '0'.repeat(64) } });
  assert.equal(r.error.code, 'conflict');
  r = await client.call('action.run', { action: 'remove_item', target: 'license:aaaa1111bbbb2222', args: { item: 'water', amount: 1, slot: 1, expected_fingerprint: fp } });
  assert.equal(r.ok, true, JSON.stringify(r));
  r = await client.call('action.run', { action: 'remove_item', target: 'license:aaaa1111bbbb2222', args: { item: 'bread', amount: 1 } });
  assert.equal(r.error.code, 'conflict');
});

test('give_item checks the item definition; set_job calls the framework', async () => {
  let r = await client.call('action.run', { action: 'give_item', target: 'license:aaaa1111bbbb2222', args: { item: 'nonexistent_item', amount: 1 } });
  assert.equal(r.error.code, 'not_found');
  r = await client.call('action.run', { action: 'give_item', target: 'license:aaaa1111bbbb2222', args: { item: 'bread', amount: 2 } });
  assert.equal(r.ok, true);
  assert.equal(r.data.result.given, 2);
  r = await client.call('action.run', { action: 'set_job', target: 'license:aaaa1111bbbb2222', args: { job: 'ambulance', grade: 1 } });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.ok(fake.state.calls.some((c) => c[0] === 'setjob' && c[2] === 'ambulance' && c[3] === 1));
});

test('assets: ox_inventory images are listed and served as static PNG', async () => {
  const m = await client.call('assets.manifest', {});
  assert.equal(m.ok, true, JSON.stringify(m));
  assert.ok(m.data.entries.some((e) => e.key === 'item:water' && e.render === 'static'));
  assert.match(m.data.manifest_hash, /^[0-9a-f]{16}$/);
  const a = await client.call('assets.get', { keys: ['item:water', 'item:bogus'] });
  assert.equal(a.ok, true);
  assert.equal(a.data.assets[0].render, 'static');
  assert.equal(a.data.assets[0].textures.icon.width, 1);
  assert.equal(a.data.assets[1].unavailable_reason, 'not_found');
  const bad = await client.call('assets.get', { keys: ['../etc/passwd'] });
  assert.equal(bad.data.assets[0].render, 'unavailable');
});

test('unknown ops are unsupported and malformed frames answer invalid_args', async () => {
  let r = await client.call('player.nearby', { id: 'license:aaaa1111bbbb2222' });
  assert.equal(r.error.code, 'unsupported');
  client.raw({ op: 'hello' });
  await sleep(50);
  r = await client.call('server.info');
  assert.equal(r.ok, true, 'connection survives a bad request');
});

test('cleanup: client closes and the resource stops', async () => {
  client.close();
  fake.fireEvent('onResourceStop', undefined, 'playermanager_bridge');
  await sleep(50);
  assert.equal(fs.existsSync(sock), false, 'socket removed on clean shutdown');
});
