// Registry of every conformance test, in run order.

import { tests as bridgeJson } from './bridge-json.mjs';
import { tests as handshake } from './handshake.mjs';
import { tests as ops } from './ops.mjs';
import { tests as data } from './data.mjs';
import { tests as streams } from './streams.mjs';
import { tests as connection } from './connection.mjs';
import { tests as assets } from './assets.mjs';
import { tests as actions } from './actions.mjs';
import { tests as profile } from './profile.mjs';

export const ALL_TESTS = [
  ...bridgeJson,
  ...handshake,
  ...ops,
  ...data,
  ...streams,
  ...connection,
  ...assets,
  ...actions,
  ...profile,
];
