// Test registry runner. A test is { id, group, title, needs?, timeout?, run(ctx) }.
// run() passes when it returns, fails when it throws (CheckFailed or any Error), and is
// skipped when it throws Skip(reason) or when a requirement in `needs` is unmet.

import { Skip } from './check.mjs';

const DEFAULT_TEST_TIMEOUT = 20000;

function withTimeout(promise, ms, id) {
  let timer;
  const to = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`test exceeded ${ms} ms (${id})`)), ms);
  });
  return Promise.race([promise, to]).finally(() => clearTimeout(timer));
}

/**
 * Runs the tests in order. `onResult(record)` is called after each test with
 * { id, group, title, status: 'pass'|'fail'|'skip', reason, ms }.
 */
export async function runTests(tests, ctx, { only, onResult }) {
  const results = [];
  for (const t of tests) {
    if (only && !only.has(t.id)) continue;
    const started = Date.now();
    let status;
    let reason = '';
    const unmet = await ctx.unmet(t.needs ?? []).catch((err) => `requirement error: ${err.message}`);
    if (unmet) {
      status = 'skip';
      reason = `skipped: ${unmet}`;
    } else {
      try {
        const out = await withTimeout(Promise.resolve().then(() => t.run(ctx)), t.timeout ?? DEFAULT_TEST_TIMEOUT, t.id);
        status = 'pass';
        reason = typeof out === 'string' && out ? out : 'ok';
      } catch (err) {
        if (err instanceof Skip) {
          status = 'skip';
          reason = `skipped: ${err.message}`;
        } else {
          status = 'fail';
          reason = err && err.message ? err.message : String(err);
        }
      } finally {
        ctx.closeTests();
      }
    }
    const rec = { id: t.id, group: t.group, title: t.title, status, reason, ms: Date.now() - started };
    results.push(rec);
    onResult(rec);
  }
  return results;
}

export function summarize(results) {
  const s = { pass: 0, fail: 0, skip: 0, total: results.length };
  for (const r of results) s[r.status] += 1;
  return s;
}
