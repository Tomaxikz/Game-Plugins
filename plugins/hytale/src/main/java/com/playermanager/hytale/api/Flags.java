package com.playermanager.hytale.api;

/** Results of the startup probes: one flag per independently advertised capability. */
record Flags(boolean base, boolean position, boolean health, boolean gameMode, boolean inventory,
             boolean heldItem, boolean equipment, boolean ping, boolean ip, boolean permissions,
             boolean nearby, boolean kick, boolean ban, boolean unban, boolean whitelist, boolean op,
             boolean kill, boolean heal, boolean teleport, boolean give, boolean clear,
             boolean evJoin, boolean evLeave, boolean evChat, boolean evDeath) {
}
