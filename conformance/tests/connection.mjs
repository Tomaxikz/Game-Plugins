// Connection behaviour: concurrency, busy, frame limits, malformed frames, keep-alive and idle close.

import { Skip, fail, isStr } from '../lib/check.mjs';
import { encodeFrame, frameHeader } from '../lib/framing.mjs';
import { num } from '../lib/msgpack.mjs';
import { okData, errCode, describeResp, waitFor, sleep } from './helpers.mjs';

async function healthy(ctx) {
  const { client, resp } = await ctx.open({ hello: true });
  okData(resp, 'fresh connection hello');
  const info = await client.request('server.info', {});
  okData(info, 'fresh connection server.info');
  client.close();
}

export const tests = [
  {
    id: 'conn.eight-concurrent',
    group: 'connection',
    title: 'at least 8 concurrent connections are accepted and answered',
    needs: ['hello'],
    async run(ctx) {
      const conns = [];
      for (let i = 0; i < 8; i++) conns.push((await ctx.open({ hello: true })).client);
      const results = await Promise.all(conns.map((c) => c.request('server.info', {})));
      results.forEach((r, i) => okData(r, `connection ${i}`));
      conns.forEach((c) => c.close());
      return '8 of 8 answered';
    },
  },
  {
    id: 'conn.max-inflight-busy',
    group: 'connection',
    title: 'max_inflight+4 requests sent at once: all answered, extras may be busy and nothing else',
    needs: ['hello'],
    async run(ctx) {
      const max = num(ctx.hello.limits.max_inflight);
      const n = max + 4;
      const { client } = await ctx.open({ hello: true });
      const promises = [];
      for (let i = 0; i < n; i++) promises.push(client.request('server.info', {}, { timeout: 5000 }));
      const results = await Promise.all(promises);
      client.close();
      let busy = 0;
      let ok = 0;
      for (const r of results) {
        if (r.ok === true) ok += 1;
        else if (r.error?.code === 'busy') busy += 1;
        else fail(`unexpected response under load: ${describeResp(r)}`);
      }
      if (ok + busy !== n) fail(`answered ${ok + busy} of ${n}`);
      return `${ok} ok, ${busy} busy (max_inflight ${max})`;
    },
  },
  {
    id: 'conn.frame-oversize-rejected',
    group: 'connection',
    title: 'a frame header announcing more than 4 MiB is rejected or the connection closed; no ok response',
    needs: ['hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      client.writeRaw(frameHeader(4194305));
      client.writeRaw(Buffer.alloc(64, 0x41));
      const closed = await client.waitClosed(3000);
      if (!closed) fail('connection stayed open after an oversize header (must reject or close)');
      await healthy(ctx);
      return 'connection closed; server still serves new connections';
    },
  },
  {
    id: 'conn.frame-1mib-accepted',
    group: 'connection',
    title: 'a request frame of about 1 MiB (unknown padding arg) is accepted',
    needs: ['hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const resp = await client.request('server.info', { pm_padding: 'x'.repeat(1024 * 1024) }, { timeout: 5000 });
      client.close();
      okData(resp, 'server.info with 1 MiB padding');
      return 'accepted';
    },
  },
  {
    id: 'conn.malformed-body',
    group: 'connection',
    title: 'a frame whose body is not valid MessagePack gets an error or a close, never ok; server survives',
    needs: ['hello'],
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const body = Buffer.from([0xc1, 0xc1, 0x00]);
      client.writeRaw(Buffer.concat([frameHeader(body.length), body]));
      const got = await waitFor(() => client.closed || client.decodeErrors.length > 0 || client.protocolErrors.length > 0 || client.unmatched.length > 0, 3000);
      if (!got) {
        // Bridge ignored it silently: must still be usable and must not ack anything.
        const r = await client.request('server.info', {}, { timeout: 2000 });
        okData(r, 'server.info after garbage');
      }
      client.close();
      await healthy(ctx);
      return 'survived';
    },
  },
  {
    id: 'ping.keepalive-60s',
    group: 'keepalive',
    title: '(--slow) a quiet subscription receives the {type:ping} keep-alive frame and stays open',
    needs: ['slow', 'hello'],
    timeout: 90000,
    async run(ctx) {
      let client, sub;
      if (ctx.has('events.subscribe') && ctx.caps.events.length) {
        ({ client } = await ctx.open({ hello: true }));
        ({ sub } = await client.subscribe('events.subscribe', { types: [ctx.caps.events[0]] }));
      } else if (ctx.has('positions.subscribe')) {
        ({ client } = await ctx.open({ hello: true }));
        ({ sub } = await client.subscribe('positions.subscribe', { interval_ms: 1000 }));
      } else {
        throw new Skip('no subscription op advertised');
      }
      if (!sub) fail('subscription not acknowledged');
      const f = await sub.until((x) => x.data?.type === 'ping', 75000);
      if (Object.keys(f.data).join() !== 'type') fail(`ping frame has extra keys: ${Object.keys(f.data).join(', ')}`);
      if (client.closed) fail('connection closed right after ping');
      const r = await client.request('server.info', {}, { timeout: 2000 });
      okData(r, 'server.info after ping');
      client.close();
      return `ping at seq ${f.seq}`;
    },
  },
  {
    id: 'conn.idle-close-120s',
    group: 'keepalive',
    title: '(--slow) an idle connection with no subscription is closed after about 120 s',
    needs: ['slow', 'hello'],
    timeout: 150000,
    async run(ctx) {
      const { client } = await ctx.open({ hello: true });
      const closed = await client.waitClosed(135000);
      if (!closed) fail('idle connection still open after 135 s');
      return 'closed when idle';
    },
  },
];

export { isStr, sleep, errCode, encodeFrame };
