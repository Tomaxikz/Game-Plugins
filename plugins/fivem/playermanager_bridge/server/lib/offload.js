'use strict';
// Optional worker-thread offload for encoding large replies. FXServer only lets a resource spawn worker threads
// after an operator grant in server.cfg (citizenfx FilesystemPermissions.cpp / NodeScriptRuntime.cpp, scope "worker"):
//     add_unsafe_worker_permission playermanager_bridge
// Without the grant `new Worker` throws or errors; the offload then reports unavailable and every caller falls back to
// the synchronous encoder. The worker is spawned lazily on first use and is unref'd, so idle cost is zero.
const path = require('path');

class Offload {
  constructor({ file = path.join(__dirname, 'encode-worker.js'), log = () => {}, minItems = 200, loader = () => require('worker_threads') } = {}) {
    this.file = file;
    this.log = log;
    this.minItems = minItems;
    this.loader = loader;
    this.worker = null;
    this.state = 'idle'; // idle | starting | ready | failed
    this.pending = new Map();
    this.nextId = 1;
  }

  get available() { return this.state !== 'failed'; }

  // Size heuristic used by callers: only large, Float-free payloads are worth the structured-clone round trip.
  worthIt(n) { return n >= this.minItems; }

  start() {
    if (this.state !== 'idle') return;
    this.state = 'starting';
    try {
      const { Worker } = this.loader();
      this.worker = new Worker(this.file);
      if (this.worker.unref) this.worker.unref();
      this.worker.on('message', (m) => this.onMessage(m));
      this.worker.on('error', (e) => this.fail('worker error: ' + (e && e.message)));
      this.worker.on('exit', () => this.fail('worker exited'));
      this.worker.postMessage({ hello: true });
    } catch (e) {
      this.fail('worker threads not permitted (' + (e && e.message) + '); add `add_unsafe_worker_permission playermanager_bridge` to server.cfg to enable the optional encode offload');
    }
  }

  fail(why) {
    if (this.state === 'failed') return;
    if (this.state !== 'idle') this.log('encode offload disabled: ' + why);
    this.state = 'failed';
    for (const p of this.pending.values()) p.resolve(null);
    this.pending.clear();
    try { if (this.worker) this.worker.terminate(); } catch (e) { /* ignore */ }
    this.worker = null;
  }

  onMessage(m) {
    if (m && m.hello) { if (this.state === 'starting') this.state = 'ready'; return; }
    const p = m && this.pending.get(m.id);
    if (!p) return;
    this.pending.delete(m.id);
    p.resolve(m.ab ? Buffer.from(m.ab) : null);
  }

  // Resolves to the framed Buffer, or null when the caller must encode synchronously instead.
  encodeFramed(obj, max) {
    if (this.state === 'idle') this.start();
    if (this.state !== 'ready') return Promise.resolve(null);
    return new Promise((resolve) => {
      const id = this.nextId++;
      this.pending.set(id, { resolve });
      try { this.worker.postMessage({ id, obj, max }); } catch (e) { this.pending.delete(id); resolve(null); }
    });
  }

  stop() { this.fail('stopped'); }
}

module.exports = { Offload };
