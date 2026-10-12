'use strict';
class BridgeError extends Error {
  constructor(code, message) { super(message || code); this.code = code; }
}
const E = {
  unsupported: (m) => new BridgeError('unsupported', m),
  invalid: (m) => new BridgeError('invalid_args', m),
  notFound: (m) => new BridgeError('not_found', m),
  offline: (m) => new BridgeError('offline', m),
  conflict: (m) => new BridgeError('conflict', m),
  timeout: (m) => new BridgeError('timeout', m),
  busy: (m) => new BridgeError('busy', m),
  internal: (m) => new BridgeError('internal', m),
  unauthorized: (m) => new BridgeError('unauthorized', m),
};
module.exports = { BridgeError, E };
