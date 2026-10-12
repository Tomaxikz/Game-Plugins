'use strict';
// GTA JOAAT hash (lowercased input). Verified: joaat('weapon_unarmed') = 0xA2719263.
function joaat(s) {
  let h = 0;
  for (const b of Buffer.from(String(s).toLowerCase(), 'utf8')) {
    h = (h + b) >>> 0;
    h = (h + (h << 10)) >>> 0;
    h = (h ^ (h >>> 6)) >>> 0;
  }
  h = (h + (h << 3)) >>> 0;
  h = (h ^ (h >>> 11)) >>> 0;
  h = (h + (h << 15)) >>> 0;
  return h >>> 0;
}

const WEAPON_NAMES = [
  'WEAPON_UNARMED', 'WEAPON_KNIFE', 'WEAPON_NIGHTSTICK', 'WEAPON_HAMMER', 'WEAPON_BAT', 'WEAPON_GOLFCLUB',
  'WEAPON_CROWBAR', 'WEAPON_PISTOL', 'WEAPON_COMBATPISTOL', 'WEAPON_APPISTOL', 'WEAPON_PISTOL50',
  'WEAPON_MICROSMG', 'WEAPON_SMG', 'WEAPON_ASSAULTSMG', 'WEAPON_ASSAULTRIFLE', 'WEAPON_CARBINERIFLE',
  'WEAPON_ADVANCEDRIFLE', 'WEAPON_MG', 'WEAPON_COMBATMG', 'WEAPON_PUMPSHOTGUN', 'WEAPON_SAWNOFFSHOTGUN',
  'WEAPON_ASSAULTSHOTGUN', 'WEAPON_BULLPUPSHOTGUN', 'WEAPON_STUNGUN', 'WEAPON_SNIPERRIFLE',
  'WEAPON_HEAVYSNIPER', 'WEAPON_REMOTESNIPER', 'WEAPON_GRENADELAUNCHER', 'WEAPON_RPG', 'WEAPON_MINIGUN',
  'WEAPON_GRENADE', 'WEAPON_STICKYBOMB', 'WEAPON_SMOKEGRENADE', 'WEAPON_MOLOTOV', 'WEAPON_FIREEXTINGUISHER',
  'WEAPON_PETROLCAN', 'WEAPON_FLARE', 'WEAPON_SNOWBALL', 'WEAPON_BALL', 'WEAPON_PARACHUTE',
];
const PED_NAMES = ['mp_m_freemode_01', 'mp_f_freemode_01'];

const UNARMED = 0xA2719263; // WEAPON_UNARMED, see weapons test; verify on target build
const byHash = new Map();
for (const n of WEAPON_NAMES) byHash.set(joaat(n), n);
for (const n of PED_NAMES) byHash.set(joaat(n), n);

// Returns { hash, name } with hash as unsigned 32-bit int; name null when unknown.
function hashName(h) {
  const u = h >>> 0;
  return { hash: u, name: byHash.get(u) ?? null };
}

function weaponId(h) {
  const u = h >>> 0;
  const n = byHash.get(u);
  return n && n.startsWith('WEAPON_') ? n : String(u);
}

module.exports = { joaat, hashName, weaponId, UNARMED, byHash };
