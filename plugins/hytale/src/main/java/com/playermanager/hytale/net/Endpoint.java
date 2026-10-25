package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.wire.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where the bridge listens: a Unix domain socket in {@code .playermanager/}, or TCP with a token when AF_UNIX is
 * unavailable (docs/protocol/README.md §2). Owns the bound socket, {@code bridge.json} and the TCP token.
 */
final class Endpoint implements AutoCloseable {
    private final Path dir;
    private final BridgeLog log;
    private final int tcpPort;
    private final String platformVersion;
    private final String bridgeVersion;
    private final AtomicInteger injectedFailures = new AtomicInteger();

    private volatile ServerSocketChannel server;
    private volatile boolean tcp;
    private volatile byte[] token;

    Endpoint(Path dir, BridgeLog log, int tcpPort, String platformVersion, String bridgeVersion) {
        this.dir = dir;
        this.log = log;
        this.tcpPort = tcpPort;
        this.platformVersion = platformVersion;
        this.bridgeVersion = bridgeVersion;
    }

    /**
     * Binds the socket (Unix first, TCP+token fallback) and writes bridge.json. Throws on total failure.
     * {@code forceTcp} is a test hook that acts as a runtime without AF_UNIX.
     */
    void bind(boolean forceTcp) throws IOException {
        Files.createDirectories(dir);
        Path sock = dir.resolve("bridge.sock");
        try {
            if (forceTcp) {
                throw new UnsupportedOperationException("AF_UNIX disabled for test");
            }
            Files.deleteIfExists(sock);
            ServerSocketChannel ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            ch.bind(UnixDomainSocketAddress.of(sock));
            server = ch;
            tcp = false;
            writeBridgeJson("unix", ".playermanager/bridge.sock", null);
        } catch (IOException | UnsupportedOperationException e) {
            log.error("Unix socket unavailable, falling back to TCP with token", e);
            ServerSocketChannel ch = ServerSocketChannel.open();
            ch.bind(new InetSocketAddress(tcpPort));
            server = ch;
            tcp = true;
            token = loadOrCreateToken(dir.resolve("token"));
            writeBridgeJson("tcp", null, ((InetSocketAddress) ch.getLocalAddress()).getPort());
        }
    }

    boolean tcp() {
        return tcp;
    }

    boolean isOpen() {
        ServerSocketChannel s = server;
        return s != null && s.isOpen();
    }

    /** Blocks for the next client. TCP clients get TCP_NODELAY: replies are small request/response frames. */
    SocketChannel accept() throws IOException {
        if (injectedFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IOException("injected accept failure");
        }
        SocketChannel c = server.accept();
        if (tcp) {
            try {
                c.setOption(StandardSocketOptions.TCP_NODELAY, true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // an option, not a requirement
            }
        }
        return c;
    }

    /** Test hook: the next {@code n} calls to {@link #accept} fail with an IOException before touching the socket. */
    void failNextAccepts(int n) {
        injectedFailures.set(n);
    }

    boolean tokenMatches(Object presented) {
        byte[] expected = token;
        if (!(presented instanceof String s) || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBridgeJson(String transport, String socket, Integer port) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocol", Dispatcher.PROTOCOL);
        m.put("transport", transport);
        m.put("socket", socket);
        m.put("port", port);
        m.put("platform", "hytale");
        m.put("platform_version", platformVersion);
        m.put("game", "hytale");
        m.put("game_version", platformVersion);
        m.put("bridge_version", bridgeVersion);
        m.put("pid", ProcessHandle.current().pid());
        m.put("started_at", System.currentTimeMillis() / 1000L);
        Path tmp = dir.resolve("bridge.json.tmp");
        Files.writeString(tmp, Json.write(m) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("bridge.json"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] loadOrCreateToken(Path file) throws IOException {
        if (Files.exists(file)) {
            return Files.readString(file, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String hex = HexFormat.of().formatHex(raw);
        Files.writeString(file, hex + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        return hex.getBytes(StandardCharsets.UTF_8);
    }

    /** Closes the listening socket and removes the socket file. */
    @Override
    public void close() {
        try {
            ServerSocketChannel s = server;
            if (s != null) {
                s.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
        try {
            Files.deleteIfExists(dir.resolve("bridge.sock"));
        } catch (IOException ignored) {
            // leave it; next start removes stale sockets
        }
    }
}
