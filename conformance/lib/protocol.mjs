// Protocol vocabularies from docs/protocol (README, operations.md, items-and-assets.md, games/*).

export const MAX_FRAME = 4194304;

export const KNOWN_OPS = [
  'hello',
  'server.info',
  'players.list',
  'player.get',
  'player.inventory',
  'player.nearby',
  'item.get',
  'positions.subscribe',
  'positions.keyframe',
  'positions.unsubscribe',
  'events.subscribe',
  'events.unsubscribe',
  'action.run',
  'assets.manifest',
  'assets.get',
];

/** Names that are never implemented (games/minecraft.md D3, operations.md) and must answer unsupported. */
export const NEVER_OPS = ['ping', 'worlds.list', 'servers.list', 'chat.subscribe', 'inventory.get'];

export const EVENT_TYPES = ['join', 'leave', 'death', 'chat', 'command', 'advancement', 'damage', 'kick', 'ban'];

export const ERROR_CODES = [
  'unsupported',
  'invalid_args',
  'not_found',
  'offline',
  'unauthorized',
  'busy',
  'timeout',
  'conflict',
  'internal',
];

export const FIELD_KINDS = ['text', 'integer', 'number', 'select', 'identifier', 'target', 'item'];

export const GAMES = ['minecraft_java', 'rust', 'hytale', 'fivem'];

export const PLATFORMS = {
  minecraft_java: ['paper', 'spigot', 'folia', 'velocity', 'bungeecord', 'fabric', 'neoforge', 'forge'],
  rust: ['oxide', 'carbon'],
  hytale: ['hytale'],
  fivem: ['fivem', 'redm'],
};

export const KNOWN_FEATURES = ['positions.stream', 'inventory.write', 'offline_players', 'sensitive', 'assets', 'proxy'];

export const PLAYER_STATES = ['alive', 'dead', 'spectator'];

export const BUILTIN_GROUPS = ['operators', 'banned', 'whitelisted', 'moderators', 'sleeping', 'banned_ips'];

export const EXTENSION_KINDS = ['plugin', 'mod', 'resource'];

export const HEX16 = /^[0-9a-f]{16}$/;
export const HEX64 = /^[0-9a-f]{64}$/;
export const ACTION_ID = /^[a-z][a-z0-9_]*$/;
export const GROUP_NAME = /^[a-z0-9_.-]+$/;
export const UUID_LOWER = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const NAMESPACED = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;

/** Items carry these keys at the top level; null is only allowed for name, lore and model. */
export const ITEM_NULLABLE_KEYS = ['name', 'lore', 'model'];

/** Vanilla-ish game-agnostic item container slot names used by the contract. */
export const MC_EQUIPMENT_SLOTS = ['head', 'chest', 'legs', 'feet', 'offhand'];

export const DEFAULT_TIMEOUT = 3000;

/**
 * A well-formed player id for the game that no server will have online. Used for not_found and
 * for argument-validation probes. Format follows README section 6.
 */
export function fakePlayerId(game) {
  switch (game) {
    case 'rust':
      return '76561199999999999';
    case 'fivem':
      return 'license:0000000000000000000000000000000000000000';
    default:
      return '00000000-0000-4000-8000-000000000000';
  }
}
