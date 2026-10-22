// move_item / move_item_live onto an occupied slot (NOTES D24, minecraft.md section 7).
//
// move.advertised only reads the ActionSpec. Every other test here MODIFIES the online player's
// inventory and therefore needs --allow-actions, set_item_live + remove_item_live + the move
// action. They use two free inventory slots (highest numbers first, so normally 35 and 34),
// fill them with dirt/stone, and clear them again in a finally block. The equipment tests
// leave the head slot as they found it (a helmet already there is swapped back).

import { Skip, CheckFailed, isObj } from '../lib/check.mjs';
import { okData, errCode, describeResp } from './helpers.mjs';

const AIR = 'minecraft:air';
const INV = 'inventory';
const ZERO_FP = '0'.repeat(64);

const FIELD_KINDS = {
  move_item_live: {
    container: ['text', 'identifier', 'select'],
    slot: ['text', 'integer', 'identifier'],
    destination_container: ['text', 'identifier', 'select'],
    destination_slot: ['text', 'integer', 'identifier'],
    expected_id: ['identifier', 'text'],
    destination_expected_id: ['identifier', 'text'],
  },
  move_item: {
    container: ['text', 'identifier', 'select'],
    slot: ['text', 'integer', 'identifier'],
    expected_id: ['identifier', 'text'],
    expected_count: ['integer'],
    expected_fingerprint: ['text', 'identifier'],
    destination_slot: ['text', 'integer', 'identifier'],
    destination_expected_id: ['identifier', 'text'],
    destination_expected_count: ['integer'],
    destination_expected_fingerprint: ['text', 'identifier'],
  },
};

const NEEDS = ['actions', 'player', 'op:action.run', 'op:player.inventory', 'op:item.get'];

function spec(ctx, id) {
  return (ctx.caps?.actions ?? []).find((a) => a?.id === id) ?? null;
}

/** Test harness around one player: two scratch slots, read-back and cleanup. */
class Rig {
  constructor(ctx, player, variant) {
    this.ctx = ctx;
    this.p = player;
    this.variant = variant;
    this.touched = []; // [container, slot] pairs to clear at the end
    this.restore = []; // async functions run (in order) after clearing
    this.A = null;
    this.B = null;
  }

  static async open(ctx, variant) {
    const set = spec(ctx, 'set_item_live');
    const rm = spec(ctx, 'remove_item_live');
    const mv = spec(ctx, variant);
    if (!set) throw new Skip('set_item_live not advertised');
    if (!rm) throw new Skip('remove_item_live not advertised (needed to clear the scratch slots)');
    if (!mv) throw new Skip(`${variant} not advertised`);
    const p = await ctx.getPlayer();
    const rig = new Rig(ctx, p, variant);
    await rig.pickSlots();
    return rig;
  }

  async pickSlots() {
    const inv = okData(await this.ctx.call('player.inventory', { id: this.p.id }), 'player.inventory');
    const c = inv.containers.find((x) => x.id === INV);
    if (!c) throw new Skip('no inventory container');
    const size = Number.isInteger(c.size) ? c.size : 36;
    const used = new Set(c.slots.map((s) => s.slot));
    const free = [];
    for (let s = Math.min(size, 36) - 1; s >= 0 && free.length < 2; s--) if (!used.has(s)) free.push(s);
    if (free.length < 2) throw new Skip('player has fewer than two free inventory slots');
    [this.A, this.B] = free;
    this.touched.push([INV, this.A], [INV, this.B]);
  }

  run(action, args) {
    return this.ctx.call('action.run', { action, target: this.p.id, args });
  }

  /** The item in a slot ({id,count,fingerprint}) or null when empty. */
  async get(container, slot) {
    const r = await this.ctx.call('item.get', { id: this.p.id, container, slot });
    if (r?.ok === false && r.error?.code === 'not_found') return null;
    const d = okData(r, `item.get ${container}/${slot}`);
    if (!isObj(d.item)) throw new CheckFailed(`item.get ${container}/${slot}: no item object`);
    return d.item;
  }

  async clear(container, slot) {
    const it = await this.get(container, slot);
    if (!it) return;
    const d = okData(await this.run('remove_item_live', { container, slot, expected_id: it.id }), `remove_item_live ${container}/${slot}`);
    if (d.ok === false) throw new CheckFailed(`remove_item_live ${container}/${slot} refused: ${d.message ?? ''}`);
  }

  /** Puts `count` of `id` into an inventory slot (clearing it first). Returns the stored item. */
  async put(slot, id, count) {
    await this.clear(INV, slot);
    const d = okData(await this.run('set_item_live', { container: INV, slot, expected_id: AIR, item: { id, count } }), `set_item_live ${slot}`);
    if (d.ok === false) throw new CheckFailed(`set_item_live ${id} x${count} refused: ${d.message ?? ''}`);
    const it = await this.get(INV, slot);
    if (!it || it.id !== id || it.count !== count) {
      throw new CheckFailed(`fixture: slot ${slot} should hold ${id} x${count}, has ${it ? `${it.id} x${it.count}` : 'nothing'}`);
    }
    return it;
  }

  /** Runs the move action under test. src/dst are [container, slot]; extra are the expectation args. */
  move(src, dst, extra) {
    if (this.variant === 'move_item_live') {
      return this.run('move_item_live', { container: src[0], slot: src[1], destination_container: dst[0], destination_slot: dst[1], ...extra });
    }
    if (src[0] !== dst[0]) throw new Skip('move_item works inside one container only');
    return this.run('move_item', { container: src[0], slot: src[1], destination_slot: dst[1], ...extra });
  }

  /** Source-side expectation args for an item record. */
  src(it) {
    if (this.variant === 'move_item_live') return { expected_id: it.id };
    return { expected_id: it.id, expected_count: it.count, expected_fingerprint: it.fingerprint };
  }

  /** Destination-side expectation args for an item record (null = empty). */
  dst(it) {
    if (this.variant === 'move_item_live') return { destination_expected_id: it ? it.id : AIR };
    if (!it) return { destination_expected_id: AIR, destination_expected_fingerprint: 'none' };
    return { destination_expected_id: it.id, destination_expected_count: it.count, destination_expected_fingerprint: it.fingerprint };
  }

  async expectSlot(container, slot, id, count, what) {
    const it = await this.get(container, slot);
    if (id === null) {
      if (it) throw new CheckFailed(`${what}: ${container}/${slot} should be empty, holds ${it.id} x${it.count}`);
      return;
    }
    if (!it) throw new CheckFailed(`${what}: ${container}/${slot} should hold ${id} x${count}, is empty`);
    if (it.id !== id || it.count !== count) throw new CheckFailed(`${what}: ${container}/${slot} should hold ${id} x${count}, holds ${it.id} x${it.count}`);
  }

  async snapshot(cells) {
    const out = [];
    for (const [c, s] of cells) {
      const it = await this.get(c, s);
      out.push(it ? `${it.id}|${it.count}|${it.fingerprint}` : 'empty');
    }
    return out;
  }

  async expectUnchanged(cells, before, what) {
    const now = await this.snapshot(cells);
    for (let i = 0; i < cells.length; i++) {
      if (now[i] !== before[i]) throw new CheckFailed(`${what}: ${cells[i][0]}/${cells[i][1]} changed (${before[i]} -> ${now[i]})`);
    }
  }

  /** Result of a successful move: { op, moved }. */
  result(resp, what, op, moved) {
    const d = okData(resp, what);
    if (d.ok !== true) throw new CheckFailed(`${what}: data.ok must be true, message: ${d.message ?? '(none)'}`);
    const r = d.result;
    if (!isObj(r)) throw new CheckFailed(`${what}: data.result must be {op, moved}`);
    if (!['move', 'stack', 'swap'].includes(r.op)) throw new CheckFailed(`${what}: result.op must be move|stack|swap, got ${JSON.stringify(r.op)}`);
    if (!Number.isInteger(r.moved) || r.moved < 0) throw new CheckFailed(`${what}: result.moved must be a non-negative integer`);
    if (op && r.op !== op) throw new CheckFailed(`${what}: expected op ${op}, got ${r.op}`);
    if (moved !== undefined && r.moved !== moved) throw new CheckFailed(`${what}: expected moved ${moved}, got ${r.moved}`);
    return r;
  }

  async cleanup() {
    const errs = [];
    for (const fn of this.restore.reverse()) {
      try {
        await fn();
      } catch (e) {
        errs.push(`restore: ${e.message}`);
      }
    }
    for (const [c, s] of this.touched) {
      try {
        await this.clear(c, s);
      } catch (e) {
        errs.push(`clear ${c}/${s}: ${e.message}`);
      }
    }
    return errs;
  }
}

/** Defines one test per move variant. body(rig, ctx) returns the pass message. */
function moveTest({ id, title, body, only }) {
  const mk = (variant) => ({
    id: only ? id : `${id}${variant === 'move_item' ? '.item' : ''}`,
    group: 'move',
    title: `(--allow-actions, ${variant}) ${title}`,
    needs: NEEDS,
    async run(ctx) {
      const rig = await Rig.open(ctx, variant);
      let msg;
      let failure = null;
      try {
        msg = await body(rig, ctx);
      } catch (e) {
        failure = e;
      }
      const errs = await rig.cleanup();
      // The test player disconnecting mid-test (kicked, server-side mod required on the client...) is not a bridge fault.
      const offline = (x) => /\bgot offline\b/.test(String(x?.message ?? x));
      if ((failure && offline(failure)) || errs.some(offline)) throw new Skip('the test player went offline during the test');
      if (failure) throw failure;
      if (errs.length) throw new CheckFailed(`cleanup failed, the player's inventory may be left modified: ${errs.join('; ')}`);
      return msg;
    },
  });
  return only ? [mk(only)] : [mk('move_item_live'), mk('move_item')];
}

export const tests = [
  {
    id: 'move.advertised',
    group: 'move',
    title: 'move_item / move_item_live advertise the destination_expected_* fields with the right kinds (D24)',
    needs: ['hello'],
    run(ctx) {
      const found = [];
      const errs = [];
      for (const [aid, want] of Object.entries(FIELD_KINDS)) {
        const a = spec(ctx, aid);
        if (!a) continue;
        found.push(aid);
        if (a.target !== true) errs.push(`${aid}: target must be true`);
        for (const [key, kinds] of Object.entries(want)) {
          const f = a.fields.find((x) => x.key === key);
          if (!f) errs.push(`${aid}: field ${key} missing`);
          else if (!kinds.includes(f.kind)) errs.push(`${aid}.${key}: kind ${f.kind} (expected ${kinds.join('|')})`);
        }
      }
      if (!found.length) throw new Skip('neither move_item nor move_item_live advertised');
      if (errs.length) throw new CheckFailed(errs.join('; '));
      return `${found.join(', ')}: destination fields present`;
    },
  },

  ...moveTest({
    id: 'move.empty-destination',
    title: 'moving onto an empty slot moves the stack; a destination_expected_id other than minecraft:air is a conflict',
    async body(r) {
      const src = await r.put(r.A, 'minecraft:dirt', 10);
      const cells = [[INV, r.A], [INV, r.B]];
      const before = await r.snapshot(cells);
      const wrong = await r.move([INV, r.A], [INV, r.B], { ...r.src(src), destination_expected_id: 'minecraft:stone' });
      errCode(wrong, 'conflict', 'empty destination with wrong destination_expected_id');
      await r.expectUnchanged(cells, before, 'after conflict');
      const resp = await r.move([INV, r.A], [INV, r.B], { ...r.src(src), ...r.dst(null) });
      r.result(resp, 'move to empty', 'move', 10);
      await r.expectSlot(INV, r.A, null, 0, 'move to empty');
      await r.expectSlot(INV, r.B, 'minecraft:dirt', 10, 'move to empty');
      return 'dirt x10 moved, source empty';
    },
  }),

  ...moveTest({
    id: 'move.swap',
    title: 'dirt onto stone swaps the two stacks',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 5);
      const b = await r.put(r.B, 'minecraft:stone', 7);
      const resp = await r.move([INV, r.A], [INV, r.B], { ...r.src(a), ...r.dst(b) });
      r.result(resp, 'swap', 'swap');
      await r.expectSlot(INV, r.A, 'minecraft:stone', 7, 'swap');
      await r.expectSlot(INV, r.B, 'minecraft:dirt', 5, 'swap');
      return 'dirt x5 and stone x7 exchanged';
    },
  }),

  ...moveTest({
    id: 'move.stack-full',
    title: 'dirt x10 onto dirt x20 merges to x30 and empties the source',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 10);
      const b = await r.put(r.B, 'minecraft:dirt', 20);
      const resp = await r.move([INV, r.A], [INV, r.B], { ...r.src(a), ...r.dst(b) });
      r.result(resp, 'stack', 'stack', 10);
      await r.expectSlot(INV, r.A, null, 0, 'stack');
      await r.expectSlot(INV, r.B, 'minecraft:dirt', 30, 'stack');
      return 'x30 in destination, source empty';
    },
  }),

  ...moveTest({
    id: 'move.stack-remainder',
    title: 'dirt x40 onto dirt x50 fills the destination to 64 and leaves 26 in the source',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 40);
      const b = await r.put(r.B, 'minecraft:dirt', 50);
      const resp = await r.move([INV, r.A], [INV, r.B], { ...r.src(a), ...r.dst(b) });
      r.result(resp, 'stack with remainder', 'stack', 14);
      await r.expectSlot(INV, r.B, 'minecraft:dirt', 64, 'stack with remainder');
      await r.expectSlot(INV, r.A, 'minecraft:dirt', 26, 'stack with remainder');
      return 'destination 64, source 26';
    },
  }),

  ...moveTest({
    id: 'move.full-destination-swaps',
    title: 'dirt x10 onto a full dirt x64 stack swaps (nothing fits, so stacking is impossible)',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 10);
      const b = await r.put(r.B, 'minecraft:dirt', 64);
      const resp = await r.move([INV, r.A], [INV, r.B], { ...r.src(a), ...r.dst(b) });
      r.result(resp, 'full destination', 'swap');
      await r.expectSlot(INV, r.A, 'minecraft:dirt', 64, 'full destination');
      await r.expectSlot(INV, r.B, 'minecraft:dirt', 10, 'full destination');
      return 'stacks exchanged (64 / 10)';
    },
  }),

  ...moveTest({
    id: 'move.stale-destination',
    title: 'a wrong destination_expected_id (or source expected_id) is a conflict and changes neither slot',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 10);
      const b = await r.put(r.B, 'minecraft:stone', 5);
      const cells = [[INV, r.A], [INV, r.B]];
      const before = await r.snapshot(cells);
      errCode(await r.move([INV, r.A], [INV, r.B], { ...r.src(a), destination_expected_id: 'minecraft:dirt' }), 'conflict', 'stale destination id');
      await r.expectUnchanged(cells, before, 'stale destination id');
      errCode(await r.move([INV, r.A], [INV, r.B], { ...r.src(a), destination_expected_id: AIR }), 'conflict', 'destination expected empty but occupied');
      await r.expectUnchanged(cells, before, 'destination expected empty');
      errCode(await r.move([INV, r.A], [INV, r.B], { ...r.dst(b), ...r.src(a), expected_id: 'minecraft:stone' }), 'conflict', 'stale source id');
      await r.expectUnchanged(cells, before, 'stale source id');
      return 'three stale expectations answered conflict, both slots unchanged';
    },
  }),

  ...moveTest({
    id: 'move.no-destination-expectation',
    title: 'an occupied destination without destination_expected_id is a conflict (old-panel behaviour)',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 10);
      await r.put(r.B, 'minecraft:stone', 5);
      const cells = [[INV, r.A], [INV, r.B]];
      const before = await r.snapshot(cells);
      errCode(await r.move([INV, r.A], [INV, r.B], { ...r.src(a) }), 'conflict', 'occupied destination without expectation');
      await r.expectUnchanged(cells, before, 'occupied destination without expectation');
      return 'conflict, unchanged';
    },
  }),

  ...moveTest({
    id: 'move.same-slot',
    title: 'source equal to destination is invalid_args and changes nothing',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 10);
      const cells = [[INV, r.A]];
      const before = await r.snapshot(cells);
      errCode(await r.move([INV, r.A], [INV, r.A], { ...r.src(a), ...r.dst(a) }), 'invalid_args', 'same slot');
      await r.expectUnchanged(cells, before, 'same slot');
      return 'invalid_args, unchanged';
    },
  }),

  ...moveTest({
    id: 'move.armor-mismatch',
    title: 'a non-armor item cannot go into the head slot: conflict, both slots unchanged',
    only: 'move_item_live', // move_item cannot cross containers
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 10);
      const head = await r.get('equipment', 'head');
      const cells = [[INV, r.A], ['equipment', 'head']];
      const before = await r.snapshot(cells);
      // A non-conformant bridge may accept the move: undo it (dirt out of the head, original helmet back).
      r.restore.push(async () => {
        const n = await r.get('equipment', 'head');
        if (!n || n.id !== a.id) return;
        await r.clear('equipment', 'head');
        const back = await r.get(INV, r.A);
        if (head && back && back.id === head.id) await r.move([INV, r.A], ['equipment', 'head'], { expected_id: head.id, ...r.dst(null) });
      });
      const resp = await r.move([INV, r.A], ['equipment', 'head'], { ...r.src(a), ...r.dst(head) });
      if (resp?.ok === false && resp.error?.code === 'invalid_args' && /equipment|container|slot/i.test(resp.error.message)) {
        throw new Skip(`equipment/head is not addressable on this bridge (${describeResp(resp)})`);
      }
      errCode(resp, 'conflict', 'dirt into head');
      await r.expectUnchanged(cells, before, 'dirt into head');
      return head ? 'dirt into an occupied head slot refused' : 'dirt into the empty head slot refused';
    },
  }),

  ...moveTest({
    id: 'move.armor-fit',
    title: 'a helmet can be moved into the head slot (moved when empty, swapped when a helmet is there) and back',
    only: 'move_item_live',
    async body(r) {
      const head = await r.get('equipment', 'head');
      if (head && !/_helmet$/.test(head.id)) throw new Skip(`head slot holds ${head.id}, which may not fit back; test skipped`);
      // iron_helmet by default; another helmet when the head already wears one, so a swap is distinguishable
      const hid = head?.id === 'minecraft:iron_helmet' ? 'minecraft:diamond_helmet' : 'minecraft:iron_helmet';
      const helmet = await r.put(r.A, hid, 1);
      const cells = [[INV, r.A], ['equipment', 'head']];
      const before = await r.snapshot(cells);
      const resp = await r.move([INV, r.A], ['equipment', 'head'], { ...r.src(helmet), ...r.dst(head) });
      if (resp?.ok === false && resp.error?.code === 'invalid_args') throw new Skip(`equipment/head is not writable on this bridge (${describeResp(resp)})`);
      // From this point the head slot is touched: always try to put the original back.
      r.restore.push(async () => {
        const now = await r.get('equipment', 'head');
        if (now && now.id === helmet.id && head && head.id !== helmet.id) {
          await r.move(['equipment', 'head'], [INV, r.A], { expected_id: now.id, ...r.dst(await r.get(INV, r.A)) });
        } else if (now && now.id === helmet.id && !head) {
          await r.move(['equipment', 'head'], [INV, r.A], { expected_id: now.id, ...r.dst(null) });
        }
      });
      r.result(resp, 'helmet into head', head ? 'swap' : 'move');
      const afterHead = await r.get('equipment', 'head');
      if (!afterHead || afterHead.id !== hid) throw new CheckFailed(`head should hold ${hid}, holds ${afterHead ? afterHead.id : 'nothing'}`);
      if (head) {
        const slotA = await r.get(INV, r.A);
        if (!slotA || slotA.id !== head.id) throw new CheckFailed(`the previous helmet ${head.id} should now be in inventory/${r.A}`);
      } else {
        await r.expectSlot(INV, r.A, null, 0, 'helmet into empty head');
      }
      // Back again: head -> inventory slot (inventory slots accept anything).
      const now = await r.get('equipment', 'head');
      const dest = await r.get(INV, r.A);
      const back = await r.move(['equipment', 'head'], [INV, r.A], { expected_id: now.id, ...r.dst(dest) });
      r.result(back, 'helmet back', head ? 'swap' : 'move');
      await r.expectUnchanged([['equipment', 'head']], [before[1]], 'head after restore');
      return head ? 'helmet swapped in and back, head restored' : 'helmet moved in and back, head empty again';
    },
  }),

  ...moveTest({
    id: 'move.item.destination-checks',
    title: 'move_item verifies destination_expected_count and destination_expected_fingerprint ("none" = empty)',
    only: 'move_item',
    async body(r) {
      const a = await r.put(r.A, 'minecraft:dirt', 10);
      const b = await r.put(r.B, 'minecraft:stone', 7);
      const cells = [[INV, r.A], [INV, r.B]];
      const before = await r.snapshot(cells);
      const base = { ...r.src(a), destination_expected_id: b.id };
      errCode(await r.move([INV, r.A], [INV, r.B], { ...base, destination_expected_count: b.count + 1, destination_expected_fingerprint: b.fingerprint }), 'conflict', 'wrong destination count');
      await r.expectUnchanged(cells, before, 'wrong destination count');
      errCode(await r.move([INV, r.A], [INV, r.B], { ...base, destination_expected_count: b.count, destination_expected_fingerprint: ZERO_FP }), 'conflict', 'wrong destination fingerprint');
      await r.expectUnchanged(cells, before, 'wrong destination fingerprint');
      errCode(await r.move([INV, r.A], [INV, r.B], { ...base, destination_expected_count: b.count, destination_expected_fingerprint: 'none' }), 'conflict', 'fingerprint none on occupied destination');
      await r.expectUnchanged(cells, before, 'fingerprint none on occupied destination');
      const ok = await r.move([INV, r.A], [INV, r.B], { ...base, destination_expected_count: b.count, destination_expected_fingerprint: b.fingerprint });
      r.result(ok, 'matching destination expectations', 'swap');
      await r.expectSlot(INV, r.A, 'minecraft:stone', 7, 'swap');
      await r.expectSlot(INV, r.B, 'minecraft:dirt', 10, 'swap');
      return 'wrong count, wrong fingerprint and "none" rejected; the full expectation swapped';
    },
  }),
];
