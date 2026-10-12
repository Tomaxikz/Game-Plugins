package dev.playermanager.bridge.net;

import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Unix domain socket server using the JDK's {@code java.nio.channels} support (Java 16+). The API is
 * reached reflectively so the class files stay Java 8 compatible.
 */
public final class UnixTransport implements Transport {
    private final ServerSocketChannel server;
    private final Path path;

    private UnixTransport(ServerSocketChannel server, Path path) { this.server = server; this.path = path; }

    /** True if the running JVM supports Unix domain sockets in java.nio (Java 16+). */
    public static boolean isSupported() {
        try {
            StandardProtocolFamily.valueOf("UNIX");
            Class.forName("java.net.UnixDomainSocketAddress");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Removes a stale socket file, binds, restricts permissions to the owner. */
    public static UnixTransport bind(Path socketPath) throws IOException {
        if (!isSupported()) throw new IOException("Unix domain sockets need Java 16+");
        try {
            Files.deleteIfExists(socketPath);
            Class<?> addrClass = Class.forName("java.net.UnixDomainSocketAddress");
            Object addr = addrClass.getMethod("of", Path.class).invoke(null, socketPath);
            ProtocolFamily fam = StandardProtocolFamily.valueOf("UNIX");
            ServerSocketChannel ch = (ServerSocketChannel) ServerSocketChannel.class.getMethod("open", ProtocolFamily.class).invoke(null, fam);
            try {
                ch.bind((SocketAddress) addr);
            } catch (IOException e) {
                ch.close();
                throw e;
            }
            try {
                Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
                // not POSIX, or permissions not applicable
            }
            return new UnixTransport(ch, socketPath);
        } catch (IOException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new IOException("unix socket bind failed: " + e.getCause(), e);
        }
    }

    public String name() { return "unix"; }
    public Integer port() { return null; }

    public Conn accept() throws IOException {
        SocketChannel c = server.accept();
        return new ChannelConn(c, "unix");
    }

    public void close() {
        try { server.close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
