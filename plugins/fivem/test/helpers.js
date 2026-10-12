'use strict';
const net = require('net');
const { encode, decode } = require('../playermanager_bridge/server/lib/msgpack');
const { encodeFrame, FrameDecoder } = require('../playermanager_bridge/server/lib/framing');

// Minimal protocol client: request/response by id, stream frames collected separately.
function connect(target) {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection(target);
    const dec = new FrameDecoder();
    const responses = new Map();
    const frames = [];
    const waiters = [];
    let nextId = 1;
    sock.on('data', (chunk) => {
      for (const body of dec.push(chunk)) {
        const m = decode(body);
        if (m.sub !== undefined) { frames.push(m); for (const w of waiters.splice(0)) w(); }
        else if (responses.has(m.id)) { const r = responses.get(m.id); responses.delete(m.id); r(m); }
        else responses.set('orphan' + m.id, m);
      }
    });
    sock.on('error', reject);
    sock.on('close', () => {});
    const api = {
      sock, frames,
      call(op, args = {}, timeout = 5000) {
        const id = nextId++;
        sock.write(encodeFrame(encode({ id, op, args })));
        return new Promise((res, rej) => {
          const t = setTimeout(() => { responses.delete(id); rej(new Error('timeout waiting for ' + op)); }, timeout);
          responses.set(id, (m) => { clearTimeout(t); res(m); });
        });
      },
      raw(obj) { sock.write(encodeFrame(encode(obj))); },
      waitFrame(pred, timeout = 3000) {
        return new Promise((res, rej) => {
          const check = () => {
            const i = frames.findIndex(pred);
            if (i >= 0) { res(frames.splice(i, 1)[0]); return true; }
            return false;
          };
          if (check()) return;
          const timer = setTimeout(() => rej(new Error('no matching stream frame')), timeout);
          const w = () => { if (check()) clearTimeout(timer); else waiters.push(w); };
          waiters.push(w);
        });
      },
      close() { sock.destroy(); },
    };
    sock.once('connect', () => resolve(api));
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { connect, sleep };
