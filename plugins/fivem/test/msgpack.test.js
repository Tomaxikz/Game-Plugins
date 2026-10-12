'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { encode, decode, f } = require('../playermanager_bridge/server/lib/msgpack');

test('integers use the smallest encoding', () => {
  assert.equal(encode(5)[0], 0x05);
  assert.equal(encode(-5)[0], 0xfb);
  assert.equal(encode(200)[0], 0xcc);
  assert.equal(encode(300)[0], 0xcd);
  assert.equal(encode(70000)[0], 0xce);
  assert.equal(encode(-200)[0], 0xd1);
});

test('floats are float64 only when wrapped or non-integral', () => {
  assert.equal(encode(0.5)[0], 0xcb);
  assert.equal(encode(f(2))[0], 0xcb);
  assert.equal(encode(2)[0], 0x02);
});

test('round trip of nested values and binary', () => {
  const v = { a: [1, -2, 'x'.repeat(40), null, true], b: { c: f(3.25) }, bin: Buffer.from([1, 2, 3]) };
  const d = decode(encode(v));
  assert.equal(d.a[0], 1);
  assert.equal(d.a[1], -2);
  assert.equal(d.a[2].length, 40);
  assert.equal(d.a[3], null);
  assert.equal(d.b.c, 3.25);
  assert.deepEqual([...d.bin], [1, 2, 3]);
});

test('decoder rejects trailing bytes and unknown types', () => {
  assert.throws(() => decode(Buffer.concat([encode(1), Buffer.from([0x01])])));
  assert.throws(() => decode(Buffer.from([0xd4, 0x00, 0x00])));
});
