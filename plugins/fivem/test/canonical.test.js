'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const { canonicalBytes, renderHash, fingerprint } = require('../playermanager_bridge/server/lib/canonical');
const { f } = require('../playermanager_bridge/server/lib/msgpack');

// Hash test vector, corrected per lead decision D17 (the doc's 0xae prefix is malformed: "minecraft:stone" is 15 bytes).
const CORRECT_BYTES = Buffer.from('81a26964af' + '6d696e656372616674' + '3a73746f6e65', 'hex');
const DOC_BYTES = Buffer.from('81a26964ae' + '6d696e656372616674' + '3a73746f6e65', 'hex');

test('D17: canonical bytes for {"id":"minecraft:stone"} are 81 a2 69 64 af ...', () => {
  assert.deepEqual(canonicalBytes({ id: 'minecraft:stone' }), CORRECT_BYTES);
});

test('D17: render hash of {"id":"minecraft:stone"} is 90d892ebcdb67d59', () => {
  assert.equal(renderHash({ id: 'minecraft:stone' }), '90d892ebcdb67d59');
  assert.equal(crypto.createHash('sha256').update(CORRECT_BYTES).digest('hex').slice(0, 16), '90d892ebcdb67d59');
});

test('erratum check: the malformed doc bytes (0xae) hash to the documented 027f2bef638386bd', () => {
  assert.equal(crypto.createHash('sha256').update(DOC_BYTES).digest('hex').slice(0, 16), '027f2bef638386bd');
});

test('integral floats are ints inside hash input; wire float64 is not used for hashing', () => {
  assert.deepEqual(canonicalBytes({ weight: 2 }), canonicalBytes({ weight: f(2) }));
  assert.notDeepEqual(canonicalBytes({ weight: 0.5 }), canonicalBytes({ weight: 1 }));
});

test('map keys are sorted by UTF-8 bytes at every level', () => {
  const a = canonicalBytes({ b: 1, a: { z: 1, y: 2 } });
  const b = canonicalBytes({ a: { y: 2, z: 1 }, b: 1 });
  assert.deepEqual(a, b);
});

test('fingerprint is 64 lowercase hex and ignores undefined keys', () => {
  const fp = fingerprint({ id: 'water', count: 3, metadata: undefined });
  assert.match(fp, /^[0-9a-f]{64}$/);
  assert.equal(fp, fingerprint({ id: 'water', count: 3 }));
});
