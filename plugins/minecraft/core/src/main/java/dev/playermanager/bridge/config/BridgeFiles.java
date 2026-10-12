package dev.playermanager.bridge.config;

import dev.playermanager.bridge.codec.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Paths and file handling of {@code <server root>/.playermanager/} (README section 1). */
public final class BridgeFiles {
    public final Path root;
    public final Path dir;
    public final Path socket;
    public final Path bridgeJson;
    public final Path token;
    public final Path config;
    public final Path cache;

    public BridgeFiles(Path serverRoot) {
        this.root = serverRoot.toAbsolutePath().normalize();
        this.dir = root.resolve(".playermanager");
        this.socket = dir.resolve("bridge.sock");
        this.bridgeJson = dir.resolve("bridge.json");
        this.token = dir.resolve("token");
        this.config = dir.resolve("config.json");
        this.cache = dir.resolve("cache");
    }

    public void ensureDirs() throws IOException { Files.createDirectories(dir); }

    /** Server-root-relative socket path with forward slashes, as written to bridge.json. */
    public String relativeSocket() { return ".playermanager/bridge.sock"; }

    /** Reads the TCP token, generating 32 random bytes (64 lowercase hex, mode 0600) once. */
    public String loadOrCreateToken() throws IOException {
        if (Files.isRegularFile(token)) {
            String t = new String(Files.readAllBytes(token), StandardCharsets.UTF_8).trim();
            if (t.matches("[0-9a-f]{64}")) return t;
        }
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String t = dev.playermanager.bridge.codec.Hashes.hex(b);
        atomicWrite(token, t.getBytes(StandardCharsets.UTF_8));
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(token, perms);
        } catch (Exception ignored) {
            // non-POSIX filesystem
        }
        return t;
    }

    public void writeBridgeJson(String transport, Integer port, String platform, String platformVersion, String gameVersion, String bridgeVersion) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("protocol", 1);
        m.put("transport", transport);
        m.put("socket", "unix".equals(transport) ? relativeSocket() : null);
        m.put("port", port);
        m.put("platform", platform);
        m.put("platform_version", platformVersion);
        m.put("game", "minecraft_java");
        m.put("game_version", gameVersion);
        m.put("bridge_version", bridgeVersion);
        m.put("pid", currentPid());
        m.put("started_at", System.currentTimeMillis() / 1000L);
        atomicWrite(bridgeJson, Json.writePretty(m).getBytes(StandardCharsets.UTF_8));
    }

    public void deleteQuietly(Path p) { try { Files.deleteIfExists(p); } catch (Exception ignored) { } }

    public static long currentPid() {
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(n.substring(0, n.indexOf('@')));
        } catch (Exception e) {
            return 0;
        }
    }

    public static void atomicWrite(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
