// server.info, players.list, player.get, player.nearby, player.inventory, item.get.

import { Check, fail, Skip, isObj, isStr, isIntV, isNum, isHex } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { fakePlayerId, UUID_LOWER } from '../lib/protocol.mjs';
import {
  serverInfoErrors,
  playersListErrors,
  playerGetErrors,
  containersErrors,
  itemErrors,
  digestErrors,
} from '../lib/schema.mjs';
import { subtreeTruncated } from '../lib/canonical.mjs';
import { okData, errCode, describeResp } from './helpers.mjs';

export const tests = [
  {
    id: 'server.info.schema',
    group: 'server',
    title: 'server.info matches operations.md section 2',
    needs: ['op:server.info'],
    async run(ctx) {
      const d = okData(await ctx.call('server.info', {}), 'server.info');
      const errs = serverInfoErrors(d);
      if (errs.length) fail(errs.slice(0, 8).join('; '));
      return `${d.worlds.length} worlds, ${d.extensions?.length ?? 0} extensions`;
    },
  },
  {
    id: 'players.list.schema',
    group: 'players',
    title: 'players.list matches operations.md section 3 (groups always an array, max 5000)',
    needs: ['op:players.list'],
    async run(ctx) {
      const d = okData(await ctx.call('players.list', {}), 'players.list');
      const errs = playersListErrors(d, { features: ctx.features() });
      if (errs.length) fail(errs.slice(0, 8).join('; '));
      return `${d.players.length} entries`;
    },
  },
  {
    id: 'players.list.default-online-only',
    group: 'players',
    title: 'players.list without include_offline returns only online players, and count matches server.info.online',
    needs: ['op:players.list', 'op:server.info'],
    async run(ctx) {
      const d = okData(await ctx.call('players.list', {}), 'players.list');
      const offline = d.players.filter((p) => p.online !== true);
      if (offline.length) fail(`${offline.length} offline entries without include_offline`);
      const info = okData(await ctx.call('server.info', {}), 'server.info');
      const n = d.players.length;
      if (n !== num(info.online)) fail(`players.list has ${n} online, server.info.online is ${num(info.online)}`);
      return `${n} online`;
    },
  },
  {
    id: 'players.list.include-offline',
    group: 'players',
    title: 'players.list include_offline: offline entries only when offline_players is advertised',
    needs: ['op:players.list'],
    async run(ctx) {
      const d = okData(await ctx.call('players.list', { include_offline: true }), 'players.list include_offline');
      const errs = playersListErrors(d, { features: ctx.features() });
      if (errs.length) fail(errs.slice(0, 6).join('; '));
      const offline = d.players.filter((p) => p.online === false).length;
      if (!ctx.features().includes('offline_players') && offline) fail(`${offline} offline entries but offline_players not advertised`);
      return `${offline} offline entries`;
    },
  },
  {
    id: 'players.list.sensitive',
    group: 'players',
    title: 'players.list: ip only with include_sensitive (and only when the sensitive feature is advertised)',
    needs: ['op:players.list'],
    async run(ctx) {
      const plain = okData(await ctx.call('players.list', {}), 'players.list');
      if (plain.players.some((p) => 'ip' in p)) fail('ip present without include_sensitive');
      const sens = okData(await ctx.call('players.list', { include_sensitive: true }), 'players.list include_sensitive');
      const withSensitive = ctx.features().includes('sensitive');
      if (!withSensitive) {
        if (sens.players.some((p) => 'ip' in p)) fail('ip present but sensitive feature not advertised');
        return 'no ip (sensitive not advertised)';
      }
      const online = sens.players.filter((p) => p.online === true);
      const missing = online.filter((p) => !isStr(p.ip));
      if (missing.length) fail(`${missing.length} online players lack ip with include_sensitive`);
      return `ip on ${online.length} online players`;
    },
  },
  {
    id: 'player.get.not-found',
    group: 'player',
    title: 'player.get for an unknown id is not_found',
    needs: ['op:player.get', 'hello'],
    async run(ctx) {
      errCode(await ctx.call('player.get', { id: fakePlayerId(ctx.game) }), 'not_found', 'unknown player');
      return 'not_found';
    },
  },
  {
    id: 'player.get.schema',
    group: 'player',
    title: 'player.get common section and game object match operations.md section 4',
    needs: ['op:player.get', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const d = okData(await ctx.call('player.get', { id: p.id }), 'player.get');
      const errs = playerGetErrors(d, p.id);
      if (!isObj(d.game) && d.game !== undefined) errs.push('game must be an object');
      if (errs.length) fail(errs.slice(0, 8).join('; '));
      const missing = ctx.caps.player_fields.filter((f) => f !== 'game' && !(f in d));
      if (missing.length) fail(`advertised player_fields missing from player.get: ${missing.join(', ')}`);
      return `${Object.keys(d).length} keys`;
    },
  },
  {
    id: 'player.get.fields-filter',
    group: 'player',
    title: 'player.get fields filter returns only id, name, online plus the requested field',
    needs: ['op:player.get', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const field = ctx.caps.player_fields.find((f) => f !== 'game') ?? 'game';
      const d = okData(await ctx.call('player.get', { id: p.id, fields: [field] }), 'player.get fields');
      const allowed = new Set(['id', 'name', 'online', field]);
      const extra = Object.keys(d).filter((k) => !allowed.has(k));
      if (extra.length) fail(`unrequested keys returned: ${extra.join(', ')}`);
      if (!(field in d)) fail(`requested field ${field} missing`);
      for (const k of ['id', 'name', 'online']) if (!(k in d)) fail(`${k} must always be returned`);
      return `only ${field}`;
    },
  },
  {
    id: 'player.get.include-sensitive',
    group: 'player',
    title: 'player.get: ip only with include_sensitive',
    needs: ['op:player.get', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const plain = okData(await ctx.call('player.get', { id: p.id }), 'player.get');
      if ('ip' in plain) fail('ip present without include_sensitive');
      const sens = okData(await ctx.call('player.get', { id: p.id, include_sensitive: true }), 'player.get sensitive');
      if (ctx.features().includes('sensitive') && !isStr(sens.ip)) fail('ip missing with include_sensitive');
      return 'ip gated';
    },
  },
  {
    id: 'player.nearby.schema',
    group: 'player',
    title: 'player.nearby matches operations.md section 6 (look vector, target, entities <= 64 nearest first)',
    needs: ['op:player.nearby', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const d = okData(await ctx.call('player.nearby', { id: p.id, radius: 16 }), 'player.nearby');
      const c = new Check('player.nearby');
      if (d.held_item !== undefined && d.held_item !== null) c.ok(isObj(d.held_item) && isStr(d.held_item.id), 'held_item must be an item or null');
      const l = d.look;
      c.ok(isObj(l) && ['yaw', 'pitch', 'dx', 'dy', 'dz'].every((k) => isNum(l[k])), 'look must have yaw,pitch,dx,dy,dz numbers');
      if (isObj(l) && isNum(l.dx)) {
        const len = Math.hypot(num(l.dx), num(l.dy), num(l.dz));
        c.ok(Math.abs(len - 1) < 0.01, `look vector length ${len.toFixed(3)} is not unit`);
      }
      if (d.target !== null && d.target !== undefined) {
        const t = d.target;
        c.ok(isObj(t) && (t.kind === 'block' || t.kind === 'entity') && isStr(t.type), 'target must be {kind block|entity, type}');
        c.ok(isObj(t) && isObj(t.position) && isNum(t.position.x), 'target.position required');
        c.ok(isObj(t) && isNum(t.distance), 'target.distance required');
      }
      c.ok(Array.isArray(d.entities) || d.entities === undefined, 'entities must be an array');
      if (Array.isArray(d.entities)) {
        c.ok(d.entities.length <= 64, `entities has ${d.entities.length} entries (max 64)`);
        let prev = -1;
        for (const [i, e] of d.entities.entries()) {
          c.ok(isObj(e) && isStr(e.type) && isNum(e.distance), `entities[${i}] needs type and distance`);
          if (isObj(e) && isNum(e.distance)) {
            c.ok(num(e.distance) >= prev, 'entities must be nearest first');
            prev = num(e.distance);
          }
        }
      }
      c.ok(!('blocks' in d) || isObj(d.blocks), 'blocks must be absent or an object (only when blocks: true)');
      c.done();
      return `${Array.isArray(d.entities) ? d.entities.length : 0} entities`;
    },
  },
  {
    id: 'player.inventory.not-found',
    group: 'inventory',
    title: 'player.inventory for an unknown id is not_found',
    needs: ['op:player.inventory'],
    async run(ctx) {
      errCode(await ctx.call('player.inventory', { id: fakePlayerId(ctx.game) }), 'not_found', 'unknown player');
      return 'not_found';
    },
  },
  {
    id: 'player.inventory.schema',
    group: 'inventory',
    title: 'player.inventory containers: unique ids, only non-empty slots, int/name slot types, no duplicate slots',
    needs: ['op:player.inventory', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const d = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
      if (!Array.isArray(d.containers)) fail('containers must be an array');
      const errs = containersErrors(d.containers, { named: ctx.profile?.expect?.named_slots });
      if (errs.length) fail(errs.slice(0, 8).join('; '));
      const n = d.containers.reduce((a, c) => a + c.slots.length, 0);
      return `${d.containers.length} containers, ${n} items`;
    },
  },
  {
    id: 'player.inventory.digests',
    group: 'inventory',
    title: 'every item fingerprint recomputes exactly; hash recomputes where the record alone determines it',
    needs: ['op:player.inventory', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const d = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
      const acc = { errors: [], fp: 0, hash: 0, skippedHash: 0 };
      let items = 0;
      for (const c of d.containers) {
        for (const s of c.slots) {
          items += 1;
          const e = itemErrors(s.item, `${c.id}[${s.slot}]`);
          if (e.length) acc.errors.push(...e);
          else digestErrors(s.item, `${c.id}[${s.slot}]`, acc);
        }
      }
      if (acc.errors.length) fail(acc.errors.slice(0, 6).join('; '));
      if (items === 0) throw new Skip('player carries no items');
      return `${acc.fp} fingerprints, ${acc.hash} hashes recomputed, ${acc.skippedHash} hashes need MC components (format only)`;
    },
  },
  {
    id: 'item.get.matches-inventory',
    group: 'inventory',
    title: 'item.get returns the same fingerprint and hash as the inventory record (never truncated)',
    needs: ['op:item.get', 'op:player.inventory', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const inv = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
      let pick = null;
      for (const c of inv.containers) {
        for (const s of c.slots) {
          if (!subtreeTruncated(s.item)) {
            pick = { container: c.id, slot: s.slot, item: s.item };
            break;
          }
        }
        if (pick) break;
      }
      if (!pick) throw new Skip('no untruncated item to compare');
      const got = okData(await ctx.call('item.get', { id: p.id, container: pick.container, slot: pick.slot }), 'item.get');
      const rec = got.item;
      const errs = itemErrors(rec, 'item.get');
      if (errs.length) fail(errs.slice(0, 6).join('; '));
      if ('slot' in rec) fail('item.get record must not carry slot');
      if (rec.truncated === true) fail('item.get must never return truncated');
      if (rec.fingerprint !== pick.item.fingerprint) fail(`fingerprint differs: inventory ${pick.item.fingerprint.slice(0, 12)} vs item.get ${String(rec.fingerprint).slice(0, 12)}`);
      if (rec.hash !== pick.item.hash) fail(`hash differs: inventory ${pick.item.hash} vs item.get ${rec.hash}`);
      return `${pick.container}[${pick.slot}] matches`;
    },
  },
  {
    id: 'item.get.nested-path',
    group: 'inventory',
    title: 'item.get with path reaches a nested item (contents[0] of a container item)',
    needs: ['op:item.get', 'op:player.inventory', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const inv = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
      let pick = null;
      for (const c of inv.containers) {
        for (const s of c.slots) {
          if (Array.isArray(s.item.contents) && s.item.contents.length && !subtreeTruncated(s.item)) {
            pick = { container: c.id, slot: s.slot, nested: s.item.contents[0] };
            break;
          }
        }
        if (pick) break;
      }
      if (!pick) throw new Skip('no nested container item on this player');
      const got = okData(await ctx.call('item.get', { id: p.id, container: pick.container, slot: pick.slot, path: [0] }), 'item.get path [0]');
      if (got.item.fingerprint !== pick.nested.fingerprint) fail('nested item fingerprint differs from parent contents[0]');
      return 'nested item matches';
    },
  },
  {
    id: 'item.get.errors',
    group: 'inventory',
    title: 'item.get: unknown container is not_found; unknown player is not_found',
    needs: ['op:item.get'],
    async run(ctx) {
      errCode(await ctx.call('item.get', { id: fakePlayerId(ctx.game), container: 'inventory', slot: 0 }), 'not_found', 'unknown player');
      const p = await ctx.getPlayer();
      if (p) errCode(await ctx.call('item.get', { id: p.id, container: 'pm_no_such_container', slot: 0 }), 'not_found', 'unknown container');
      return p ? 'unknown player and container -> not_found' : 'unknown player -> not_found';
    },
  },
];


