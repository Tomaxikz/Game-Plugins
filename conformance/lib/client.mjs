// Protocol client: request/response by id (responses may arrive out of order), subscriptions
// with seq tracking, raw frame access for negative tests.

import net from 'node:net';
import { EventEmitter } from 'node:events';
import { encodeFrame, FrameReader, FrameError, decodeBody } from './framing.mjs';
import { num } from './msgpack.mjs';

const U32_MAX = 0xffffffff;

export class TimeoutError extends Error {
  constructor(what, ms) {
    super(`timeout after ${ms} ms waiting for ${what}`);
    this.name = 'TimeoutError';
    this.code = 'timeout';
  }
}

/** A subscription opened on one connection. Frames are kept in order; `next()` consumes them. */
export class Subscription {
  constructor(sub, op) {
    this.sub = sub;
    this.op = op;
    this.nextSeq = 0;
    this.frames = []; // all frames received, in order
    this.times = []; // Date.now() at receipt, parallel to frames
    this.gaps = []; // {expected, got} seq discontinuities
    this.unread = [];
    this.waiters = [];
    this.closed = false;
  }

  _push(msg) {
    const seq = num(msg.seq);
    if (seq !== this.nextSeq) this.gaps.push({ expected: this.nextSeq, got: msg.seq });
    this.nextSeq = (typeof seq === 'number' ? seq : this.nextSeq) + 1;
    this.frames.push(msg);
    this.times.push(Date.now());
    this.unread.push(msg);
    const w = this.waiters.shift();
    if (w) w.resolve(this.unread.shift());
  }

  /** Next frame not yet consumed; rejects with TimeoutError. */
  next(timeout = 3000) {
    if (this.unread.length > 0) return Promise.resolve(this.unread.shift());
    return new Promise((resolve, reject) => {
      const waiter = {
        resolve: (m) => {
          clearTimeout(timer);
          resolve(m);
        },
      };
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((x) => x !== waiter);
        reject(new TimeoutError(`frame on sub ${this.sub}`, timeout));
      }, timeout);
      this.waiters.push(waiter);
    });
  }

  /** Consume frames until `pred` matches; returns the matching frame. */
  async until(pred, timeout = 3000) {
    const deadline = Date.now() + timeout;
    for (;;) {
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw new TimeoutError(`matching frame on sub ${this.sub}`, timeout);
      const f = await this.next(remaining);
      if (pred(f)) return f;
    }
  }
}

export class BridgeClient extends EventEmitter {
  constructor(socket, { maxFrame } = {}) {
    super();
    this.socket = socket;
    this.reader = new FrameReader({ maxFrame });
    this.pending = new Map();
    this.subs = new Map();
    this.orphans = []; // stream frames for unknown subs
    this.unmatched = []; // responses for unknown ids
    this.protocolErrors = []; // decoded messages that are neither responses nor stream frames
    this.decodeErrors = [];
    this.frameError = null;
    this.lastId = 0;
    this.closed = false;
    this.closeInfo = null;
    this.closedPromise = new Promise((resolve) => {
      this._resolveClosed = resolve;
    });
    socket.on('data', (chunk) => this._onData(chunk));
    socket.on('error', (err) => {
      this.socketError = err;
    });
    socket.on('close', (hadError) => this._onClose(hadError));
  }

  /**
   * Connect. opts: { path } for a unix socket, or { host, port } for TCP.
   */
  static connect(opts, { timeout = 3000, maxFrame } = {}) {
    return new Promise((resolve, reject) => {
      const socket = opts.path !== undefined ? net.createConnection({ path: opts.path }) : net.createConnection({ host: opts.host, port: opts.port });
      const timer = setTimeout(() => {
        socket.destroy();
        reject(new TimeoutError('connect', timeout));
      }, timeout);
      socket.once('connect', () => {
        clearTimeout(timer);
        socket.setNoDelay(true);
        resolve(new BridgeClient(socket, { maxFrame }));
      });
      socket.once('error', (err) => {
        clearTimeout(timer);
        reject(err);
      });
    });
  }

  _onData(chunk) {
    let bodies;
    try {
      bodies = this.reader.push(chunk);
    } catch (err) {
      this.frameError = err;
      this.socket.destroy();
      return;
    }
    for (const body of bodies) {
      let msg;
      try {
        msg = decodeBody(body);
      } catch (err) {
        this.decodeErrors.push(err);
        continue;
      }
      this._route(msg);
    }
  }

  _route(msg) {
    if (msg && typeof msg === 'object' && !Array.isArray(msg)) {
      if ('id' in msg) {
        const id = num(msg.id);
        const p = this.pending.get(id);
        if (!p) {
          this.unmatched.push(msg);
          return;
        }
        this.pending.delete(id);
        clearTimeout(p.timer);
        if (msg.ok === true && p.op.endsWith('.subscribe') && msg.data && typeof msg.data === 'object') {
          const s = num(msg.data.sub);
          if (typeof s === 'number' && !this.subs.has(s)) {
            const sub = new Subscription(s, p.op);
            this.subs.set(s, sub);
            // Frames pushed to the orphan list before the ack (should not happen) are adopted.
            const early = this.orphans.filter((f) => num(f.sub) === s);
            this.orphans = this.orphans.filter((f) => num(f.sub) !== s);
            for (const f of early) sub._push(f);
          }
        }
        p.resolve(msg);
        return;
      }
      if ('sub' in msg) {
        const sub = this.subs.get(num(msg.sub));
        if (sub) sub._push(msg);
        else this.orphans.push(msg);
        return;
      }
    }
    this.protocolErrors.push(msg);
  }

  _allocId() {
    for (let i = 0; i < 1000; i++) {
      this.lastId = this.lastId >= U32_MAX ? 1 : this.lastId + 1;
      if (!this.pending.has(this.lastId)) return this.lastId;
    }
    throw new Error('no free request id');
  }

  /**
   * Send a request and resolve with the response envelope { id, ok, data | error }.
   * args omitted (undefined) sends no args key.
   */
  request(op, args, { timeout = 3000, id } = {}) {
    const useId = id !== undefined ? id : this._allocId();
    const msg = { id: useId, op };
    if (args !== undefined) msg.args = args;
    return this.requestRaw(msg, { timeout, op });
  }

  /** Send an arbitrary message object (already shaped) and await the response with its id. */
  requestRaw(msg, { timeout = 3000, op } = {}) {
    if (this.closed) return Promise.reject(new Error('connection closed'));
    const id = num(msg.id);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new TimeoutError(`response to ${op ?? msg.op} (id ${id})`, timeout));
      }, timeout);
      this.pending.set(id, { resolve, reject, timer, op: op ?? String(msg.op) });
      try {
        this.socket.write(encodeFrame(msg));
      } catch (err) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(err);
      }
    });
  }

  /** Subscribe: returns { resp, sub } where sub is null if the response was not ok. */
  async subscribe(op, args, opts) {
    const resp = await this.request(op, args, opts);
    const sub = resp.ok === true && resp.data && typeof resp.data === 'object' ? this.subs.get(num(resp.data.sub)) ?? null : null;
    return { resp, sub };
  }

  /** Write raw bytes, bypassing framing. */
  writeRaw(bytes) {
    this.socket.write(bytes);
  }

  /** Encode and write an object without waiting for a response. */
  sendObject(obj) {
    this.socket.write(encodeFrame(obj));
  }

  _onClose(hadError) {
    if (this.closed) return;
    this.closed = true;
    this.closeInfo = { hadError: Boolean(hadError), at: Date.now() };
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer);
      p.reject(new Error('connection closed before response'));
      this.pending.delete(id);
    }
    for (const sub of this.subs.values()) sub.closed = true;
    this._resolveClosed(this.closeInfo);
    this.emit('close', this.closeInfo);
  }

  /** Resolves true if the server closes the connection within `timeout` ms. */
  async waitClosed(timeout) {
    if (this.closed) return true;
    let timer;
    const to = new Promise((resolve) => {
      timer = setTimeout(() => resolve(false), timeout);
    });
    const res = await Promise.race([this.closedPromise.then(() => true), to]);
    clearTimeout(timer);
    return res;
  }

  close() {
    if (this.closed) return;
    try {
      this.socket.end();
    } catch {
      /* ignore */
    }
    const t = setTimeout(() => this.socket.destroy(), 200);
    t.unref();
  }

  destroy() {
    this.socket.destroy();
  }
}

export { FrameError };
