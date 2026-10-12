package dev.playermanager.bridge.net;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unix domain sockets through junixsocket, for Java &lt; 16. The library is optional: it is only used when it
 * is on the class path (the universal jar can shade + relocate it; string class names below are rewritten
 * by the relocation).
 */
public final class JunixTransport implements Transport {
    private final ServerSocket server;
    private final Path path;

    private JunixTransport(ServerSocket s, Path p) { this.server = s; this.path = p; }

    public static boolean isAvailable() {
        try {
            Class.forName("org.newsclub.net.unix.AFUNIXServerSocket");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static JunixTransport bind(Path socketPath) throws IOException {
        try {
            Files.deleteIfExists(socketPath);
            Class<?> srv = Class.forName("org.newsclub.net.unix.AFUNIXServerSocket");
            Class<?> addrC = Class.forName("org.newsclub.net.unix.AFUNIXSocketAddress");
            ServerSocket s = (ServerSocket) srv.getMethod("newInstance").invoke(null);
            Object addr;
            try {
                addr = addrC.getMethod("of", File.class).invoke(null, socketPath.toFile());
            } catch (NoSuchMethodException e) {
                addr = addrC.getConstructor(File.class).newInstance(socketPath.toFile());
            }
            s.bind((SocketAddress) addr);
            return new JunixTransport(s, socketPath);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException("junixsocket bind failed: " + c, c);
        }
    }

    public String name() { return "unix"; }
    public Integer port() { return null; }

    public Conn accept() throws IOException {
        final Socket s = server.accept();
        final InputStream in = s.getInputStream();
        final OutputStream out = s.getOutputStream();
        return new Conn() {
            public InputStream in() { return in; }
            public OutputStream out() { return out; }
            public void close() { try { s.close(); } catch (IOException ignored) { } }
            public String describe() { return "unix(junixsocket)"; }
        };
    }

    public void close() {
        try { server.close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
