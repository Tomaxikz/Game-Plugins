#!/usr/bin/env node
// Behavioural smoke test of the mod-loader bridges against a running server WITH an online bot player.
// It goes beyond conformance (which never runs state-changing actions): every action of minecraft.md section 7 that the bridge
// advertises is executed against the bot (a throw-away offline-mode test account) and the result is verified through player.get /
// player.inventory, and events are checked against console-driven game activity.
//
//   node scripts/modded-smoke.mjs --root <server-root> [--player <uuid>]
//
// The server console is reached through <root>/console.in (the fifo created by run-modded-conformance.sh) to provoke events
// (damage, chat, advancement); no bridge input ever becomes a console command.
// Exit code 0 when every check passed.
import { existsSync, appendFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { BridgeClient } from '../../../conformance/lib/client.mjs';

const args = process.argv.slice(2);
const opt = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : undefined; };
const root = opt('--root');
if (!root) { console.error('usage: modded-smoke.mjs --root <server-root> [--player <uuid>]'); process.exit(2); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const console_in = join(root, 'console.in');
const con = (line) => { if (existsSync(console_in)) writeFileSync(console_in, line + '\n', { flag: 'a' }); };

const c = await BridgeClient.connect({ path: join(root, '.playermanager', 'bridge.sock') });
const hello = (await c.request('hello', { panel_version: '1' })).data;
const caps = hello.capabilities;
const advertised = new Set(caps.actions.map((a) => a.id));
const rows = (await c.request('players.list', {})).data.players;
const pid = opt('--player') ?? rows.find((p) => p.online)?.id;
if (!pid) { console.error('no online player'); process.exit(2); }
const pname = rows.find((p) => p.id === pid).name;

let failed = 0, passed = 0;
const ok = (name, cond, extra = '') => { if (cond) { passed++; console.log(`PASS ${name}${extra ? ' — ' + extra : ''}`); } else { failed++; console.log(`FAIL ${name}${extra ? ' — ' + extra : ''}`); } };
const act = async (action, a = {}, extra = {}) => {
  const r = await c.request('action.run', { action, target: extra.noTarget ? undefined : (extra.target ?? pid), args: a, ...(extra.expect ? { expect: extra.expect } : {}) }, { timeout: 12000 });
  return r;
};
const get = async (fields) => (await c.request('player.get', { id: pid, ...(fields ? { fields } : {}) }, { timeout: 5000 })).data;
const inv = async () => (await c.request('player.inventory', { id: pid }, { timeout: 5000 })).data.containers;
const slotOf = (containers, cid, pred) => containers.find((x) => x.id === cid)?.slots.find((s) => pred(s.item));

// ---- events collection
const evs = await c.subscribe('events.subscribe', { types: caps.events });
const seen = [];
const pump = (async () => { for (;;) { try { const f = await evs.sub.next(60000); seen.push(f.data); } catch { return; } } })();
const sawEvent = async (type, pred = () => true, ms = 4000) => { const end = Date.now() + ms; while (Date.now() < end) { if (seen.some((e) => e.type === type && pred(e))) return true; await sleep(100); } return false; };

const run = async (name, fn) => { try { await fn(); } catch (e) { failed++; console.log(`FAIL ${name} — threw ${e.message}`); } };
const needs = (id) => { if (!advertised.has(id)) { console.log(`SKIP ${id} — not advertised`); return false; } return true; };

await run('messages', async () => {
  for (const [id, a] of [['message', { message: 'smoke message' }], ['whisper', { message: 'smoke whisper' }], ['actionbar', { message: 'smoke bar' }],
    ['title', { title: 'Smoke', subtitle: 'test', fade_in_ticks: 5, stay_ticks: 20, fade_out_ticks: 5 }]]) {
    if (!needs(id)) continue;
    const r = await act(id, a);
    ok(`action ${id}`, r.ok && r.data.ok !== false, JSON.stringify(r.data ?? r.error));
  }
  if (needs('broadcast')) { const r = await act('broadcast', { message: 'smoke broadcast' }, { noTarget: true }); ok('action broadcast', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error)); }
});

await run('events', async () => {
  await sleep(3500);   // 26.x: a joining or respawning player is invulnerable until the client has loaded (60 ticks)
  con(`say smoke-say`);
  con(`damage ${pname} 2 minecraft:generic`);
  con(`advancement grant ${pname} only minecraft:story/root`);
  await sleep(500);
  ok('event damage', await sawEvent('damage', (e) => e.player.id === pid && e.amount > 0), JSON.stringify(seen.filter((e) => e.type === 'damage').slice(-1)));
  ok('event advancement', await sawEvent('advancement', (e) => e.player.id === pid && e.id === 'minecraft:story/root'), JSON.stringify(seen.filter((e) => e.type === 'advancement').slice(-1)));
  ok('event command (console)', await sawEvent('command', (e) => e.source === 'console' && /say|damage/.test(e.command)));
  if (needs('kill')) {
    const r = await act('kill', {});
    ok('action kill', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
    ok('event death', await sawEvent('death', (e) => e.player.id === pid), JSON.stringify(seen.filter((e) => e.type === 'death').slice(-1)));
    await sleep(4500);   // let the bot respawn
  }
  // chat and join/leave need the bot; chat events appear only if BOT_CHATTER=1 was set for the bot
  const chat = seen.filter((e) => e.type === 'chat');
  console.log(`INFO chat events seen: ${chat.length}; join: ${seen.filter((e) => e.type === 'join').length}; leave: ${seen.filter((e) => e.type === 'leave').length}`);
});

await run('items', async () => {
  if (needs('clear_inventory')) { const r = await act('clear_inventory'); ok('action clear_inventory', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error)); }
  if (needs('give_item')) {
    const r = await act('give_item', { item: 'minecraft:apple', amount: 70 });
    ok('action give_item 70 (splits stacks)', r.ok && r.data.ok && r.data.result?.given === 70, JSON.stringify(r.data ?? r.error));
    const cs = await inv();
    const apples = cs.find((x) => x.id === 'inventory').slots.filter((s) => s.item.id === 'minecraft:apple').reduce((n, s) => n + s.item.count, 0);
    ok('inventory shows 70 apples', apples === 70, String(apples));
  }
  if (needs('give_custom_item')) {
    const rec = { id: 'minecraft:diamond_sword', count: 1, components: { 'minecraft:custom_name': '{"text":"Smoke Blade","color":"red"}', 'minecraft:enchantments': { 'minecraft:sharpness': 3 }, 'minecraft:damage': 5 } };
    const r = await act('give_custom_item', { item: rec });
    ok('action give_custom_item', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
    const s = slotOf(await inv(), 'inventory', (i) => i.id === 'minecraft:diamond_sword' && (i.components?.['minecraft:enchantments']));
    ok('custom item round trip (enchant+damage)', !!s && s.item.damage === 5 && s.item.enchantments?.[0]?.level === 3, JSON.stringify(s?.item?.components));
  }
  for (const sl of [5, 7]) await act('remove_item', { container: 'ender_chest', slot: sl, expected_id: 'minecraft:bread' });   // leftovers of an earlier run (ignored when empty)
  const cs0 = await inv();
  const apple = slotOf(cs0, 'inventory', (i) => i.id === 'minecraft:apple');
  if (apple) {
    const slot = apple.slot;
    const exp = { container: 'inventory', slot, id: 'minecraft:apple', count: apple.item.count };
    if (needs('set_item_count')) {
      const r = await act('set_item_count', { container: 'inventory', slot, amount: 3, expected_id: 'minecraft:apple', expected_count: apple.item.count });
      ok('action set_item_count', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
      ok('count is 3', slotOf(await inv(), 'inventory', (i) => i.id === 'minecraft:apple')?.item.count === 3);
    }
    if (needs('remove_item_live')) {
      const r = await act('remove_item_live', { container: 'inventory', slot, expected_id: 'minecraft:apple' });
      ok('remove_item_live conflicts on wrong expectation? (right id -> ok)', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
    }
    if (needs('set_item_live')) {
      const r = await act('set_item_live', { container: 'inventory', slot, expected_id: 'minecraft:air', item: { id: 'minecraft:golden_apple', count: 2 } });
      ok('set_item_live into the empty slot (expected_id air)', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
      const g = slotOf(await inv(), 'inventory', (i) => i.id === 'minecraft:golden_apple');
      ok('slot now holds 2 golden apples', g?.item.count === 2 && g.slot === slot, JSON.stringify(g));
      if (needs('replace_item_live')) {
        const r2 = await act('replace_item_live', { container: 'inventory', slot, expected_id: 'minecraft:golden_apple', item: { id: 'minecraft:bread', count: 1 }, amount: 4 });
        ok('action replace_item_live', r2.ok && r2.data.ok, JSON.stringify(r2.data ?? r2.error));
        const b = slotOf(await inv(), 'inventory', (i) => i.id === 'minecraft:bread');
        ok('bread x4', b?.item.count === 4, JSON.stringify(b?.item?.count));
      }
      if (needs('move_item_live')) {
        const r3 = await act('move_item_live', { container: 'inventory', slot, expected_id: 'minecraft:bread', destination_container: 'ender_chest', destination_slot: 5 });
        ok('action move_item_live to ender chest', r3.ok && r3.data.ok, JSON.stringify(r3.data ?? r3.error));
        const e = (await inv()).find((x) => x.id === 'ender_chest').slots.find((s) => s.slot === 5);
        ok('ender chest slot 5 has bread', e?.item.id === 'minecraft:bread');
        if (needs('move_item')) {
          const r4 = await act('move_item', { container: 'ender_chest', slot: 5, expected_id: 'minecraft:bread', destination_slot: 7 });
          ok('action move_item within the ender chest', r4.ok && r4.data.ok, JSON.stringify(r4.data ?? r4.error));
          const e7 = (await inv()).find((x) => x.id === 'ender_chest').slots.find((s) => s.slot === 7);
          ok('ender chest slot 7 has bread now', e7?.item.id === 'minecraft:bread');
        }
        if (needs('remove_item')) {
          const r5 = await act('remove_item', { container: 'ender_chest', slot: 7, expected_id: 'minecraft:bread' });
          ok('action remove_item', r5.ok && r5.data.ok, JSON.stringify(r5.data ?? r5.error));
        }
      }
    }
    // stale expectation
    const r6 = await act('remove_item_live', { container: 'inventory', slot: 35, expected_id: 'minecraft:stone' });
    ok('stale expectation -> conflict', !r6.ok && r6.error?.code === 'conflict', JSON.stringify(r6.error ?? r6.data));
  }
});

await run('vitals', async () => {
  for (const [id, a, check, label] of [
    ['set_health', { amount: 7 }, (g) => Math.abs(Number(g.health) - 7) < 0.01, 'health 7'],
    ['set_food', { amount: 9 }, (g) => g.food === 9, 'food 9'],
    ['set_saturation', { amount: 2 }, (g) => Math.abs(g.saturation - 2) < 0.01, 'saturation 2'],
    ['set_experience', { amount: 12 }, (g) => g.experience.level === 12, 'xp level 12'],
    ['apply_effect', { effect: 'minecraft:speed', duration: 30, amplifier: 1 }, (g) => g.game.effects.some((e) => e.id === 'minecraft:speed' && e.amplifier === 1 && e.duration_ticks > 500), 'speed II ~600 ticks'],
    ['clear_effects', {}, (g) => g.game.effects.length === 0, 'no effects'],
    ['heal', {}, (g) => Number(g.health) === Number(g.max_health) && g.food === 20, 'healed'],
    ['gamemode', { mode: 'creative' }, (g) => g.game_mode === 'creative', 'creative'],
    ['set_scale', { scale: 2, attribute: 'minecraft:scale' }, (g) => Number(g.game.scale) === 2, 'scale 2'],
    ['reset_scale', {}, (g) => Number(g.game.scale) === 1, 'scale 1'],
    ['gamemode', { mode: 'survival' }, (g) => g.game_mode === 'survival', 'survival'],
  ]) {
    if (!needs(id)) continue;
    const r = await act(id, a);
    await sleep(150);
    const g = await get(id === 'set_experience' ? undefined : undefined);
    ok(`action ${id} -> ${label}`, r.ok && r.data.ok && check(g), r.ok ? `now: health=${g.health}/${g.max_health} food=${g.food} scale=${g.game?.scale} ${JSON.stringify(r.data)}` : JSON.stringify(r.error));
  }
  const r = await act('apply_effect', { effect: 'minecraft:nope', duration: 5, amplifier: 0 });
  ok('apply_effect unknown effect -> not_found', !r.ok && r.error?.code === 'not_found', JSON.stringify(r.error ?? r.data));
});

await run('teleport', async () => {
  if (!needs('teleport')) return;
  const r = await act('teleport', { x: 100.5, y: 70, z: -20.5, yaw: 90, pitch: 10 });
  await sleep(300);
  const g = await get();
  ok('teleport to coordinates', r.ok && r.data.ok && Math.abs(g.position.x - 100.5) < 0.01 && Math.abs(g.position.z + 20.5) < 0.01, JSON.stringify(r.data ?? r.error));
  const n = await act('teleport', { dimension: 'minecraft:the_nether', x: 10, y: 80, z: 10 });
  await sleep(500);
  const g2 = await get();
  ok('teleport across dimensions', n.ok && n.data.ok && g2.dimension === 'minecraft:the_nether', `${g2.dimension} ${JSON.stringify(n.data ?? n.error)}`);
  const back = await act('teleport', { dimension: 'minecraft:overworld', x: 0.5, y: -59, z: 0.5 });
  await sleep(500);
  ok('teleport back', back.ok && (await get()).dimension === 'minecraft:overworld');
  const bad = await act('teleport', { dimension: 'mymod:nowhere', x: 0, y: 0, z: 0 });
  ok('teleport to unknown dimension -> not_found', !bad.ok && bad.error?.code === 'not_found', JSON.stringify(bad.error ?? bad.data));
  const mixed = await act('teleport', { x: 1, y: 2, z: 3, to_player: pid });
  ok('teleport mixing forms -> invalid_args', !mixed.ok && mixed.error?.code === 'invalid_args', JSON.stringify(mixed.error ?? mixed.data));
});

await run('world', async () => {
  for (const [id, a] of [['time', { amount: 6000 }], ['weather', { value: 'clear' }], ['difficulty', { value: 'normal' }], ['gamerule', { rule: 'doDaylightCycle', value: 'true' }], ['gamerule', { rule: 'advance_time', value: 'true' }], ['save', {}]]) {
    if (!needs(id)) continue;
    const r = await act(id, a, { noTarget: true });
    ok(`action ${id} ${JSON.stringify(a)}`, r.ok, JSON.stringify(r.data ?? r.error));
  }
});

await run('roster', async () => {
  const other = '853c80ef-3c37-49fd-aa49-938b674adae6';   // jeb_, any UUID: unknown players are not_found unless the profile cache knows them
  if (needs('whitelist_on')) { const r = await act('whitelist_on', {}, { noTarget: true }); ok('whitelist_on', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error)); }
  if (needs('whitelist_off')) { const r = await act('whitelist_off', {}, { noTarget: true }); ok('whitelist_off', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error)); }
  if (needs('whitelist')) {
    const r = await act('whitelist', {});
    ok('whitelist player', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
    ok('player.get game.whitelisted', (await get()).game.whitelisted === true);
    const r2 = await act('unwhitelist', {});
    ok('unwhitelist player', r2.ok && r2.data.ok, JSON.stringify(r2.data ?? r2.error));
    ok('player.get game.whitelisted false', (await get()).game.whitelisted === false);
  }
  if (needs('op')) {
    const r = await act('op', {});
    ok('op player', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
    await sleep(200);
    const g = await get();
    ok('game.op true, op_level>0', g.game.op === true && g.game.op_level > 0, `${g.game.op} ${g.game.op_level}`);
    const rr = (await c.request('players.list', {})).data.players.find((p) => p.id === pid);
    ok('players.list groups has operators + admin', rr.admin === true && rr.groups.includes('operators'));
    const r2 = await act('deop', {});
    ok('deop player', r2.ok && r2.data.ok, JSON.stringify(r2.data ?? r2.error));
  }
  const unk = await act('ban', {}, { target: other });
  ok('ban unknown player -> not_found or ok', unk.ok ? true : unk.error?.code === 'not_found', JSON.stringify(unk.error ?? unk.data));
  if (unk.ok) await act('unban', {}, { target: other });
});

await run('kick+ban', async () => {
  if (needs('ban')) {
    const r = await act('ban', { reason: 'smoke ban' });
    ok('action ban', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
    ok('event kick or leave after ban', await sawEvent('leave', (e) => e.player.id === pid) || await sawEvent('kick', (e) => e.player.id === pid));
    ok('event ban', await sawEvent('ban', (e) => e.player.id === pid), JSON.stringify(seen.filter((e) => e.type === 'ban').slice(-1)));
    const g = await get();
    ok('player.get offline shows banned object', g.online === false && g.game.banned?.reason === 'smoke ban', JSON.stringify(g.game?.banned));
    const list = (await c.request('players.list', { include_offline: true })).data.players.find((p) => p.id === pid);
    ok('players.list offline row groups banned + reason', list?.groups.includes('banned') && list.reason === 'smoke ban', JSON.stringify(list));
    if (needs('unban')) { const u = await act('unban', {}); ok('action unban', u.ok && u.data.ok, JSON.stringify(u.data ?? u.error)); }
  }
  if (needs('ban_ip')) {
    const r = await act('ban_ip', {}, { target: '203.0.113.9' });
    ok('action ban_ip (literal ip)', r.ok && r.data.ok, JSON.stringify(r.data ?? r.error));
    const u = await act('unban_ip', {}, { target: '203.0.113.9' });
    ok('action unban_ip', u.ok && u.data.ok, JSON.stringify(u.data ?? u.error));
  }
});

if (advertised.has('wipe_data')) {
  const w = await act('wipe_data', {});
  ok('wipe_data on an offline player without data -> refused or ok', w.ok || w.error?.code, JSON.stringify(w.data ?? w.error));
}

await sleep(200);
c.close();
console.log(`\nsmoke: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
