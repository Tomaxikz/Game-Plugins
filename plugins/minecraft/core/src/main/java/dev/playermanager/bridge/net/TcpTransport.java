package dev.playermanager.bridge.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** TCP fallback transport (all interfaces). Callers must require the token in {@code hello}. */
public final class TcpTransport implements Transport {
    private final ServerSocket server;

    private TcpTransport(ServerSocket s) { this.server = s; }

    /** Binds {@code port}, trying up to 20 following ports if taken. {@code loopbackOnly} is for tests. */
    public static TcpTransport bind(int port, boolean loopbackOnly) throws IOException {
        IOException last = null;
        for (int p = port; p < port + 20 && p <= 65535; p++) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(true);
                s.bind(loopbackOnly ? new InetSocketAddress("127.0.0.1", p) : new InetSocketAddress(p));
                return new TcpTransport(s);
            } catch (IOException e) {
                last = e;
                try { s.close(); } catch (IOException ignored) { }
            }
        }
        throw last != null ? last : new IOException("no free port");
    }

    public String name() { return "tcp"; }
    public Integer port() { return server.getLocalPort(); }

    public Conn accept() throws IOException {
        final Socket s = server.accept();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        final InputStream in = s.getInputStream();
        final OutputStream out = s.getOutputStream();
        final String d = "tcp:" + s.getRemoteSocketAddress();
        return new Conn() {
            public InputStream in() { return in; }
            public OutputStream out() { return out; }
            public void close() { try { s.close(); } catch (IOException ignored) { } }
            public String describe() { return d; }
        };
    }

    public void close() { try { server.close(); } catch (IOException ignored) { } }
}
