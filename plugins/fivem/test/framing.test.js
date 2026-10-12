'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { FrameDecoder, encodeFrame, MAX_FRAME } = require('../playermanager_bridge/server/lib/framing');

test('frames split across chunks are reassembled', () => {
  const d = new FrameDecoder();
  const a = encodeFrame(Buffer.from('hello'));
  const b = encodeFrame(Buffer.from('world!'));
  const all = Buffer.concat([a, b]);
  const out = [];
  for (let i = 0; i < all.length; i++) out.push(...d.push(all.subarray(i, i + 1)));
  assert.deepEqual(out.map(String), ['hello', 'world!']);
});

test('length prefix is big-endian u32', () => {
  const fr = encodeFrame(Buffer.alloc(258));
  assert.deepEqual([...fr.subarray(0, 4)], [0, 0, 1, 2]);
});

test('frames above the 4 MiB limit are rejected', () => {
  assert.equal(MAX_FRAME, 4 * 1024 * 1024);
  const d = new FrameDecoder();
  const head = Buffer.alloc(4);
  head.writeUInt32BE(MAX_FRAME + 1, 0);
  assert.throws(() => d.push(head), /frame too large/);
  assert.throws(() => encodeFrame(Buffer.alloc(MAX_FRAME + 1)), /frame too large/);
});
