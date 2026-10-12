package dev.playermanager.bridge.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Player id helpers (minecraft.md section 2). */
public final class PlayerIds {
    private PlayerIds() {}

    /** UUID v3 of {@code "OfflinePlayer:" + name}, as derived by servers with {@code online-mode=false}. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** True when the UUID is version 3 and equals the offline derivation for {@code name}. */
    public static boolean isOfflineUuid(UUID id, String name) {
        return id.version() == 3 && name != null && id.equals(offlineUuid(name));
    }

    /** Lowercase hyphenated form (README section 6). */
    public static String format(UUID id) { return id.toString().toLowerCase(java.util.Locale.ROOT); }
}
