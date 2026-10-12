// Shared state for one conformance run: transport, bridge.json, hello, profile, player, and
// helpers that open connections. Tests receive this as `ctx`.

import { readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { BridgeClient } from './client.mjs';
import { num } from './msgpack.mjs';
import { Skip } from './check.mjs';

export const CLIENT_NAME = '1.0.0-conformance';

export class Context {
  /**
   * @param {object} opts parsed CLI options:
   *   root, socket, tcp {host,port}, tokenFile, profile, player, allowActions, slow, verbose
   * @param {object} profile loaded profile JSON (or null)
   */
  constructor(opts, profile) {
    this.opts = opts;
    this.profile = profile;
    this.root = opts.root ? resolve(opts.root) : null;
    this.bridge = null; // parsed bridge.json
    this.bridgeError = null;
    this.bridgeJsonPath = this.root ? join(this.root, '.playermanager', 'bridge.json') : null;
    this.mode = null; // 'unix' | 'tcp'
    this.target = null; // { path } or { host, port }
    this.token = null;
    this.tokenError = null;
    this.transportError = null;
    this.main = null;
    this.hello = null;
    this.helloResp = null;
    this.helloError = null;
    this.tracked = new Set();
    this._player = undefined;
    this.started = Date.now();
  }

  get caps() {
    return this.hello?.capabilities ?? null;
  }

  /** Reads bridge.json (if --root) and fixes the transport target and token. */
  init() {
    if (this.bridgeJsonPath) {
      try {
        this.bridge = JSON.parse(readFileSync(this.bridgeJsonPath, 'utf8'));
      } catch (err) {
        this.bridgeError = err.code === 'ENOENT' ? `missing ${this.bridgeJsonPath}` : `cannot read bridge.json: ${err.message}`;
      }
    }
    const { opts } = this;
    if (opts.socket) {
      this.mode = 'unix';
      this.target = { path: resolve(opts.socket) };
    } else if (opts.tcp) {
      this.mode = 'tcp';
      this.target = { host: opts.tcp.host, port: opts.tcp.port };
    } else if (this.bridge) {
      if (this.bridge.transport === 'unix' && typeof this.bridge.socket === 'string') {
        this.mode = 'unix';
        this.target = { path: join(this.root, this.bridge.socket) };
      } else if (this.bridge.transport === 'tcp' && Number.isInteger(this.bridge.port)) {
        this.mode = 'tcp';
        this.target = { host: '127.0.0.1', port: this.bridge.port };
      } else {
        this.transportError = 'bridge.json has no usable transport';
      }
    } else {
      this.transportError = this.bridgeError ?? 'no transport: pass --root, --socket or --tcp';
    }
    if (this.mode === 'tcp') {
      const tokenFile = opts.tokenFile ?? (this.root ? join(this.root, '.playermanager', 'token') : null);
      if (!tokenFile) {
        this.tokenError = 'tcp mode needs --token-file';
      } else {
        try {
          this.token = readFileSync(tokenFile, 'utf8').trim();
        } catch (err) {
          this.tokenError = `cannot read token file ${tokenFile}: ${err.code ?? err.message}`;
        }
      }
    }
  }

  /** Opens a connection (not tracked as main). */
  async connectRaw() {
    if (this.transportError) throw new Error(this.transportError);
    const opts = this.mode === 'unix' ? { path: this.target.path } : { host: this.target.host, port: this.target.port };
    const client = await BridgeClient.connect(opts, { timeout: 3000 });
    this.tracked.add(client);
    return client;
  }

  helloArgs({ token = this.token, withToken = true } = {}) {
    const args = { panel_version: CLIENT_NAME };
    if (this.mode === 'tcp' && withToken && token !== null && token !== undefined) args.token = token;
    return args;
  }

  /**
   * Opens a connection; if `hello` is true, performs hello (with token in tcp mode).
   * Returns { client, resp } where resp is the hello envelope (or null).
   */
  async open({ hello = true, token, withToken = true } = {}) {
    const client = await this.connectRaw();
    if (!hello) return { client, resp: null };
    const resp = await client.request('hello', this.helloArgs({ token, withToken }));
    return { client, resp };
  }

  /** Main connection used for simple request/response calls. Re-established after closes. */
  async mainClient() {
    if (this.main && !this.main.closed) return this.main;
    const { client, resp } = await this.open({ hello: true });
    this.main = client;
    if (resp.ok) {
      this.helloResp = resp;
      this.hello = resp.data;
    } else {
      this.helloError = `${resp.error?.code}: ${resp.error?.message}`;
    }
    return client;
  }

  /** Performs the first connection and hello. Called once before the test run. */
  async connectMain() {
    if (this.transportError) {
      this.helloError = this.transportError;
      return;
    }
    if (this.mode === 'tcp' && this.tokenError) {
      this.helloError = this.tokenError;
      return;
    }
    try {
      await this.mainClient();
    } catch (err) {
      this.helloError = `connect failed: ${err.message}`;
    }
    if (!this.hello && !this.helloError) this.helloError = 'hello not answered';
  }

  /** Request on the main connection; reconnects when needed. */
  async call(op, args, opts) {
    const client = await this.mainClient();
    if (!this.hello) throw new Skip(this.helloError ?? 'hello failed');
    return client.request(op, args, opts);
  }

  has(op) {
    return Boolean(this.caps?.ops?.includes(op));
  }

  features() {
    return this.caps?.features ?? [];
  }

  /** The online player used by player-scoped tests, or null. Cached. */
  getPlayer() {
    if (this._player === undefined) {
      this._player = this._findPlayer();
    }
    return this._player;
  }

  async _findPlayer() {
    if (!this.hello || !this.has('players.list')) return null;
    const resp = await this.call('players.list', {});
    if (!resp.ok) return null;
    const list = resp.data?.players ?? [];
    const online = list.filter((p) => p && p.online === true);
    if (this.opts.player) {
      const hit = online.find((p) => p.id === this.opts.player);
      if (!hit) return null;
      return { id: hit.id, name: hit.name };
    }
    const first = online[0];
    return first ? { id: first.id, name: first.name } : null;
  }

  /**
   * Returns the reason a test cannot run (a string), or null when all requirements are met.
   * Requirement names: root, hello, unix, tcp, slow, actions, player, op:<name>,
   * feature:<name>, event:<name>, profile:<check>.
   */
  async unmet(needs = []) {
    for (const n of needs) {
      if (n === 'root') {
        if (!this.root) return 'needs --root (bridge.json)';
      } else if (n === 'hello') {
        if (!this.hello) return `hello failed: ${this.helloError ?? 'unknown'}`;
      } else if (n === 'unix' || n === 'tcp') {
        if (this.mode !== n) return `only applies to ${n} transport`;
      } else if (n === 'slow') {
        if (!this.opts.slow) return 'needs --slow';
      } else if (n === 'actions') {
        if (!this.opts.allowActions) return 'needs --allow-actions';
      } else if (n === 'player') {
        if (!this.hello) return `hello failed: ${this.helloError ?? 'unknown'}`;
        if (!(await this.getPlayer())) {
          return this.opts.player ? `player ${this.opts.player} is not online` : 'no online player';
        }
      } else if (n.startsWith('op:')) {
        if (!this.hello) return `hello failed: ${this.helloError ?? 'unknown'}`;
        if (!this.has(n.slice(3))) return `${n.slice(3)} not advertised`;
      } else if (n.startsWith('feature:')) {
        if (!this.hello) return `hello failed: ${this.helloError ?? 'unknown'}`;
        if (!this.features().includes(n.slice(8))) return `feature ${n.slice(8)} not advertised`;
      } else if (n.startsWith('event:')) {
        if (!this.hello) return `hello failed: ${this.helloError ?? 'unknown'}`;
        if (!(this.caps?.events ?? []).includes(n.slice(6))) return `event ${n.slice(6)} not advertised`;
      } else if (n.startsWith('profile:')) {
        const check = n.slice(8);
        if (!this.profile?.checks?.includes(check)) return `profile ${this.profile?.name ?? 'none'} does not define ${check}`;
      } else {
        throw new Error(`unknown requirement ${n}`);
      }
    }
    return null;
  }

  /** Closes every tracked connection except the main one. */
  closeTests() {
    for (const c of this.tracked) {
      if (c !== this.main) {
        c.destroy();
        this.tracked.delete(c);
      }
    }
  }

  closeAll() {
    for (const c of this.tracked) c.destroy();
    this.tracked.clear();
    this.main = null;
  }

  /** Game the bridge reports, or null. */
  get game() {
    return this.hello?.game ?? null;
  }

  /** Token file mode check helper. */
  tokenFileMode() {
    if (!this.root) return null;
    try {
      return statSync(join(this.root, '.playermanager', 'token')).mode & 0o777;
    } catch {
      return null;
    }
  }

  /** Number helper for JSON-decoded values that may be PmFloat. */
  n(v) {
    return num(v);
  }
}
