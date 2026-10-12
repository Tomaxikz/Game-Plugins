'use strict';
// .playermanager/config.json: defaults, load, sanitise. Never throws.
const DEFAULTS = {
  enabled_ops: ['*'],
  disabled_actions: [],
  positions_min_interval_ms: 250,
  max_connections: 8,
  tcp_fallback_port: 25590,
  platform: {
    revive_event: null,
    ban: null,
    vehicle_report_interval_ms: 1000,
  },
};

function clampInt(v, min, max, def) {
  if (typeof v !== 'number' || !Number.isInteger(v)) return def;
  return Math.min(max, Math.max(min, v));
}

function sanitise(raw) {
  const r = raw && typeof raw === 'object' ? raw : {};
  const p = r.platform && typeof r.platform === 'object' ? r.platform : {};
  const reviveEvent = typeof p.revive_event === 'string' && /^[A-Za-z0-9_:.\-]{1,128}$/.test(p.revive_event) ? p.revive_event : null;
  return {
    enabled_ops: Array.isArray(r.enabled_ops) && r.enabled_ops.every((x) => typeof x === 'string') ? r.enabled_ops : ['*'],
    disabled_actions: Array.isArray(r.disabled_actions) && r.disabled_actions.every((x) => typeof x === 'string') ? r.disabled_actions : [],
    positions_min_interval_ms: clampInt(r.positions_min_interval_ms, 100, 60000, 250),
    max_connections: clampInt(r.max_connections, 8, 256, 8),
    tcp_fallback_port: clampInt(r.tcp_fallback_port, 1, 65535, 25590),
    platform: {
      revive_event: reviveEvent,
      // Ban has no verified route in v1 (games/fivem.md 8.4): the value is kept but never advertised.
      ban: p.ban ?? null,
      vehicle_report_interval_ms: clampInt(p.vehicle_report_interval_ms, 250, 10000, 1000),
    },
  };
}

// Returns { config, source } where source is 'file' | 'defaults' | 'defaults-unwritable'.
function loadConfig(fs, path, file) {
  let raw = null;
  try {
    raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    if (e.code !== 'ENOENT') { /* unparsable: fall back to defaults, keep file untouched */ }
  }
  const config = sanitise(raw ?? DEFAULTS);
  let source = raw ? 'file' : 'defaults';
  if (!raw) {
    try {
      fs.writeFileSync(file, JSON.stringify(DEFAULTS, null, 2) + '\n');
    } catch (e) {
      source = 'defaults-unwritable';
    }
  }
  return { config, source };
}

module.exports = { DEFAULTS, sanitise, loadConfig };
