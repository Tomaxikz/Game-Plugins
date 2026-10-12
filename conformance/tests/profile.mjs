// Profile checks (conformance/profiles/*.json). Tests whose check the active profile does not
// define are skipped. profile.platform, required-ops, required-features and
// required-player-fields apply to every profile.

import { Check, fail, isObj, isStr, isIntV, isNum } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { NAMESPACED, UUID_LOWER, MC_EQUIPMENT_SLOTS } from '../lib/protocol.mjs';
import { containersErrors, gameSectionErrors } from '../lib/schema.mjs';
import { okData } from './helpers.mjs';

const WORLD_ENV = ['normal', 'nether', 'the_end', 'custom'];
const DIM_RE = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;

function worldPathErrors(w, c, where) {
  if (typeof w !== 'string') return;
  c.ok(!w.startsWith('/') && !w.endsWith('/'), `${where}: world ${w} must not start or end with /`);
  c.ok(!w.split('/').includes('..'), `${where}: world ${w} must not contain ..`);
}

async function firstStreamPlayers(ctx) {
  const { client } = await ctx.open({ hello: true });
  const { sub, resp } = await client.subscribe('positions.subscribe', { interval_ms: 250 });
  okData(resp, 'positions.subscribe');
  const f = await sub.next(3000);
  client.close();
  return f.data?.players ?? [];
}

function expectOf(ctx) {
  return ctx.profile?.expect ?? {};
}

export const tests = [
  {
    id: 'profile.platform',
    group: 'profile',
    title: 'hello.game and hello.platform match the active profile',
    needs: ['hello'],
    run(ctx) {
      const p = ctx.profile;
      const c = new Check('profile');
      if (p.game) c.ok(ctx.hello.game === p.game, `game ${ctx.hello.game} != profile game ${p.game}`);
      if (p.platforms) c.ok(p.platforms.includes(ctx.hello.platform), `platform ${ctx.hello.platform} not in ${p.platforms.join('|')}`);
      c.done();
      return `profile ${p.name} (${ctx.hello.game}/${ctx.hello.platform})`;
    },
  },
  {
    id: 'profile.required-ops',
    group: 'profile',
    title: 'profile required ops are advertised and forbidden ops are not',
    needs: ['hello'],
    run(ctx) {
      const p = ctx.profile;
      const ops = ctx.caps.ops;
      const c = new Check('profile ops');
      for (const op of p.required_ops ?? []) c.ok(ops.includes(op), `required op ${op} not advertised`);
      for (const op of p.forbidden_ops ?? []) c.ok(!ops.includes(op), `op ${op} must not be advertised on ${p.name}`);
      c.done();
      return `${(p.required_ops ?? []).length} required, ${(p.forbidden_ops ?? []).length} forbidden checked`;
    },
  },
  {
    id: 'profile.required-features',
    group: 'profile',
    title: 'profile required features are advertised and forbidden features are not',
    needs: ['hello'],
    run(ctx) {
      const p = ctx.profile;
      const f = ctx.features();
      const c = new Check('profile features');
      for (const x of p.required_features ?? []) c.ok(f.includes(x), `required feature ${x} not advertised`);
      for (const x of p.forbidden_features ?? []) c.ok(!f.includes(x), `feature ${x} must not be advertised on ${p.name}`);
      c.done();
      return 'features match profile';
    },
  },
  {
    id: 'profile.required-player-fields',
    group: 'profile',
    title: 'profile required player_fields are advertised',
    needs: ['hello'],
    run(ctx) {
      const missing = (ctx.profile.required_player_fields ?? []).filter((f) => !ctx.caps.player_fields.includes(f));
      if (missing.length) fail(`player_fields missing: ${missing.join(', ')}`);
      return 'player_fields match profile';
    },
  },
  {
    id: 'profile.mc.containers',
    group: 'profile',
    title: 'Minecraft containers: inventory 36, equipment 5 named slots, ender_chest 27 (docs/protocol/games/minecraft.md section 5)',
    needs: ['profile:mc.containers', 'op:player.inventory', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const d = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
      const want = expectOf(ctx).containers ?? {};
      const c = new Check('mc containers');
      const byId = new Map(d.containers.map((x) => [x.id, x]));
      for (const id of ctx.profile.required_containers ?? []) c.ok(byId.has(id), `container ${id} missing`);
      for (const [id, spec] of Object.entries(want)) {
        const got = byId.get(id);
        if (!got) continue;
        c.ok(got.size === spec.size, `${id}.size is ${got.size}, expected ${spec.size}`);
      }
      const eq = byId.get('equipment');
      if (eq) {
        for (const s of eq.slots) {
          if (typeof s.slot === 'string') c.ok(MC_EQUIPMENT_SLOTS.includes(s.slot), `equipment slot ${s.slot} is not one of head,chest,legs,feet,offhand`);
          else c.ok(false, `equipment slot ${s.slot} must be a name, not an int`);
        }
      }
      const inv = byId.get('inventory');
      if (inv) for (const s of inv.slots) c.ok(typeof s.slot === 'number' && s.slot < 36, `inventory slot ${s.slot} must be an int 0..35`);
      const errs = containersErrors(d.containers, { named: { equipment: MC_EQUIPMENT_SLOTS } });
      for (const e of errs) c.errs.push(e);
      c.done();
      return `${[...byId.keys()].join(', ')}`;
    },
  },
  {
    id: 'profile.mc.world',
    group: 'profile',
    title: 'Minecraft world/dimension: folder paths relative to server root, namespaced dimension, environment vocabulary',
    needs: ['profile:mc.world', 'hello'],
    async run(ctx) {
      const c = new Check('mc world');
      const info = okData(await ctx.call('server.info', {}), 'server.info');
      for (const [i, w] of info.worlds.entries()) {
        worldPathErrors(w.world, c, `worlds[${i}]`);
        if (w.dimension !== undefined) c.ok(DIM_RE.test(w.dimension), `worlds[${i}].dimension ${w.dimension} is not namespaced`);
        if (w.environment !== undefined) c.ok(WORLD_ENV.includes(w.environment), `worlds[${i}].environment ${w.environment} not in vocabulary`);
      }
      if (ctx.has('players.list')) {
        const pl = okData(await ctx.call('players.list', {}), 'players.list');
        for (const p of pl.players) {
          if (p.dimension !== undefined) c.ok(DIM_RE.test(p.dimension), `${p.name} dimension ${p.dimension} is not namespaced`);
          if (p.world !== undefined) worldPathErrors(p.world, c, p.name);
        }
      }
      const p = await ctx.getPlayer();
      if (p && ctx.has('player.get')) {
        const d = okData(await ctx.call('player.get', { id: p.id, fields: ['position'] }), 'player.get position');
        if (d.dimension !== undefined) c.ok(DIM_RE.test(d.dimension), `player dimension ${d.dimension} is not namespaced`);
        if (d.world !== undefined) worldPathErrors(d.world, c, 'player');
      }
      c.done();
      return `${info.worlds.length} worlds checked`;
    },
  },
  {
    id: 'profile.mc.version-range',
    group: 'profile',
    title: 'Minecraft universal jar range is >= 1.12 (releases.md, NOTES D19)',
    needs: ['profile:mc.version', 'hello'],
    run(ctx) {
      const v = String(ctx.hello.game_version ?? '');
      const [major, minor] = v.split('.').map((x) => Number(x));
      if (!Number.isInteger(major)) fail(`game_version ${v} is not a version string`);
      if (major === 1 && (minor ?? 0) < 12) fail(`game_version ${v} is below 1.12`);
      return `game_version ${v}`;
    },
  },
  {
    id: 'profile.mc.uuid',
    group: 'profile',
    title: 'Minecraft player ids are lowercase hyphenated UUID strings',
    needs: ['profile:mc.uuid', 'op:players.list'],
    async run(ctx) {
      const d = okData(await ctx.call('players.list', { include_offline: true }), 'players.list');
      const bad = d.players.filter((p) => !UUID_LOWER.test(p.id)).map((p) => p.id);
      if (bad.length) fail(`non-UUID ids: ${bad.slice(0, 3).join(', ')}`);
      return `${d.players.length} UUID ids`;
    },
  },
  {
    id: 'profile.mc.items',
    group: 'profile',
    title: 'Minecraft item ids are namespace:path (legacy:<MATERIAL> allowed for unknown legacy names)',
    needs: ['profile:mc.items', 'op:player.inventory', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const d = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
      const c = new Check('mc items');
      let n = 0;
      const walk = (item, where) => {
        n += 1;
        c.ok(NAMESPACED.test(item.id) || item.id.startsWith('legacy:'), `${where} id ${item.id} is not namespaced`);
        if (item.encoding !== undefined) c.ok(['components', 'nbt', 'bukkit_meta'].includes(item.encoding), `${where} encoding ${item.encoding}`);
        if (item.encoding === 'nbt') c.ok(isObj(item.components) && isStr(item.components.snbt), `${where} nbt encoding needs components.snbt`);
        if (item.encoding === 'bukkit_meta') c.ok(!('components' in item), `${where} bukkit_meta must not carry components`);
        for (const [i, ch] of (item.contents ?? []).entries()) walk(ch, `${where}.contents[${i}]`);
      };
      for (const cont of d.containers) for (const s of cont.slots) walk(s.item, `${cont.id}[${s.slot}]`);
      c.done();
      return `${n} items checked`;
    },
  },
  {
    id: 'profile.mc.player-game',
    group: 'profile',
    title: 'Minecraft player.get game object: typed keys from games/minecraft.md section 4',
    needs: ['profile:mc.player_game', 'op:player.get', 'player'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const d = okData(await ctx.call('player.get', { id: p.id }), 'player.get');
      const g = d.game ?? {};
      const c = new Check('mc game');
      if (g.offline_uuid !== undefined) c.ok(typeof g.offline_uuid === 'boolean', 'offline_uuid must be a bool');
      for (const k of ['flying', 'may_fly', 'sneaking', 'sprinting', 'sleeping', 'on_ground', 'op']) {
        if (g[k] !== undefined) c.ok(typeof g[k] === 'boolean', `${k} must be a bool`);
      }
      for (const k of ['air_ticks', 'max_air_ticks', 'fire_ticks', 'score', 'selected_slot', 'first_played', 'last_login']) {
        if (g[k] !== undefined) c.ok(isIntV(g[k]), `${k} must be an int`);
      }
      if (Array.isArray(g.effects)) {
        for (const [i, e] of g.effects.entries()) {
          c.ok(isObj(e) && NAMESPACED.test(e.id), `effects[${i}].id must be namespaced`);
          c.ok(isObj(e) && isIntV(e.amplifier) && isIntV(e.duration_ticks), `effects[${i}] needs int amplifier and duration_ticks`);
        }
      } else if (g.effects !== undefined) c.ok(false, 'effects must be an array');
      if (g.groups !== undefined) c.ok(Array.isArray(g.groups) && g.groups.every(isStr), 'groups must be string[]');
      if (isObj(g.advancements)) {
        for (const [k, a] of Object.entries(g.advancements)) {
          c.ok(NAMESPACED.test(k), `advancement key ${k} must be namespaced`);
          c.ok(isObj(a) && typeof a.done === 'boolean', `advancement ${k} needs done bool`);
          if (isObj(a) && isObj(a.criteria)) {
            for (const [ck, t] of Object.entries(a.criteria)) c.ok(isStr(t), `criterion ${k}/${ck} time must be a string`);
          }
        }
      }
      if (d.experience !== undefined && d.experience !== null) c.ok(isObj(d.experience) && isNum(d.experience.progress), 'experience must be {level,progress,total}');
      for (const err of gameSectionErrors(g, { mc: true })) c.errs.push(err);
      c.done();
      return 'game object typed';
    },
  },
  {
    id: 'profile.mc.proxy',
    group: 'profile',
    title: 'Minecraft proxy: feature proxy, worlds [], backends list, no world-level ops, players.list server key',
    needs: ['profile:mc.proxy', 'hello'],
    async run(ctx) {
      const c = new Check('proxy');
      c.ok(ctx.features().includes('proxy'), 'feature proxy must be advertised');
      const info = okData(await ctx.call('server.info', {}), 'server.info');
      c.ok(Array.isArray(info.worlds) && info.worlds.length === 0, 'proxy server.info.worlds must be []');
      const backends = info.game?.backends;
      c.ok(Array.isArray(backends), 'server.info.game.backends must be an array');
      for (const [i, b] of (backends ?? []).entries()) {
        c.ok(isObj(b) && isStr(b.name) && isStr(b.address) && isIntV(b.players), `backends[${i}] needs name, address (string) and players`);
      }
      c.ok(typeof ctx.hello.game === 'string', 'hello.game must be the game id string (NOTES D18)');
      const hb = ctx.hello.game_info?.backends;
      c.ok(Array.isArray(hb), 'hello.game_info.backends must be an array');
      for (const [i, b] of (hb ?? []).entries()) c.ok(isObj(b) && isStr(b.name) && isStr(b.address) && isIntV(b.players), `hello.game_info.backends[${i}] needs name, address and players`);
      if (ctx.hello.game_info?.protocol_range !== undefined) {
        const pr = ctx.hello.game_info.protocol_range;
        c.ok(isObj(pr) && isIntV(pr.min) && isIntV(pr.max), 'protocol_range must be {min,max}');
      }
      const pl = okData(await ctx.call('players.list', {}), 'players.list');
      for (const p of pl.players) {
        if (p.online === true) c.ok('server' in p, `${p.name} online without server key (null while connecting is fine)`);
      }
      c.done();
      return `${(backends ?? []).length} backends`;
    },
  },
  {
    id: 'profile.rust.steamid',
    group: 'profile',
    title: 'Rust: SteamID64 string ids, no dimension key, server.info.game keys present (rust.md sections 2-3)',
    needs: ['profile:rust.steamid', 'hello'],
    async run(ctx) {
      const c = new Check('rust');
      const pl = okData(await ctx.call('players.list', { include_offline: true }), 'players.list');
      for (const p of pl.players) {
        c.ok(typeof p.id === 'string' && /^7656119\d{10}$/.test(p.id), `${p.name} id ${p.id} is not a SteamID64 string`);
        c.ok(!('dimension' in p), `${p.name} must not carry dimension`);
      }
      const info = okData(await ctx.call('server.info', {}), 'server.info');
      for (const w of info.worlds) c.ok(!('dimension' in w), 'rust worlds must not carry dimension');
      for (const p of pl.players) {
        const sleeping = Array.isArray(p.groups) && p.groups.includes('sleeping');
        if (sleeping) c.ok(p.online === false, `${p.name} is sleeping but listed online`);
      }
      if (ctx.has('positions.subscribe')) {
        for (const e of await firstStreamPlayers(ctx)) {
          c.ok(!('yaw' in e) && !('pitch' in e), `rust stream entry ${e.name} must omit yaw and pitch`);
        }
      }
      const g = info.game ?? {};
      const req = { hostname: 'string', description: 'string', url: 'string', header_image: 'string', map_name: 'string', world_size: 'int', seed: 'int', entity_count: 'int', fps: 'num', save_interval: 'int', backpacks_plugin: 'bool', roles: 'array', bans: 'array' };
      for (const r of g.roles ?? []) c.ok(isObj(r) && isStr(r.id) && ['owner', 'admin', 'moderator'].includes(r.role), 'roles[] needs id and role owner|admin|moderator');
      for (const b of g.bans ?? []) c.ok(isObj(b) && isStr(b.id) && isStr(b.reason), 'bans[] needs id and reason string');
      for (const [k, t] of Object.entries(req)) {
        const v = g[k];
        const ok = t === 'string' ? isStr(v) : t === 'int' ? isIntV(v) : t === 'num' ? isNum(v) : t === 'bool' ? typeof v === 'boolean' : Array.isArray(v);
        c.ok(ok, `server.info.game.${k} missing or wrong type`);
      }
      const exp = expectOf(ctx).containers ?? {};
      if (ctx.has('player.inventory') && (await ctx.getPlayer())) {
        const p = await ctx.getPlayer();
        const inv = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
        for (const cont of inv.containers) {
          if (exp[cont.id]) c.ok(cont.size === exp[cont.id].size, `${cont.id}.size ${cont.size} != ${exp[cont.id].size}`);
          c.ok(['main', 'belt', 'wear', 'backpack'].includes(cont.id), `unexpected Rust container ${cont.id}`);
        }
        const pg = okData(await ctx.call('player.get', { id: p.id, fields: ['position'] }), 'player.get');
        c.ok(!('dimension' in pg), 'rust player.get must not carry dimension');
        if (pg.position) c.ok(!('yaw' in pg.position) && !('pitch' in pg.position), 'rust position must omit yaw/pitch (rust.md section 2)');
      }
      c.done();
      return `${pl.players.length} SteamID players checked`;
    },
  },
  {
    id: 'profile.rust.assets',
    group: 'profile',
    title: 'Rust exposes no art: manifest empty, assets.get unavailable/no_art_on_server',
    needs: ['profile:rust.assets', 'op:assets.manifest'],
    async run(ctx) {
      const d = okData(await ctx.call('assets.manifest', {}), 'assets.manifest');
      if ((d.entries ?? []).length) fail(`${d.entries.length} entries; Rust must expose no assets`);
      if (ctx.has('assets.get')) {
        const g = okData(await ctx.call('assets.get', { keys: ['item:rifle.ak'] }), 'assets.get');
        const a = g.assets[0];
        if (a.render !== 'unavailable' || a.unavailable_reason !== 'no_art_on_server') fail(`expected unavailable/no_art_on_server, got ${a.render}/${a.unavailable_reason}`);
      }
      return 'no assets';
    },
  },
  {
    id: 'profile.fivem.identity',
    group: 'profile',
    title: 'FiveM: license: identifiers, game.server_id 1..65535 for online players, world gta5|rdr3, no dimension',
    needs: ['profile:fivem.identity', 'hello'],
    async run(ctx) {
      const c = new Check('fivem');
      const world = ctx.hello.platform === 'redm' ? 'rdr3' : 'gta5';
      const pl = okData(await ctx.call('players.list', { include_offline: true }), 'players.list');
      for (const p of pl.players) {
        c.ok(typeof p.id === 'string' && /^license:[0-9a-f]+$/i.test(p.id), `${p.name} id ${p.id} is not license:<hex>`);
        c.ok(!('dimension' in p), `${p.name} must not carry dimension`);
        if (p.online === true) {
          const sid = p.game?.server_id;
          c.ok(isIntV(sid) && num(sid) >= 1 && num(sid) <= 65535, `${p.name} game.server_id must be 1..65535`);
        }
      }
      const info = okData(await ctx.call('server.info', {}), 'server.info');
      for (const w of info.worlds) c.ok(w.world === world, `world ${w.world} != ${world}`);
      const p = await ctx.getPlayer();
      if (p) {
        const d = okData(await ctx.call('player.get', { id: p.id, fields: ['position'] }), 'player.get');
        c.ok(d.world === world, `player world ${d.world} != ${world}`);
        c.ok(!('dimension' in d), 'FiveM player.get must not carry dimension');
        if (d.position) c.ok(!('pitch' in d.position), 'FiveM stream/position omits pitch');
        const inv = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
        c.ok(inv.containers.length === 1 && inv.containers[0].id === 'main', `FiveM exposes one container main, got ${inv.containers.map((x) => x.id).join(',')}`);
      }
      if (ctx.has('positions.subscribe')) {
        for (const e of await firstStreamPlayers(ctx)) {
          c.ok(!('pitch' in e), `FiveM stream entry ${e.name} must omit pitch`);
          c.ok(isIntV(e.yaw), `FiveM stream entry ${e.name} yaw must be an int (heading x 100)`);
        }
      }
      c.done();
      return `world ${world}`;
    },
  },
  {
    id: 'profile.hytale.world',
    group: 'profile',
    title: 'Hytale: plain world-name strings, no dimension key, container sizes come from the build (not fixed)',
    needs: ['profile:hytale.world', 'hello'],
    async run(ctx) {
      const c = new Check('hytale');
      const info = okData(await ctx.call('server.info', {}), 'server.info');
      for (const w of info.worlds) {
        c.ok(isStr(w.world), 'world must be a plain string');
        c.ok(!('dimension' in w), 'hytale worlds must not carry dimension');
        for (const k of Object.keys(w)) c.ok(['world', 'name', 'loaded', 'players'].includes(k), `hytale worlds[] key ${k} is not in {world,name,loaded,players}`);
      }
      const p = await ctx.getPlayer();
      if (p) {
        const d = okData(await ctx.call('player.get', { id: p.id, fields: ['position'] }), 'player.get');
        c.ok(!('dimension' in d), 'hytale player.get must not carry dimension');
        for (const k of Object.keys(d.game ?? {})) c.ok(['stats', 'permissions', 'groups', 'equipment'].includes(k), `hytale game key ${k} is not stats|permissions|groups|equipment`);
        const inv = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
        c.ok(inv.containers.every((x) => isStr(x.id) && isIntV(x.size)), 'container ids and sizes must be present');
      }
      c.done();
      return 'hytale world rules';
    },
  },
];

