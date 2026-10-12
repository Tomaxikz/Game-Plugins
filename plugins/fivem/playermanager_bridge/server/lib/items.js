'use strict';
// Encoded item record (items-and-assets.md section 1-3) for FiveM.
const { renderHash, fingerprint } = require('./canonical');

// Build a full record with hash + fingerprint. `rec` holds the record keys (no hash/fingerprint).
function finishRecord(rec) {
  const subset = { id: rec.id };
  if (rec.model) subset.model = rec.model;
  if (rec.tint) subset.tint = rec.tint;
  if (rec.skin !== undefined) subset.skin = rec.skin;
  const out = { ...rec };
  out.hash = renderHash(subset);
  out.fingerprint = fingerprint(rec);
  return out;
}

module.exports = { finishRecord };
