'use strict';
// Argument validation. Rejects selectors (@a), command fragments, newlines and control characters.
const { E } = require('./errors');

const LICENSE_RE = /^license:[0-9a-f]{8,64}$/;
const ITEM_RE = /^[A-Za-z0-9_]{1,64}$/;
const JOB_RE = /^[a-z0-9_]{1,32}$/;
const RESOURCE_KEY_RE = /^[a-z0-9_]{1,64}$/;

function hasControl(s) { return /[\x00-\x1f\x7f]/.test(s); }

function reqString(args, key, { max = 256, min = 0, allowEmpty = true } = {}) {
  const v = args[key];
  if (typeof v !== 'string') throw E.invalid(`${key} must be a string`);
  if (v.length > max || v.length < min) throw E.invalid(`${key} length out of range`);
  if (!allowEmpty && v.length === 0) throw E.invalid(`${key} must not be empty`);
  if (hasControl(v)) throw E.invalid(`${key} contains control characters`);
  return v;
}

function plainText(args, key, max = 256) {
  const v = reqString(args, key, { max });
  if (/^@/.test(v) || /[;&|`$]/.test(v)) throw E.invalid(`${key} contains disallowed characters`);
  return v;
}

function optionalNumber(args, key, min, max, { integer = false } = {}) {
  const v = args[key];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== 'number' || !Number.isFinite(v)) throw E.invalid(`${key} must be a number`);
  if (integer && !Number.isInteger(v)) throw E.invalid(`${key} must be an integer`);
  if (v < min || v > max) throw E.invalid(`${key} out of range`);
  return v;
}

function requireNumber(args, key, min, max) {
  const v = optionalNumber(args, key, min, max);
  if (v === undefined) throw E.invalid(`${key} is required`);
  return v;
}

// Player id: FiveM license identifier.
function playerId(v) {
  if (typeof v !== 'string' || !LICENSE_RE.test(v)) throw E.invalid('target must be a license identifier');
  return v;
}

// Numbered slot input: int or decimal string (D4). Named slots are strings from the allowed set.
function slotKey(v) {
  if (typeof v === 'number' && Number.isInteger(v) && v >= 1 && v <= 1000) return v;
  if (typeof v === 'string' && /^[0-9]{1,4}$/.test(v) && Number(v) >= 1 && Number(v) <= 1000) return Number(v);
  throw E.invalid('slot must be an integer slot');
}

function itemName(v) {
  if (typeof v !== 'string' || !ITEM_RE.test(v)) throw E.invalid('item must be an item name');
  return v;
}

function jobName(v) {
  if (typeof v !== 'string' || !JOB_RE.test(v)) throw E.invalid('job must be a job name');
  return v;
}

function resourceKey(v) { return typeof v === 'string' && RESOURCE_KEY_RE.test(v); }

module.exports = { reqString, plainText, optionalNumber, requireNumber, playerId, slotKey, itemName, jobName, hasControl, LICENSE_RE, resourceKey };
