package dev.playermanager.bridge.support;

import dev.playermanager.bridge.codec.MsgPack;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal protocol client for tests (reader thread + frame queue). */
public class TestClient implements AutoCloseable {
    public final BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
    private final InputStream in;
    private final OutputStream out;
    private final Runnable closer;
    public volatile boolean eof;
    private int nextId = 1;

    private TestClient(InputStream in, OutputStream out, Runnable closer) {
        this.in = in; this.out = out; this.closer = closer;
        Thread t = new Thread(this::readLoop, "test-client-reader");
        t.setDaemon(true);
        t.start();
    }

    public static TestClient unix(Path socket) throws IOException {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(socket));
        InputStream in = new InputStream() {
            public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 0xff; }
            public int read(byte[] b, int off, int len) throws IOException { return ch.read(ByteBuffer.wrap(b, off, len)); }
        };
        OutputStream out = new OutputStream() {
            public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }
            public void write(byte[] b, int off, int len) throws IOException { ByteBuffer bb = ByteBuffer.wrap(b, off, len); while (bb.hasRemaining()) ch.write(bb); }
        };
        return new TestClient(in, out, () -> { try { ch.close(); } catch (IOException ignored) { } });
    }

    public static TestClient tcp(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        return new TestClient(s.getInputStream(), s.getOutputStream(), () -> { try { s.close(); } catch (IOException ignored) { } });
    }

    private void readLoop() {
        try {
            while (true) {
                int b0 = in.read();
                if (b0 < 0) break;
                int len = (b0 << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) { int n = in.read(body, off, len - off); if (n < 0) throw new IOException("eof"); off += n; }
                frames.add(MsgPack.decodeMap(body));
            }
        } catch (Exception ignored) {
        }
        eof = true;
    }

    public synchronized void sendRaw(byte[] body) throws IOException {
        out.write(new byte[] {(byte) (body.length >>> 24), (byte) (body.length >>> 16), (byte) (body.length >>> 8), (byte) body.length});
        out.write(body);
        out.flush();
    }

    public int send(String op, Map<String, Object> args) throws IOException {
        int id = nextId++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("op", op);
        if (args != null) m.put("args", args);
        sendRaw(MsgPack.encode(m));
        return id;
    }

    public Map<String, Object> next(long ms) throws InterruptedException {
        return frames.poll(ms, TimeUnit.MILLISECONDS);
    }

    /** Sends and returns the response with the matching id (stream frames are left in the queue order-insensitively dropped). */
    public Map<String, Object> call(String op, Map<String, Object> args) throws Exception {
        int id = send(op, args);
        return await(id, 8000);
    }

    public Map<String, Object> await(int id, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        java.util.List<Map<String, Object>> keep = new java.util.ArrayList<>();
        try {
            while (System.currentTimeMillis() < end) {
                Map<String, Object> f = frames.poll(100, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                if (f.containsKey("id") && ((Number) f.get("id")).intValue() == id) return f;
                keep.add(f);
            }
        } finally {
            frames.addAll(keep);
        }
        throw new AssertionError("no response for " + id);
    }

    public Map<String, Object> hello() throws Exception {
        return call("hello", java.util.Collections.singletonMap("panel_version", "1.0.0"));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok"))) throw new AssertionError("not ok: " + resp);
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    public static String errorCode(Map<String, Object> resp) {
        if (!Boolean.FALSE.equals(resp.get("ok"))) throw new AssertionError("expected error: " + resp);
        return (String) ((Map<String, Object>) resp.get("error")).get("code");
    }

    @Override public void close() { closer.run(); }
}
