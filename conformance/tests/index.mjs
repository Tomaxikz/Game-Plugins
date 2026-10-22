// Registry of every conformance test, in run order.

import { tests as bridgeJson } from './bridge-json.mjs';
import { tests as handshake } from './handshake.mjs';
import { tests as ops } from './ops.mjs';
import { tests as data } from './data.mjs';
import { tests as streams } from './streams.mjs';
import { tests as connection } from './connection.mjs';
import { tests as chunks } from './chunks.mjs';
import { tests as volume } from './volume.mjs';
import { tests as registry } from './registry.mjs';
import { tests as pack } from './pack.mjs';
import { tests as assets } from './assets.mjs';
import { tests as actions } from './actions.mjs';
import { tests as move } from './move.mjs';
import { tests as profile } from './profile.mjs';

export const ALL_TESTS = [
  ...bridgeJson,
  ...handshake,
  ...ops,
  ...data,
  ...streams,
  ...chunks,
  ...volume,
  ...registry,
  ...pack,
  ...connection,
  ...assets,
  ...actions,
  ...move,
  ...profile,
];
