// positions.* and events.* subscriptions (operations.md section 7, 8).

import { Check, fail, Skip, isBool, isIntV, isStr, isObj } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { EVENT_TYPES, PLAYER_STATES } from '../lib/protocol.mjs';
import { okData, errCode, anyErr, describeResp, sleep } from './helpers.mjs';

const POS_FRAME_KEYS = ['key', 'players', 'gone'];

/** Validates one positions frame's data (keyframe or delta). */
function positionFrameErrors(f, { first }) {
  const e = [];
  const d = f.data;
  if (!isObj(d)) return ['frame data must be an object'];
  if (typeof d.key !== 'boolean') e.push('key must be a bool');
  if (!Array.isArray(d.players)) e.push('players must be an array');
  for (const k of Object.keys(d)) if (!POS_FRAME_KEYS.includes(k) && k !== 'type') e.push(`unexpected frame key ${k}`);
  if (d.key === true && 'gone' in d) e.push('keyframe must not carry gone');
  if (d.key === false) {
    if (!Array.isArray(d.gone)) e.push('delta must carry gone as an array (may be empty)');
    if (Array.isArray(d.players) && d.players.length === 0 && (!Array.isArray(d.gone) || d.gone.length === 0)) {
      e.push('empty delta must not be sent');
    }
  }
  if (first && d.key !== true) e.push('first frame of a subscription must be a keyframe');
  for (const [i, p] of (d.players ?? []).entries()) {
    const pp = `players[${i}]`;
    if (!isObj(p)) {
      e.push(`${pp} is not an object`);
      continue;
    }
    if (!isStr(p.id)) e.push(`${pp}.id must be a string`);
    if (!isStr(p.name)) e.push(`${pp}.name must be a string`);
    if (!isStr(p.world)) e.push(`${pp}.world must be a string`);
    if (p.dimension !== undefined && !isStr(p.dimension)) e.push(`${pp}.dimension must be a string`);
    for (const k of ['x', 'y', 'z', 'yaw', 'pitch']) {
      if (p[k] !== undefined && !isIntV(p[k])) e.push(`${pp}.${k} must be an integer (quantised), got ${typeof p[k]}`);
    }
    for (const k of ['x', 'y', 'z']) if (!(k in p)) e.push(`${pp}.${k} missing`);
    if (!PLAYER_STATES.includes(p.state)) e.push(`${pp}.state ${JSON.stringify(p.state)} not alive|dead|spectator`);
  }
  if (Array.isArray(d.gone) && !d.gone.every(isStr)) e.push('gone must be string[]');
  return e;
}

async function openPositions(ctx, interval = 250) {
  const { client } = await ctx.open({ hello: true });
  const { resp, sub } = await client.subscribe('positions.subscribe', { interval_ms: interval });
  okData(resp, 'positions.subscribe');
  if (!sub) fail('positions.subscribe ack has no sub id');
  return { client, sub, ack: resp };
}

export const tests = [
  {
    id: 'positions.subscribe.ack-keyframe',
    group: 'positions',
    title: 'positions.subscribe acks {sub}; first frame is a keyframe with seq 0',
    needs: ['op:positions.subscribe'],
    async run(ctx) {
      const { client, sub, ack } = await openPositions(ctx);
      const d = okData(ack, 'ack');
      if (!isIntV(d.sub)) fail('sub must be an int');
      const f = await sub.next(3000);
      const errs = positionFrameErrors(f, { first: true });
      if (errs.length) fail(errs.slice(0, 6).join('; '));
      if (f.seq !== 0) fail(`first seq is ${f.seq}, must be 0`);
      client.close();
      return `keyframe with ${f.data.players.length} players`;
    },
  },
  {
    id: 'positions.frames.schema-quantised',
    group: 'positions',
    title: 'positions frames: quantised int coordinates, state enum, delta/gone rules',
    needs: ['op:positions.subscribe'],
    async run(ctx) {
      const { client, sub } = await openPositions(ctx, 250);
      await sub.next(3000);
      for (let i = 0; i < 3; i++) {
        const resp = await client.request('positions.keyframe', { sub: sub.sub });
        okData(resp, 'positions.keyframe');
      }
      const frames = [];
      for (let i = 0; i < 3; i++) frames.push(await sub.next(3000));
      const errs = frames.flatMap((f) => positionFrameErrors(f, { first: false }));
      client.close();
      if (errs.length) fail(errs.slice(0, 6).join('; '));
      return `${frames.length} frames checked`;
    },
  },
  {
    id: 'positions.seq',
    group: 'positions',
    title: 'seq starts at 0 and increases by exactly 1 per frame on a subscription',
    needs: ['op:positions.subscribe'],
    async run(ctx) {
      const { client, sub } = await openPositions(ctx);
      await sub.next(3000);
      for (let i = 0; i < 4; i++) okData(await client.request('positions.keyframe', { sub: sub.sub }), 'keyframe');
      for (let i = 0; i < 4; i++) await sub.next(3000);
      client.close();
      if (sub.gaps.length) fail(`seq gaps: ${JSON.stringify(sub.gaps.slice(0, 3))}`);
      sub.frames.forEach((f, i) => {
        if (num(f.seq) !== i) fail(`frame ${i} has seq ${f.seq}`);
      });
      return `${sub.frames.length} frames, seq 0..${sub.frames.length - 1}`;
    },
  },
  {
    id: 'positions.keyframe.triggers',
    group: 'positions',
    title: 'positions.keyframe returns {} and the next frame is a keyframe (seq continues)',
    needs: ['op:positions.keyframe', 'op:positions.subscribe'],
    async run(ctx) {
      const { client, sub } = await openPositions(ctx);
      await sub.next(3000);
      const resp = await client.request('positions.keyframe', { sub: sub.sub });
      const d = okData(resp, 'positions.keyframe');
      if (Object.keys(d).length !== 0) fail('positions.keyframe data must be {}');
      const f = await sub.until((x) => x.data.key === true, 3000);
      client.close();
      const prev = sub.frames[sub.frames.length - 2];
      if (sub.gaps.length) fail(`seq gaps: ${JSON.stringify(sub.gaps)}`);
      if (prev && num(f.seq) !== num(prev.seq) + 1) fail(`keyframe seq ${f.seq} does not follow ${prev.seq}`);
      return 'keyframe on request';
    },
  },
  {
    id: 'positions.unsubscribe',
    group: 'positions',
    title: 'positions.unsubscribe returns {}; the sub then stops and is not_found (unknown sub too)',
    needs: ['op:positions.unsubscribe', 'op:positions.subscribe'],
    async run(ctx) {
      const { client, sub } = await openPositions(ctx);
      await sub.next(3000);
      const un = okData(await client.request('positions.unsubscribe', { sub: sub.sub }), 'unsubscribe');
      if (Object.keys(un).length) fail('unsubscribe data must be {}');
      const before = sub.frames.length;
      await sleep(800);
      if (sub.frames.length !== before) fail('frames still arriving after unsubscribe');
      errCode(await client.request('positions.keyframe', { sub: sub.sub }), 'not_found', 'keyframe after unsubscribe');
      errCode(await client.request('positions.unsubscribe', { sub: sub.sub }), 'not_found', 'second unsubscribe');
      client.close();
      return 'stopped; not_found after';
    },
  },
  {
    id: 'positions.keyframe.unknown-sub',
    group: 'positions',
    title: 'positions.keyframe with an unknown sub is not_found',
    needs: ['op:positions.keyframe', 'hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      errCode(await client.request('positions.keyframe', { sub: 4242 }), 'not_found', 'unknown sub');
      return 'not_found';
    },
  },
  {
    id: 'positions.busy-second-subscription',
    group: 'positions',
    title: 'a second positions subscription on the same connection is busy',
    needs: ['op:positions.subscribe'],
    async run(ctx) {
      const { client } = await openPositions(ctx);
      const resp = await client.request('positions.subscribe', { interval_ms: 250 });
      errCode(resp, 'busy', 'second positions.subscribe');
      client.close();
      return 'busy';
    },
  },
  {
    id: 'positions.interval-clamped',
    group: 'positions',
    title: 'positions.subscribe with interval_ms below the floor is accepted (clamped)',
    needs: ['op:positions.subscribe'],
    async run(ctx) {
      const { client, sub } = await openPositions(ctx, 1);
      await sub.next(3000);
      client.close();
      return 'accepted';
    },
  },
  {
    id: 'events.subscribe.ack',
    group: 'events',
    title: 'events.subscribe acks {sub}; unsubscribe returns {}',
    needs: ['op:events.subscribe'],
    async run(ctx) {
      const types = ctx.caps.events;
      if (types.length === 0) throw new Skip('no event types advertised');
      const { client, sub, resp } = await (async () => {
        const { client } = await ctx.open({ hello: true });
        const r = await client.subscribe('events.subscribe', { types: [types[0]] });
        return { client, sub: r.sub, resp: r.resp };
      })();
      okData(resp, 'events.subscribe');
      if (!sub) fail('no sub registered');
      const un = okData(await client.request('events.unsubscribe', { sub: sub.sub }), 'events.unsubscribe');
      if (Object.keys(un).length) fail('events.unsubscribe data must be {}');
      errCode(await client.request('events.unsubscribe', { sub: sub.sub }), 'not_found', 'events.unsubscribe twice');
      client.close();
      return `subscribed to ${types[0]}`;
    },
  },
  {
    id: 'events.subscribe.unsupported-type',
    group: 'events',
    title: 'events.subscribe for an event type not advertised is unsupported',
    needs: ['op:events.subscribe'],
    async run(ctx) {
      const missing = EVENT_TYPES.filter((t) => !ctx.caps.events.includes(t));
      if (missing.length === 0) throw new Skip('every known event type is advertised');
      const { client } = await ctx.open({ hello: true });
      errCode(await client.request('events.subscribe', { types: [missing[0]] }), 'unsupported', `types [${missing[0]}]`);
      return `${missing[0]} -> unsupported`;
    },
  },
  {
    id: 'events.subscribe.busy',
    group: 'events',
    title: 'a second events subscription on the same connection is busy; one positions + one events coexist',
    needs: ['op:events.subscribe', 'op:positions.subscribe'],
    async run(ctx) {
      const types = ctx.caps.events.length ? [ctx.caps.events[0]] : ['join'];
      const { client } = await ctx.open({ hello: true });
      const e1 = await client.subscribe('events.subscribe', { types });
      okData(e1.resp, 'first events.subscribe');
      const p1 = await client.subscribe('positions.subscribe', { interval_ms: 250 });
      okData(p1.resp, 'positions.subscribe alongside events');
      errCode(await client.request('events.subscribe', { types }), 'busy', 'second events.subscribe');
      client.close();
      return 'busy on second events; positions alongside ok';
    },
  },
  {
    id: 'events.frames.shape',
    group: 'events',
    title: 'events frames carry a known type, time and seq from 0 (if any arrive within 2 s)',
    needs: ['op:events.subscribe'],
    async run(ctx) {
      const types = ctx.caps.events.length ? ctx.caps.events : null;
      if (!types) throw new Skip('no event types advertised');
      const { client } = await ctx.open({ hello: true });
      const { sub, resp } = await client.subscribe('events.subscribe', { types });
      okData(resp, 'events.subscribe');
      await sleep(2000);
      client.close();
      if (sub.frames.length === 0) return 'no events during 2 s (nothing to check)';
      const c = new Check('events');
      c.ok(num(sub.frames[0].seq) === 0, `first event seq is ${sub.frames[0].seq}, expected 0`);
      for (const f of sub.frames) {
        const t = f.data?.type;
        c.ok(t === 'ping' || types.includes(t) || t === 'overflow', `event type ${t} not subscribed`);
        if (t !== 'ping') c.ok(isIntV(f.data?.time), 'event time must be unix seconds');
      }
      c.done();
      return `${sub.frames.length} events checked`;
    },
  },
  {
    id: 'events.unsubscribe.unknown',
    group: 'events',
    title: 'events.unsubscribe with an unknown sub is not_found',
    needs: ['op:events.unsubscribe', 'hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      errCode(await client.request('events.unsubscribe', { sub: 4242 }), 'not_found', 'unknown sub');
      return 'not_found';
    },
  },
  {
    id: 'events.subscribe.types-unknown-rejected',
    group: 'events',
    title: 'events.subscribe with a non-contract type is refused (covered by args.events.subscribe-unknown-type)',
    needs: ['op:events.subscribe'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const resp = await client.request('events.subscribe', { types: ['chat', 'pm_bogus'] });
      if (resp.ok === true) fail('list containing an unknown type was accepted');
      anyErr(resp, 'mixed list');
      return describeResp(resp);
    },
  },
];

export { isBool, isObj };
