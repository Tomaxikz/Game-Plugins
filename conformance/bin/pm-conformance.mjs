#!/usr/bin/env node
// Player Manager bridge conformance client.
//
//   node conformance/bin/pm-conformance.mjs --root <server-root> [options]
//   node conformance/bin/pm-conformance.mjs --socket <path> [options]
//   node conformance/bin/pm-conformance.mjs --tcp host:port --token-file <f> [options]
//
// Exit 0 only when no test failed (skips do not fail the run). Exit 2 on usage errors.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Context } from '../lib/context.mjs';
import { runTests, summarize } from '../lib/runner.mjs';
import { ALL_TESTS } from '../tests/index.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const PROFILE_DIR = join(here, '..', 'profiles');

const USAGE = `usage: pm-conformance.mjs (--root <dir> | --socket <path> | --tcp host:port) [options]

transport:
  --root <dir>          server root; reads <dir>/.playermanager/bridge.json
  --socket <path>       unix socket to use instead of bridge.json
  --tcp <host:port>     tcp to use instead of bridge.json (needs a token)
  --token-file <f>      token file for tcp (default <root>/.playermanager/token)

selection:
  --profile <name>      generic | minecraft-bukkit | minecraft-proxy | minecraft-mod | rust | hytale | fivem
                        (default: chosen from hello.platform)
  --only <id,...>       run only these test ids
  --player <id>         known online player for player-scoped tests (default: first online player)
  --slow                enable the 60 s / 120 s keep-alive and idle tests (about 4 minutes)
  --allow-actions       enable action probes that may touch inventory (expect-mismatch only)
  --list                print test ids and exit

output:
  --json <file>         write a JSON report
  -h, --help            show this help
`;

function parseArgs(argv) {
  const opts = { only: null, slow: false, allowActions: false, list: false, help: false };
  const need = (i, name) => {
    if (i + 1 >= argv.length) throw new Error(`${name} needs a value`);
    return argv[i + 1];
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    switch (a) {
      case '--root': opts.root = need(i++, a); break;
      case '--socket': opts.socket = need(i++, a); break;
      case '--tcp': {
        const v = need(i++, a);
        const idx = v.lastIndexOf(':');
        const port = Number(v.slice(idx + 1));
        if (idx < 1 || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('--tcp expects host:port');
        opts.tcp = { host: v.slice(0, idx), port };
        break;
      }
      case '--token-file': opts.tokenFile = need(i++, a); break;
      case '--profile': opts.profile = need(i++, a); break;
      case '--only': opts.only = need(i++, a).split(',').map((s) => s.trim()).filter(Boolean); break;
      case '--player': opts.player = need(i++, a); break;
      case '--json': opts.json = need(i++, a); break;
      case '--slow': opts.slow = true; break;
      case '--allow-actions': opts.allowActions = true; break;
      case '--list': opts.list = true; break;
      case '-h':
      case '--help': opts.help = true; break;
      default:
        throw new Error(`unknown argument ${a}`);
    }
  }
  return opts;
}

function loadProfile(name) {
  const file = join(PROFILE_DIR, `${name}.json`);
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (err) {
    throw new Error(`cannot load profile ${name} (${file}): ${err.code ?? err.message}`);
  }
}

function autoProfile(platform) {
  for (const name of ['minecraft-bukkit', 'minecraft-proxy', 'minecraft-mod', 'rust', 'hytale', 'fivem']) {
    const p = loadProfile(name);
    if (p.platforms?.includes(platform)) return p;
  }
  return loadProfile('generic');
}

function line(status, id, reason) {
  const tag = status.toUpperCase();
  console.log(`${tag} ${id} — ${reason}`);
}

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error(`pm-conformance: ${err.message}\n${USAGE}`);
    return 2;
  }
  if (opts.help) {
    console.log(USAGE);
    return 0;
  }
  if (opts.list) {
    for (const t of ALL_TESTS) console.log(`${t.id}\t${t.group}\t${t.title}`);
    return 0;
  }
  if (!opts.root && !opts.socket && !opts.tcp) {
    console.error(`pm-conformance: need --root, --socket or --tcp\n${USAGE}`);
    return 2;
  }
  if (opts.only) {
    const known = new Set(ALL_TESTS.map((t) => t.id));
    const unknown = opts.only.filter((id) => !known.has(id));
    if (unknown.length) {
      console.error(`pm-conformance: unknown test id(s): ${unknown.join(', ')}`);
      return 2;
    }
  }

  let fixed = null;
  if (opts.profile) {
    try {
      fixed = loadProfile(opts.profile);
    } catch (err) {
      console.error(`pm-conformance: ${err.message}`);
      return 2;
    }
  }

  const ctx = new Context(opts, fixed ?? loadProfile('generic'));
  ctx.init();
  await ctx.connectMain();
  if (!fixed) ctx.profile = autoProfile(ctx.hello?.platform ?? null);

  const target = ctx.mode === 'unix' ? ctx.target?.path : ctx.target ? `${ctx.target.host}:${ctx.target.port}` : '(none)';
  const head = ctx.hello
    ? `${ctx.hello.game}/${ctx.hello.platform} ${ctx.hello.bridge_version} protocol ${ctx.hello.protocol}`
    : `no hello (${ctx.helloError ?? 'unknown error'})`;
  console.log(`pm-conformance: ${head}; transport ${ctx.mode ?? '?'} ${target}; profile ${ctx.profile.name}`);

  const only = opts.only ? new Set(opts.only) : null;
  const results = await runTests(ALL_TESTS, ctx, {
    only,
    onResult: (r) => line(r.status, r.id, r.reason),
  });

  const summary = summarize(results);
  console.log(`\n${summary.pass} passed, ${summary.fail} failed, ${summary.skip} skipped (of ${summary.total})`);

  if (opts.json) {
    const report = {
      tool: 'pm-conformance',
      generated_at: new Date().toISOString(),
      transport: { mode: ctx.mode, target },
      profile: ctx.profile.name,
      options: { slow: opts.slow, allow_actions: opts.allowActions, only: opts.only, player: opts.player ?? null },
      bridge_json: ctx.bridge ?? null,
      hello: ctx.hello ?? null,
      hello_error: ctx.helloError ?? null,
      summary,
      results,
    };
    mkdirSync(dirname(resolve(opts.json)), { recursive: true });
    writeFileSync(opts.json, `${JSON.stringify(report, null, 2)}\n`);
  }

  ctx.closeAll();
  if (!ctx.hello) return 1;
  return summary.fail === 0 ? 0 : 1;
}

main().then(
  (code) => {
    process.exitCode = code;
  },
  (err) => {
    console.error(`pm-conformance: ${err.stack ?? err}`);
    process.exitCode = 1;
  },
);
