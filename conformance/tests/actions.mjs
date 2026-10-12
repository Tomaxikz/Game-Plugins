// action.run (operations.md section 9). Only validation probes and, with --allow-actions, an
// expect-mismatch probe that must answer conflict and change nothing. No state-changing action
// is ever executed by this suite.

import { Skip, fail, isStr } from '../lib/check.mjs';
import { fakePlayerId } from '../lib/protocol.mjs';
import { okData, errCode, describeResp } from './helpers.mjs';

const INVENTORY_EDIT = ['remove_item_live', 'replace_item_live', 'set_item_live', 'move_item_live', 'remove_item', 'set_item_count', 'move_item'];

export const tests = [
  {
    id: 'action.run.missing-action',
    group: 'actions',
    title: 'action.run without an action id is invalid_args',
    needs: ['op:action.run'],
    async run(ctx) {
      errCode(await ctx.call('action.run', {}), 'invalid_args', 'action.run {}');
      return 'invalid_args';
    },
  },
  {
    id: 'action.run.not-advertised',
    group: 'actions',
    title: 'action.run with an action not in capabilities.actions is unsupported',
    needs: ['op:action.run'],
    async run(ctx) {
      errCode(await ctx.call('action.run', { action: 'pm_conformance_nothing', target: fakePlayerId(ctx.game) }), 'unsupported', 'unknown action');
      return 'unsupported';
    },
  },
  {
    id: 'action.run.teleport_player-unsupported',
    group: 'actions',
    title: 'teleport_player is never accepted (unsupported, NOTES D5)',
    needs: ['op:action.run'],
    async run(ctx) {
      errCode(await ctx.call('action.run', { action: 'teleport_player', target: fakePlayerId(ctx.game), args: { destination: fakePlayerId(ctx.game) } }), 'unsupported', 'teleport_player');
      return 'unsupported';
    },
  },
  {
    id: 'action.run.target-injection',
    group: 'actions',
    title: 'selector and newline targets are rejected with invalid_args (no command execution)',
    needs: ['op:action.run', 'hello'],
    async run(ctx) {
      const act = ctx.caps.actions.find((a) => a.target === true);
      if (!act) throw new Skip('no targeted action advertised');
      for (const target of ['@a', 'x\ny', 'Notch; op *']) {
        errCode(await ctx.call('action.run', { action: act.id, target }), 'invalid_args', `target ${JSON.stringify(target)}`);
      }
      return `${act.id}: selectors and fragments rejected`;
    },
  },
  {
    id: 'action.run.bad-args',
    group: 'actions',
    title: 'an advertised action with a value of the wrong kind is invalid_args (rejected before execution)',
    needs: ['player', 'op:action.run'],
    async run(ctx) {
      const p = await ctx.getPlayer();
      const act = ctx.caps.actions.find((a) => a.fields.some((f) => f.kind === 'integer' || f.kind === 'number'));
      if (!act) throw new Skip('no action with a numeric field advertised');
      const field = act.fields.find((f) => f.kind === 'integer' || f.kind === 'number');
      const req = { action: act.id, args: { [field.key]: 'pm-not-a-number' } };
      if (act.target) req.target = p.id;
      errCode(await ctx.call('action.run', req), 'invalid_args', `${act.id}.${field.key}`);
      return `${act.id}.${field.key} rejected`;
    },
  },
  {
    id: 'action.run.expect-conflict',
    group: 'actions',
    title: '(--allow-actions) an expect that does not match the slot returns conflict and nothing changes',
    needs: ['actions', 'player', 'op:action.run', 'op:player.inventory'],
    async run(ctx) {
      const act = ctx.caps.actions.find((a) => INVENTORY_EDIT.includes(a.id));
      if (!act) throw new Skip('no inventory-editing action advertised');
      const p = await ctx.getPlayer();
      const inv = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory');
      let cell = null;
      for (const c of inv.containers) {
        const s = c.slots.find((x) => typeof x.slot === 'number' && x.item && !x.item.truncated);
        if (s) {
          cell = { container: c.id, slot: s.slot, item: s.item };
          break;
        }
      }
      if (!cell) throw new Skip('player has no item to probe');
      const count = Number(cell.item.count) + 999;
      const base = { action: act.id, target: p.id, args: { container: cell.container, slot: cell.slot, expected_id: cell.item.id, amount: 1 } };
      const bad1 = await ctx.call('action.run', { ...base, expect: { container: cell.container, slot: cell.slot, id: cell.item.id, count } });
      errCode(bad1, 'conflict', 'count mismatch');
      const bad2 = await ctx.call('action.run', { ...base, expect: { container: cell.container, slot: cell.slot, fingerprint: '0'.repeat(64) } });
      errCode(bad2, 'conflict', 'fingerprint mismatch');
      const after = okData(await ctx.call('player.inventory', { id: p.id }), 'player.inventory after probe');
      const now = after.containers.find((c) => c.id === cell.container)?.slots.find((x) => x.slot === cell.slot)?.item;
      if (!now || now.count !== cell.item.count || now.fingerprint !== cell.item.fingerprint) fail('slot changed despite conflict');
      return `${act.id}: conflict on count and fingerprint, slot unchanged`;
    },
  },
];

export { describeResp, isStr };
