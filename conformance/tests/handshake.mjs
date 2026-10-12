// hello, authentication, envelope and capability schema (operations.md section 1, README section 2, D15).

import { Check, fail, isObj, isStr } from '../lib/check.mjs';
import { helloErrors, actionSpecErrors } from '../lib/schema.mjs';
import { KNOWN_OPS, ERROR_CODES, MAX_FRAME } from '../lib/protocol.mjs';
import { num } from '../lib/msgpack.mjs';
import { okData, errCode, anyErr, describeResp, waitFor } from './helpers.mjs';

export const tests = [
  {
    id: 'hello.required-first',
    group: 'handshake',
    title: 'first request must be hello (unix: invalid_args, connection kept; tcp: unauthorized + close)',
    needs: [],
    async run(ctx) {
      const { client } = await ctx.open({ hello: false });
      const resp = await client.request('server.info', {});
      if (ctx.mode === 'unix') {
        errCode(resp, 'invalid_args', 'server.info before hello');
        if (client.closed) fail('unix: connection closed after a pre-hello request (D15: must stay open)');
        const h = await client.request('hello', ctx.helloArgs());
        okData(h, 'hello after a rejected request');
        return 'unix: invalid_args, connection stays open';
      }
      errCode(resp, 'unauthorized', 'server.info before hello (tcp)');
      const closed = await client.waitClosed(2000);
      if (!closed) fail('tcp: connection not closed after unauthorized');
      return 'tcp: unauthorized and closed';
    },
  },
  {
    id: 'hello.tcp-wrong-token',
    group: 'handshake',
    title: 'tcp: wrong token gets unauthorized and the connection is closed',
    needs: ['tcp'],
    async run(ctx) {
      const { client, resp } = await ctx.open({ hello: true, token: '0'.repeat(64) });
      errCode(resp, 'unauthorized', 'hello with wrong token');
      if (!(await client.waitClosed(2000))) fail('connection not closed after wrong token');
      return 'unauthorized, closed';
    },
  },
  {
    id: 'hello.tcp-missing-token',
    group: 'handshake',
    title: 'tcp: hello without a token gets unauthorized and the connection is closed',
    needs: ['tcp'],
    async run(ctx) {
      const { client, resp } = await ctx.open({ hello: true, withToken: false });
      errCode(resp, 'unauthorized', 'hello without token');
      if (!(await client.waitClosed(2000))) fail('connection not closed after missing token');
      return 'unauthorized, closed';
    },
  },
  {
    id: 'hello.missing-panel_version',
    group: 'handshake',
    title: 'hello without panel_version is invalid_args',
    needs: [],
    async run(ctx) {
      const { client } = await ctx.open({ hello: false });
      const args = {};
      if (ctx.mode === 'tcp') args.token = ctx.token;
      const resp = await client.request('hello', args);
      errCode(resp, 'invalid_args', 'hello without panel_version');
      return 'invalid_args';
    },
  },
  {
    id: 'hello.schema',
    group: 'handshake',
    title: 'hello response matches operations.md section 1 (capabilities, ActionSpec, limits)',
    needs: ['hello'],
    run(ctx) {
      const errs = helloErrors(ctx.hello, ctx.bridge);
      if (errs.length) fail(errs.slice(0, 8).join('; '));
      return `${ctx.hello.capabilities.ops.length} ops, ${ctx.hello.capabilities.actions.length} actions`;
    },
  },
  {
    id: 'hello.protocol-matches-bridge-json',
    group: 'handshake',
    title: 'hello.protocol equals bridge.json.protocol',
    needs: ['hello', 'root'],
    run(ctx) {
      const hp = num(ctx.hello.protocol);
      if (hp !== ctx.bridge.protocol) fail(`hello.protocol ${hp} != bridge.json.protocol ${ctx.bridge.protocol}`);
      return `protocol ${hp}`;
    },
  },
  {
    id: 'hello.limits',
    group: 'handshake',
    title: 'limits: max_frame <= 4194304, positions floor, max_inflight present',
    needs: ['hello'],
    run(ctx) {
      const l = ctx.hello.limits;
      const c = new Check('limits');
      c.ok(isObj(l), 'limits missing');
      if (isObj(l)) {
        c.ok(num(l.max_frame) <= MAX_FRAME, `max_frame ${num(l.max_frame)} > ${MAX_FRAME}`);
        c.ok(num(l.positions_min_interval_ms) > 0, 'positions_min_interval_ms must be > 0');
        c.ok(num(l.max_inflight) >= 1, 'max_inflight must be >= 1');
      }
      c.done();
      return `max_frame ${num(l.max_frame)}, max_inflight ${num(l.max_inflight)}`;
    },
  },
  {
    id: 'hello.actions',
    group: 'handshake',
    title: 'ActionSpec entries use the allowed field vocabulary; teleport_player is never advertised (D5)',
    needs: ['hello'],
    run(ctx) {
      const acts = ctx.hello.capabilities.actions;
      const errs = actionSpecErrors(acts);
      const ids = acts.map((a) => a?.id);
      if (ids.includes('teleport_player')) errs.push('teleport_player must not be advertised (D5)');
      if (errs.length) fail(errs.slice(0, 8).join('; '));
      return `${acts.length} actions valid`;
    },
  },
  {
    id: 'hello.capability-consistency',
    group: 'handshake',
    title: 'features agree with the ops they describe (positions.stream, assets)',
    needs: ['hello'],
    run(ctx) {
      const { ops, features, player_fields: pf, events } = ctx.hello.capabilities;
      const c = new Check('capabilities');
      c.ok(features.includes('positions.stream') === ops.includes('positions.subscribe'), 'positions.stream feature must match positions.subscribe op');
      const assetsOps = ops.includes('assets.manifest') && ops.includes('assets.get');
      c.ok(features.includes('assets') === assetsOps, 'assets feature must match assets.manifest + assets.get ops');
      c.ok(new Set(pf).size === pf.length, 'player_fields has duplicates');
      c.ok(new Set(events).size === events.length, 'events has duplicates');
      c.done();
      return 'features consistent with ops';
    },
  },
  {
    id: 'envelope.u32-id',
    group: 'handshake',
    title: 'response echoes a u32 request id (0xffffffff) exactly',
    needs: ['hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const resp = await client.request('server.info', {}, { id: 0xffffffff });
      okData(resp, 'server.info with id 4294967295');
      if (resp.id !== 0xffffffff) fail(`id echoed as ${resp.id}`);
      return 'id echoed';
    },
  },
  {
    id: 'envelope.out-of-order',
    group: 'handshake',
    title: 'concurrent requests on one connection are matched by id (responses may be out of order)',
    needs: ['hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const ids = [900, 3, 77, 5, 41];
      const ops = ['server.info', 'players.list', 'server.info', 'players.list', 'server.info'];
      const results = await Promise.all(ids.map((id, i) => client.request(ops[i], {}, { id })));
      results.forEach((r, i) => {
        okData(r, `${ops[i]} (id ${ids[i]})`);
        if (r.id !== ids[i]) fail(`response id ${r.id} for request id ${ids[i]}`);
      });
      if (client.unmatched.length) fail(`${client.unmatched.length} responses with unknown ids`);
      if (client.protocolErrors.length) fail(`${client.protocolErrors.length} unexpected messages`);
      return 'all 5 matched by id';
    },
  },
  {
    id: 'envelope.error-shape',
    group: 'handshake',
    title: 'error responses carry {code, message} with a documented code',
    needs: ['hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const resp = await client.request('no.such.op', {});
      anyErr(resp, 'unknown op');
      return `code ${resp.error.code}`;
    },
  },
];

export { KNOWN_OPS, ERROR_CODES, describeResp, waitFor, isStr };
