// Operation dispatch: advertised ops answer, unadvertised/unknown ops are unsupported, argument
// validation returns invalid_args (operations.md sections 0-9).

import { Check, fail, Skip } from '../lib/check.mjs';
import { KNOWN_OPS, NEVER_OPS, EVENT_TYPES, fakePlayerId } from '../lib/protocol.mjs';
import { anyErr, errCode, describeResp } from './helpers.mjs';

const SUBSCRIBE_OPS = ['positions.subscribe', 'events.subscribe', 'chunks.subscribe'];

/** Minimal probe requests for advertised ops. Each uses a fresh connection where subscriptions are involved. */
function probeFor(op, ctx) {
  const game = ctx.game;
  const fake = fakePlayerId(game);
  switch (op) {
    case 'server.info':
      return { args: {} };
    case 'players.list':
      return { args: {} };
    case 'player.get':
      return { args: { id: fake } };
    case 'player.inventory':
      return { args: { id: fake } };
    case 'player.nearby':
      return { args: { id: fake } };
    case 'item.get':
      return { args: { id: fake, container: 'inventory', slot: 0 } };
    case 'positions.keyframe':
      return { args: { sub: 999999 } };
    case 'positions.unsubscribe':
      return { args: { sub: 999999 } };
    case 'events.unsubscribe':
      return { args: { sub: 999999 } };
    case 'chunks.unsubscribe':
      return { args: { sub: 999999 } };
    case 'map.chunks':
      return { args: { world: 'pm_conformance_no_such_world', chunks: [[0, 0]] } };
    case 'assets.manifest':
      return { args: { limit: 1 } };
    case 'assets.get':
      return { args: { keys: ['item:conformance:probe'] } };
    default:
      return null;
  }
}

export const tests = [
  {
    id: 'ops.advertised-not-unsupported',
    group: 'ops',
    title: 'every advertised op answers without unsupported',
    needs: ['hello'],
    async run(ctx) {
      const problems = [];
      const checked = [];
      for (const op of ctx.caps.ops) {
        if (op === 'hello' || op === 'action.run') continue; // action.run is tested in actions
        if (SUBSCRIBE_OPS.includes(op)) {
          const { client } = await ctx.open({ hello: true });
          const args = op === 'positions.subscribe' ? { interval_ms: 1000 } : op === 'chunks.subscribe' ? { world: 'pm_conformance_no_such_world' } : { types: [ctx.caps.events[0] ?? 'chat'] };
          const { resp, sub } = await client.subscribe(op, args);
          if (resp.ok === false && resp.error?.code === 'unsupported') problems.push(`${op}: unsupported`);
          else if (resp.ok === true && sub) {
            await client.request(op.replace('.subscribe', '.unsubscribe'), { sub: sub.sub });
          }
          checked.push(op);
          continue;
        }
        const probe = probeFor(op, ctx);
        if (!probe) continue;
        const resp = await ctx.call(op, probe.args);
        if (resp.ok === false && resp.error?.code === 'unsupported') problems.push(`${op}: unsupported`);
        checked.push(op);
      }
      if (problems.length) fail(problems.join('; '));
      return `${checked.length} advertised ops answered (${checked.join(', ')})`;
    },
  },
  {
    id: 'ops.unadvertised-unsupported',
    group: 'ops',
    title: 'known ops that are not advertised answer unsupported',
    needs: ['hello'],
    async run(ctx) {
      const missing = KNOWN_OPS.filter((op) => op !== 'hello' && !ctx.caps.ops.includes(op));
      if (missing.length === 0) throw new Skip('every known op is advertised');
      for (const op of missing) {
        const resp = await ctx.call(op, {});
        errCode(resp, 'unsupported', `unadvertised ${op}`);
      }
      return `${missing.length} unadvertised ops -> unsupported`;
    },
  },
  {
    id: 'ops.never-implemented-unsupported',
    group: 'ops',
    title: 'ops that do not exist in the contract (ping, worlds.list, servers.list, chat.subscribe, inventory.get) are unsupported (NOTES D3)',
    needs: ['hello'],
    async run(ctx) {
      for (const op of NEVER_OPS) {
        const resp = await ctx.call(op, {});
        errCode(resp, 'unsupported', op);
      }
      return `${NEVER_OPS.length} names -> unsupported`;
    },
  },
  {
    id: 'ops.unknown-unsupported',
    group: 'ops',
    title: 'an unknown op is unsupported and the connection stays usable',
    needs: ['hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      errCode(await client.request('pm.bogus.op', {}), 'unsupported', 'pm.bogus.op');
      const again = await client.request('server.info', {});
      if (again.ok !== true) fail(`server.info after unknown op: ${describeResp(again)}`);
      return 'unsupported, connection still usable';
    },
  },
  {
    id: 'args.player.get-missing-id',
    group: 'args',
    title: 'player.get without id is invalid_args',
    needs: ['op:player.get'],
    async run(ctx) {
      errCode(await ctx.call('player.get', {}), 'invalid_args', 'player.get {}');
      return 'invalid_args';
    },
  },
  {
    id: 'args.player.nearby-radius-bounds',
    group: 'args',
    title: 'player.nearby radius outside 1..32 is invalid_args (radius 33, radius 0)',
    needs: ['op:player.nearby'],
    async run(ctx) {
      const id = fakePlayerId(ctx.game);
      errCode(await ctx.call('player.nearby', { id, radius: 33 }), 'invalid_args', 'radius 33');
      errCode(await ctx.call('player.nearby', { id, radius: 0 }), 'invalid_args', 'radius 0');
      return 'radius 33 and 0 rejected';
    },
  },
  {
    id: 'args.player.get-unknown-field',
    group: 'args',
    title: 'player.get with an unknown field name is invalid_args',
    needs: ['op:player.get'],
    async run(ctx) {
      errCode(await ctx.call('player.get', { id: fakePlayerId(ctx.game), fields: ['pm_no_such_field'] }), 'invalid_args', 'unknown field');
      return 'invalid_args';
    },
  },
  {
    id: 'args.item.get-slot-forms',
    group: 'args',
    title: 'item.get rejects the "hotbar:0" slot form and non-slot types (D4)',
    needs: ['op:item.get'],
    async run(ctx) {
      const id = fakePlayerId(ctx.game);
      errCode(await ctx.call('item.get', { id, container: 'inventory', slot: 'hotbar:0' }), 'invalid_args', 'slot hotbar:0');
      errCode(await ctx.call('item.get', { id, container: 'inventory', slot: true }), 'invalid_args', 'slot true');
      return 'hotbar:0 and bool slot rejected';
    },
  },
  {
    id: 'args.events.subscribe-types',
    group: 'args',
    title: 'events.subscribe: empty, non-array or non-string types are invalid_args',
    needs: ['op:events.subscribe'],
    async run(ctx) {
      for (const types of [[], 'chat', ['chat', 5]]) {
        const { client } = await ctx.open({ hello: true });
        errCode(await client.request('events.subscribe', { types }), 'invalid_args', `types ${JSON.stringify(types)}`);
      }
      return 'three malformed type lists rejected';
    },
  },
  {
    id: 'args.events.subscribe-unknown-type',
    group: 'args',
    title: 'events.subscribe with a type outside the contract is rejected (invalid_args or unsupported)',
    needs: ['op:events.subscribe'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const resp = await client.request('events.subscribe', { types: ['pm_bogus_event'] });
      if (resp.ok === true) fail('unknown event type was accepted');
      anyErr(resp, 'bogus event type');
      if (!['invalid_args', 'unsupported'].includes(resp.error.code)) fail(`got ${resp.error.code}`);
      return resp.error.code;
    },
  },
  {
    id: 'args.positions.subscribe-interval',
    group: 'args',
    title: 'positions.subscribe needs an integer interval_ms (missing or string is invalid_args)',
    needs: ['op:positions.subscribe'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      errCode(await client.request('positions.subscribe', {}), 'invalid_args', 'missing interval_ms');
      errCode(await client.request('positions.subscribe', { interval_ms: 'fast' }), 'invalid_args', 'string interval_ms');
      return 'missing and string interval rejected';
    },
  },
  {
    id: 'args.assets.get-max-keys',
    group: 'args',
    title: 'assets.get with 65 keys is invalid_args (max 64)',
    needs: ['op:assets.get'],
    async run(ctx) {
      const keys = Array.from({ length: 65 }, (_, i) => `item:conformance:k${i}`);
      errCode(await ctx.call('assets.get', { keys }), 'invalid_args', '65 keys');
      return '65 keys rejected';
    },
  },
  {
    id: 'args.assets.manifest-limit',
    group: 'args',
    title: 'assets.manifest limit above 5000 is invalid_args (or clamped to 5000 entries)',
    needs: ['op:assets.manifest'],
    async run(ctx) {
      const resp = await ctx.call('assets.manifest', { limit: 5001 });
      if (resp.ok === true) {
        const n = resp.data?.entries?.length ?? 0;
        if (n > 5000) fail(`limit 5001 returned ${n} entries`);
        return `clamped (${n} entries)`;
      }
      errCode(resp, 'invalid_args', 'limit 5001');
      return 'invalid_args';
    },
  },
  {
    id: 'args.server-info-unknown-args-ignored',
    group: 'args',
    title: 'unknown args are ignored (forward compatibility)',
    needs: ['hello'],
    async run(ctx) {
      const resp = await ctx.call('server.info', { pm_future_arg: 1 });
      if (resp.ok !== true) fail(`server.info with unknown arg: ${describeResp(resp)}`);
      return 'ignored';
    },
  },
];

export { EVENT_TYPES, Check };
