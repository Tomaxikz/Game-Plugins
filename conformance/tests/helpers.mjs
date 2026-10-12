// Response helpers shared by the test modules.

import { CheckFailed } from '../lib/check.mjs';
import { ERROR_CODES } from '../lib/protocol.mjs';
import { isObj, isStr } from '../lib/check.mjs';

export function describeResp(resp) {
  if (!resp) return 'no response';
  if (resp.ok === true) return 'ok';
  const code = resp.error?.code ?? '(no code)';
  return `${code}${resp.error?.message ? ` (${resp.error.message})` : ''}`;
}

/** Asserts a success envelope and returns its data object. */
export function okData(resp, what) {
  if (!resp || typeof resp !== 'object') throw new CheckFailed(`${what}: no response`);
  if (resp.ok !== true) throw new CheckFailed(`${what}: expected ok, got ${describeResp(resp)}`);
  if (!isObj(resp.data)) throw new CheckFailed(`${what}: ok response must carry a data object`);
  return resp.data;
}

/** Asserts an error envelope with the given code. Returns the error object. */
export function errCode(resp, code, what) {
  if (!resp || typeof resp !== 'object') throw new CheckFailed(`${what}: no response`);
  if (resp.ok !== false) throw new CheckFailed(`${what}: expected ${code}, got ${describeResp(resp)}`);
  const err = resp.error;
  if (!isObj(err) || !isStr(err.code) || !isStr(err.message)) {
    throw new CheckFailed(`${what}: error must be {code, message}`);
  }
  if (!ERROR_CODES.includes(err.code)) throw new CheckFailed(`${what}: unknown error code ${err.code}`);
  if (err.code !== code) throw new CheckFailed(`${what}: expected ${code}, got ${err.code} (${err.message})`);
  return err;
}

/** Any error envelope: the code must be one of the documented codes. */
export function anyErr(resp, what) {
  if (!resp || resp.ok !== false) throw new CheckFailed(`${what}: expected an error, got ${describeResp(resp)}`);
  if (!isObj(resp.error) || !ERROR_CODES.includes(resp.error.code)) {
    throw new CheckFailed(`${what}: error code ${resp.error?.code} is not a documented code`);
  }
  return resp.error;
}

export function fail(msg) {
  throw new CheckFailed(msg);
}

/** Polls `fn` until it returns truthy or `timeout` ms pass. */
export async function waitFor(fn, timeout = 3000, step = 25) {
  const end = Date.now() + timeout;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() >= end) return null;
    await new Promise((r) => setTimeout(r, step));
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
