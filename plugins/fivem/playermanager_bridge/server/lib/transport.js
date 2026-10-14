'use strict';
// Socket transport: Unix stream socket first, TCP+token fallback. Framing, per-connection limits, idle/ping timers.
const crypto = require('crypto');
const path = require('path');
const { FrameDecoder, MAX_FRAME } = require('./framing');
const { encodeFramed, decode } = require('./msgpack');
const { E } = require('./errors');
const { MAX_INFLIGHT, IDLE_CLOSE_MS, PING_AFTER_MS } = require('./bridge');
const { writeAtomic } = require('./discovery');

class Transport {
  constructor({ core, net, fs, config, paths, bridgeInfo, relDir, relSocket, forceTcp = false, log = () => {}, now = () => Date.now(), offload = null }) {
    this.offload = offload;
    this.relDir = relDir;
    this.relSocket = relSocket;
    this.forceTcp = forceTcp;
    this.core = core;
    this.net = net;
    this.fs = fs;
    this.config = config;
    this.paths = paths;
    this.info = bridgeInfo; // { platform, platform_version, game, game_version, bridge_version, pid, started_at }
    this.log = log;
    this.now = now;
    this.server = null;
    this.transport = null; // 'unix' | 'tcp'
    this.token = null;
    this.conns = new Set();
    this.timer = null;
    this.nextConnId = 1;
  }

  // Attempts unix, then tcp. Resolves to { transport, socket, port } or null if disabled.
  async start() {
    try { this.fs.mkdirSync(this.paths.dir, { recursive: true }); } catch (e) { this.log('cannot create .playermanager: ' + e.message); return null; }
    const unixOk = this.forceTcp ? false : await this.tryUnix();
    if (this.forceTcp) this.log('unix socket path too long for AF_UNIX; using tcp');
    if (!unixOk) {
      const tcpOk = await this.tryTcp();
      if (!tcpOk) return null;
    }
    return { transport: this.transport };
  }

  listen(server, arg) {
    return new Promise((resolve, reject) => {
      const onErr = (e) => { server.removeListener('listening', onOk); reject(e); };
      const onOk = () => { server.removeListener('error', onErr); resolve(); };
      server.once('error', onErr);
      server.once('listening', onOk);
      server.listen(arg);
    });
  }

  attachServer(server) {
    server.on('connection', (sock) => this.accept(sock));
    server.on('error', (e) => this.log('server error: ' + e.message));
  }

  async tryUnix() {
    let sock = this.paths.socket;
    // A stale socket is removed through the write layer. If that unlink is refused, bind a pid-named socket instead
    // (libuv removes its own pipe on close; bridge.json names the real socket, so the panel reads the actual name).
    if (require('fs').existsSync(sock)) {
      try { this.fs.unlinkSync(sock); } catch (e) {
        if (e.code !== 'ENOENT') {
          sock = path.join(path.dirname(sock), 'bridge-' + process.pid + '.sock');
          this.log('stale socket could not be removed (' + (e.code || e.message) + '); binding ' + path.basename(sock) + ' instead');
          try { require('fs').unlinkSync(sock); } catch (x) { /* new name, normally absent */ }
        }
      }
    }
    this.paths = { ...this.paths, socket: sock };
    this.relSocket = this.relDir + '/' + path.basename(sock);
    const server = this.net.createServer();
    this.attachServer(server);
    try {
      await this.listen(server, sock);
    } catch (e) {
      this.log('unix listen failed (' + e.code + '): ' + e.message + '; falling back to tcp');
      try { server.close(); } catch (x) { /* ignore */ }
      return false;
    }
    try { this.fs.chmodSync(sock, 0o600); } catch (e) { /* best effort */ }
    this.server = server;
    this.transport = 'unix';
    if (!this.writeDiscovery({ transport: 'unix', socket: this.relSocket, port: null })) { this.stop(); return false; }
    return true;
  }

  async tryTcp() {
    this.token = require('./discovery').ensureToken(this.fs, this.paths.token);
    const server = this.net.createServer();
    this.attachServer(server);
    const port = this.config.tcp_fallback_port;
    try {
      await this.listen(server, port);
    } catch (e) {
      this.log('tcp listen on ' + port + ' failed: ' + e.message + '; bridge disabled');
      try { server.close(); } catch (x) { /* ignore */ }
      return false;
    }
    this.server = server;
    this.transport = 'tcp';
    if (!this.writeDiscovery({ transport: 'tcp', socket: null, port })) { this.stop(); return false; }
    return true;
  }

  writeDiscovery(extra) {
    const doc = { protocol: 1, ...extra, dir: this.relDir, platform: this.info.platform, platform_version: this.info.platform_version, game: 'fivem',
      game_version: this.info.game_version, bridge_version: this.info.bridge_version, pid: this.info.pid, started_at: this.info.started_at };
    try {
      writeAtomic(this.fs, this.paths.json, JSON.stringify(doc, null, 2) + '\n');
      return true;
    } catch (e) {
      this.log('cannot write bridge.json (' + e.code + '): ' + e.message);
      return false;
    }
  }

  accept(sock) {
    if (this.conns.size >= this.config.max_connections) { sock.destroy(); return; }
    const conn = this.makeConn(sock);
    this.conns.add(conn);
    this.core.attach(conn);
    this.ensureHousekeeping();
    sock.on('data', (chunk) => this.onData(conn, chunk));
    sock.on('error', () => this.drop(conn));
    sock.on('close', () => this.drop(conn));
  }

  makeConn(sock) {
    const self = this;
    const conn = {
      id: this.nextConnId++,
      sock,
      decoder: new FrameDecoder(MAX_FRAME),
      hello: false,
      positions: null,
      events: null,
      inflight: 0,
      lastActivity: this.now(),
      subSeq: 1,
      closed: false,
      nextSub() { return this.subSeq++; },
      sendRaw(obj) { self.send(conn, obj); },
    };
    return conn;
  }

  drop(conn) {
    if (conn.closed) return;
    conn.closed = true;
    this.conns.delete(conn);
    this.core.detach(conn);
    try { conn.sock.destroy(); } catch (e) { /* ignore */ }
    if (this.conns.size === 0 && this.timer) { clearInterval(this.timer); this.timer = null; }
  }

  // The idle/ping sweep only exists while at least one client is connected: zero timers when nothing is connected.
  ensureHousekeeping() {
    if (this.timer) return;
    this.timer = setInterval(() => this.housekeeping(), 5000);
    if (this.timer.unref) this.timer.unref();
  }

  close(conn) { this.drop(conn); }

  send(conn, obj) {
    if (conn.closed) return;
    let frame;
    try { frame = encodeFramed(obj, MAX_FRAME); } catch (e) { this.log('encode failed: ' + e.message); return; }
    conn.lastActivity = this.now();
    try { conn.sock.write(frame); } catch (e) { this.drop(conn); }
  }

  // Replies for these ops hold no Float wrappers (which would not survive a structured clone) and can be large.
  offloadable(op, payload) {
    if (!this.offload || !this.offload.available) return false;
    if (op === 'players.list') return this.offload.worthIt(payload && payload.players ? payload.players.length : 0);
    return false;
  }

  reply(conn, id, ok, payload, op) {
    if (ok && op && this.offloadable(op, payload)) {
      const obj = { id, ok: true, data: payload };
      this.offload.encodeFramed(obj, MAX_FRAME).then((frame) => {
        if (conn.closed) return;
        if (!frame) { this.send(conn, obj); return; }
        conn.lastActivity = this.now();
        try { conn.sock.write(frame); } catch (e) { this.drop(conn); }
      });
      return;
    }
    if (ok) this.send(conn, { id, ok: true, data: payload });
    else this.send(conn, { id, ok: false, error: { code: payload.code, message: payload.message } });
  }

  onData(conn, chunk) {
    conn.lastActivity = this.now();
    let frames;
    try { frames = conn.decoder.push(chunk); } catch (e) {
      this.reply(conn, 0, false, { code: 'invalid_args', message: e.message });
      this.drop(conn);
      return;
    }
    for (const body of frames) this.onFrame(conn, body);
  }

  onFrame(conn, body) {
    let req;
    try { req = decode(body); } catch (e) { this.reply(conn, 0, false, { code: 'invalid_args', message: 'malformed frame' }); return; }
    if (!req || typeof req !== 'object' || typeof req.id !== 'number' || !Number.isInteger(req.id) || req.id < 0 || req.id > 0xffffffff) {
      this.reply(conn, 0, false, { code: 'invalid_args', message: 'request id must be a u32' });
      return;
    }
    if (this.transport === 'tcp' && !conn.hello) {
      if (req.op !== 'hello' || !this.tokenOk(req.args && req.args.token)) {
        this.reply(conn, req.id, false, { code: 'unauthorized', message: 'token required in first hello' });
        this.drop(conn);
        return;
      }
    }
    if (conn.inflight >= MAX_INFLIGHT) { this.reply(conn, req.id, false, { code: 'busy', message: 'too many requests in flight' }); return; }
    conn.inflight++;
    Promise.resolve()
      .then(() => this.core.handle(conn, req))
      .then((data) => { conn.inflight--; if (!conn.closed) this.reply(conn, req.id, true, data ?? {}, req.op); })
      .catch((err) => {
        conn.inflight--;
        const code = err && err.code && /^[a-z_]+$/.test(err.code) ? err.code : 'internal';
        const message = code === 'internal' ? 'internal error: ' + (err && err.message ? err.message : 'unknown') : (err && err.message) || code;
        if (!conn.closed) this.reply(conn, req.id, false, { code, message: String(message).slice(0, 300) });
      });
  }

  tokenOk(t) {
    if (typeof t !== 'string' || !this.token) return false;
    const a = Buffer.from(t), b = Buffer.from(this.token);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  }

  housekeeping() {
    const t = this.now();
    for (const conn of [...this.conns]) {
      if (t - conn.lastActivity >= IDLE_CLOSE_MS) { this.drop(conn); continue; }
      for (const sub of [conn.events]) {
        if (sub && t - sub.lastFrameAt >= PING_AFTER_MS) this.core.sendEvent(sub, { type: 'ping' });
      }
    }
  }

  stop() {
    if (this.offload) this.offload.stop();
    if (this.timer) { clearInterval(this.timer); this.timer = null; }
    for (const conn of [...this.conns]) this.drop(conn);
    try { if (this.server) this.server.close(); } catch (e) { /* ignore */ }
    this.server = null;
    if (this.transport === 'unix') { try { this.fs.unlinkSync(this.paths.socket); } catch (e) { /* ignore */ } }
  }
}

module.exports = { Transport };
