'use strict';
// Worker-thread body for the optional encode offload (see offload.js). Receives { id, obj, max }, replies with the
// length-prefixed msgpack frame as a transferred ArrayBuffer. No FXServer globals are available here.
const { parentPort } = require('worker_threads');
const { encodeFramed } = require('./msgpack');

parentPort.on('message', (m) => {
  if (m && m.hello) { parentPort.postMessage({ hello: true }); return; }
  try {
    const frame = encodeFramed(m.obj, m.max);
    const ab = frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.length);
    parentPort.postMessage({ id: m.id, ab }, [ab]);
  } catch (e) {
    parentPort.postMessage({ id: m.id, error: String(e && e.message) });
  }
});
