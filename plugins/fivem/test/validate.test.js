'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const V = require('../playermanager_bridge/server/lib/validate');

const bad = (fn) => assert.throws(fn, (e) => e.code === 'invalid_args');

test('player ids must be license identifiers (no selectors)', () => {
  assert.equal(V.playerId('license:aaaa1111bbbb2222'), 'license:aaaa1111bbbb2222');
  bad(() => V.playerId('@a'));
  bad(() => V.playerId('1'));
  bad(() => V.playerId('steam:110000112345678'));
  bad(() => V.playerId(undefined));
});

test('plain text rejects control characters, selectors and command fragments', () => {
  bad(() => V.plainText({ m: 'hi\nthere' }, 'm'));
  bad(() => V.plainText({ m: '@everyone' }, 'm'));
  bad(() => V.plainText({ m: 'a; quit' }, 'm'));
  assert.equal(V.plainText({ m: 'Hello there' }, 'm'), 'Hello there');
});

test('numbers are range- and integer-checked', () => {
  bad(() => V.optionalNumber({ a: 1.5 }, 'a', 0, 10, { integer: true }));
  bad(() => V.optionalNumber({ a: 11 }, 'a', 0, 10));
  bad(() => V.optionalNumber({ a: NaN }, 'a', 0, 10));
  assert.equal(V.optionalNumber({ a: 7 }, 'a', 0, 10, { integer: true }), 7);
});

test('slots accept an int or decimal string (D4) and reject hotbar:N', () => {
  assert.equal(V.slotKey(5), 5);
  assert.equal(V.slotKey('12'), 12);
  bad(() => V.slotKey('hotbar:0'));
  bad(() => V.slotKey(0));
});

test('item names and job names are constrained', () => {
  assert.equal(V.itemName('WEAPON_PISTOL'), 'WEAPON_PISTOL');
  bad(() => V.itemName('water;rm'));
  bad(() => V.jobName('Police Dept'));
});
