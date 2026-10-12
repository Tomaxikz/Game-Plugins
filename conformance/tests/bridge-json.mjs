// bridge.json, socket path and token file (README sections 1-2).

import { statSync, readdirSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { Check, Skip, fail, isBool, isIntV, isObj, isStr } from '../lib/check.mjs';
import { num } from '../lib/msgpack.mjs';
import { GAMES, PLATFORMS } from '../lib/protocol.mjs';

const SEMVER = /^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$/;

export const tests = [
  {
    id: 'bridge.json.schema',
    group: 'bridge.json',
    title: 'bridge.json has every README section 1 key with the right type and enum values',
    needs: ['root'],
    run(ctx) {
      if (ctx.bridgeError) fail(ctx.bridgeError);
      const b = ctx.bridge;
      const c = new Check('bridge.json');
      c.ok(isObj(b), 'top level must be an object');
      c.ok(isIntV(b.protocol) && num(b.protocol) >= 1, 'protocol must be an int >= 1');
      c.ok(b.transport === 'unix' || b.transport === 'tcp', 'transport must be unix or tcp');
      if (b.transport === 'unix') {
        c.ok(isStr(b.socket) && b.socket.length > 0, 'unix transport needs a socket string');
        c.ok(b.port === null, 'unix transport must have port null');
      }
      if (b.transport === 'tcp') {
        c.ok(b.socket === null, 'tcp transport must have socket null');
        c.ok(isIntV(b.port) && num(b.port) >= 1 && num(b.port) <= 65535, 'tcp transport needs port 1..65535');
      }
      c.ok(GAMES.includes(b.game), `game must be one of ${GAMES.join('|')}`);
      c.ok(Object.values(PLATFORMS).flat().includes(b.platform), 'platform must be a documented platform');
      if (GAMES.includes(b.game)) c.ok(PLATFORMS[b.game].includes(b.platform), `platform ${b.platform} is not valid for game ${b.game}`);
      c.ok(isStr(b.platform_version), 'platform_version must be a string');
      c.ok(isStr(b.game_version), 'game_version must be a string');
      c.ok(isStr(b.bridge_version) && SEMVER.test(b.bridge_version), 'bridge_version must be semver');
      c.ok(isIntV(b.pid) && num(b.pid) > 0, 'pid must be a positive int');
      c.ok(isIntV(b.started_at) && num(b.started_at) > 1_600_000_000, 'started_at must be unix seconds');
      c.done();
      const known = ['protocol', 'transport', 'socket', 'port', 'platform', 'platform_version', 'game', 'game_version', 'bridge_version', 'pid', 'started_at'];
      const extra = Object.keys(b).filter((k) => !known.includes(k));
      return `all ${known.length} keys valid${extra.length ? `; unknown keys ignored: ${extra.join(',')}` : ''}`;
    },
  },
  {
    id: 'bridge.json.socket-path',
    group: 'bridge.json',
    title: 'unix socket path is relative to the server root, inside it, short enough, and is a socket',
    needs: ['root', 'unix'],
    run(ctx) {
      const s = ctx.bridge.socket;
      const c = new Check('socket');
      c.ok(!isAbsolute(s), 'socket must be relative to the server root');
      c.ok(!s.split('/').includes('..'), 'socket must not contain ..');
      c.ok(s.startsWith('.playermanager/'), 'socket should live under .playermanager/');
      const abs = join(ctx.root, s);
      c.ok(Buffer.byteLength(abs) < 108, `absolute socket path is ${Buffer.byteLength(abs)} bytes (must be < 108)`);
      let st = null;
      try {
        st = statSync(abs);
      } catch {
        c.ok(false, `socket file ${abs} does not exist`);
      }
      if (st) c.ok(st.isSocket(), `${abs} exists but is not a socket`);
      c.done();
      return `socket ${s}`;
    },
  },
  {
    id: 'bridge.json.atomic',
    group: 'bridge.json',
    title: 'bridge.json is complete and no half-written temp files are left behind',
    needs: ['root'],
    run(ctx) {
      const dir = join(ctx.root, '.playermanager');
      const c = new Check('atomic');
      c.ok(ctx.bridgeError === null, ctx.bridgeError ?? '');
      let entries = [];
      try {
        entries = readdirSync(dir);
      } catch (err) {
        fail(`cannot list ${dir}: ${err.code ?? err.message}`);
      }
      const now = Date.now();
      for (const name of entries) {
        if (!/(\.tmp|\.tmp\d*|^\.?bridge\.json\.)/.test(name) || name === 'bridge.json') continue;
        const age = now - statSync(join(dir, name)).mtimeMs;
        c.ok(age < 5000, `stale temp file ${name} (${Math.round(age / 1000)} s old): writes must be temp+rename`);
      }
      c.done();
      return 'no stale temp files';
    },
  },
  {
    id: 'bridge.json.token',
    group: 'bridge.json',
    title: 'token file (tcp): 64 lowercase hex chars, mode 0600',
    needs: ['root'],
    run(ctx) {
      const mode = ctx.tokenFileMode();
      if (ctx.mode === 'unix' && mode === null) return 'unix transport: no token file (not required)';
      if (mode === null) fail('token file missing');
      const c = new Check('token');
      c.ok((mode & 0o077) === 0, `token mode is ${mode.toString(8)}, must be 0600 (no group/other access)`);
      c.ok(ctx.token !== null && /^[0-9a-f]{64}$/.test(ctx.token), 'token must be 64 lowercase hex characters');
      c.done();
      return 'token well formed';
    },
  },
  {
    id: 'bridge.json.transport-used',
    group: 'bridge.json',
    title: 'the transport bridge.json advertises is the one that answers hello',
    needs: ['root', 'hello'],
    run(ctx) {
      if (ctx.opts.socket || ctx.opts.tcp) throw new Skip('--socket/--tcp overrides bridge.json');
      if (ctx.bridge.transport !== ctx.mode) fail(`bridge.json transport ${ctx.bridge.transport} but connected via ${ctx.mode}`);
      return `transport ${ctx.mode}`;
    },
  },
];

export { isBool };
