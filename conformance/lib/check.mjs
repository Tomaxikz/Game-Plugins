// Assertion helpers. A test collects every violation with Check.ok(), then calls done(), which
// throws one error listing the first violations. Skip is thrown to report "skipped: <reason>".

import { isInt, isFloat, num } from './msgpack.mjs';

export class Skip extends Error {
  constructor(reason) {
    super(reason);
    this.name = 'Skip';
  }
}

export class CheckFailed extends Error {
  constructor(message) {
    super(message);
    this.name = 'CheckFailed';
  }
}

export class Check {
  constructor(where = '') {
    this.where = where;
    this.errs = [];
  }

  ok(cond, msg) {
    if (!cond) this.errs.push(msg);
    return Boolean(cond);
  }

  /** Throws CheckFailed listing violations (first 8). */
  done() {
    if (this.errs.length === 0) return;
    const shown = this.errs.slice(0, 8).join('; ');
    const more = this.errs.length > 8 ? ` (+${this.errs.length - 8} more)` : '';
    throw new CheckFailed(`${this.where ? this.where + ': ' : ''}${shown}${more}`);
  }
}

export function fail(msg) {
  throw new CheckFailed(msg);
}

export function skip(reason) {
  throw new Skip(reason);
}

export function assert(cond, msg) {
  if (!cond) throw new CheckFailed(msg);
}

export function isObj(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v) && !(v instanceof Uint8Array);
}

export function isStr(v) {
  return typeof v === 'string';
}

export function isBool(v) {
  return typeof v === 'boolean';
}

/** Integer (msgpack int, not a float). */
export function isIntV(v) {
  return isInt(v) && (typeof v !== 'bigint' || v <= BigInt(Number.MAX_SAFE_INTEGER));
}

/** Any finite number, int or float. */
export function isNum(v) {
  const n = num(v);
  return typeof n === 'number' && Number.isFinite(n);
}

export function isStrArr(v) {
  return Array.isArray(v) && v.every((x) => typeof x === 'string');
}

export function isBin(v) {
  return v instanceof Uint8Array;
}

export function isHex(v, len) {
  return typeof v === 'string' && new RegExp(`^[0-9a-f]{${len}}$`).test(v);
}

export function describe(v) {
  if (v === undefined) return 'undefined';
  if (v === null) return 'null';
  if (isFloat(v)) return `float ${num(v)}`;
  if (Array.isArray(v)) return 'array';
  if (v instanceof Uint8Array) return 'bin';
  if (typeof v === 'object') return 'object';
  return `${typeof v} ${JSON.stringify(typeof v === 'bigint' ? String(v) : v)}`;
}
