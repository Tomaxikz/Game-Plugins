package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The writer side of a connection: wake coalescing, backlog counting, the outbox byte cap and closeAfterFlush. */
@Timeout(120)
class ConnTest {
    @TempDir
    Path dir;

    /** Counts what the connection asks of its stream layer. */
    static final class Listener implements Conn.Listener {
        final AtomicInteger drains = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void drainEvents(Conn conn, BiConsumer<Subs.Sub, Map<String, Object>> out) {
            drains.incrementAndGet();
        }

        @Override
        public void connectionClosed(Conn conn) {
            closes.incrementAndGet();
        }
    }

    /** A connected Unix socket pair: [server side, client side]. */
    private SocketChannel[] pair(String name) throws IOException {
        Path sock = dir.resolve(name + ".sock");
        try (ServerSocketChannel srv = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            srv.bind(UnixDomainSocketAddress.of(sock));
            SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX);
            client.connect(UnixDomainSocketAddress.of(sock));
            return new SocketChannel[] {srv.accept(), client};
        }
    }

    /** A reply frame (length reserved) whose body is about {@code size} bytes. */
    static byte[] frame(int size, int id) {
        return Msgpack.encodeFramed(Map.of("id", (long) id, "pad", "x".repeat(size)), false);
    }

    @Test
    void manyWakesQueueOneToken() throws Exception {
        Listener l = new Listener();
        Conn conn = new Conn(SocketChannel.open(), 1, l);
        for (int i = 0; i < 100_000; i++) {
            conn.wake();
        }
        assertEquals(1, conn.outbox.size(), "wake-ups are coalesced: one token until it is consumed");
        assertFalse(conn.backlogged());
    }

    @Test
    void manyEventsThroughTheHubKeepTheOutboxBounded() throws Exception {
        FakeGame g = new FakeGame();
        StreamHub hub = new StreamHub(g, new BridgeLog(Path.of("/dev/null"), null));
        Conn conn = new Conn(SocketChannel.open(), 1, hub);
        Subs.EventSub sub = hub.subscribeEvents(conn, Set.of("chat"));
        hub.activate(sub);
        for (int i = 0; i < 50_000; i++) {
            hub.publish(new GameEvent("chat", i, FakeGame.ID, "Steve", "default", Map.of("message", "m" + i)));
        }
        assertTrue(conn.outbox.size() <= 1, "50000 events, outbox holds " + conn.outbox.size());
        assertEquals(Subs.EventSub.CAP, sub.queue.size(), "the event queue itself stays capped");
        hub.close();
    }

    @Test
    void wakesDuringADrainStillGetADrain() throws Exception {
        SocketChannel[] p = pair("wake");
        AtomicInteger drains = new AtomicInteger();
        Conn.Listener l = new Conn.Listener() {
            @Override
            public void drainEvents(Conn conn, BiConsumer<Subs.Sub, Map<String, Object>> out) {
                if (drains.incrementAndGet() == 1) {
                    conn.wake(); // an event arrives while the writer drains: it must not be lost
                }
            }

            @Override
            public void connectionClosed(Conn conn) {
            }
        };
        Conn conn = new Conn(p[0], 1, l);
        conn.startWriter();
        conn.wake();
        long deadline = System.currentTimeMillis() + 5000;
        while (drains.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(2, drains.get());
        conn.close();
        p[1].close();
    }

    @Test
    void backlogCountsFramesNotTokens() throws Exception {
        Conn conn = new Conn(SocketChannel.open(), 1, new Listener());
        for (int i = 0; i < Conn.BACKLOG; i++) {
            conn.sendFrame(frame(1, i));
        }
        conn.wake();
        conn.closeAfterFlush();
        assertEquals(Conn.BACKLOG + 2, conn.outbox.size());
        assertFalse(conn.backlogged(), "512 frames plus control tokens are not a backlog");
        conn.sendStream(new Subs.PosSub(1, conn, 250, null), Map.of());
        assertTrue(conn.backlogged());
    }

    @Test
    void outboxByteCapClosesTheConnection() throws Exception {
        Listener l = new Listener();
        Conn conn = new Conn(SocketChannel.open(), 1, l);
        int mib = 1 << 20;
        long queued = 0;
        int sent = 0;
        while (!conn.isClosed() && sent < 100) {
            conn.sendFrame(frame(mib, sent++));
            queued += mib;
        }
        assertTrue(conn.isClosed(), "closed once queued bytes pass the cap");
        assertTrue(queued >= Conn.MAX_OUTBOX_BYTES && queued <= Conn.MAX_OUTBOX_BYTES + 2L * mib, "closed at " + queued);
        assertEquals(1, l.closes.get());
        conn.sendFrame(frame(1, 1)); // after close: ignored, no exception
    }

    @Test
    void aPeerThatNeverReadsIsClosed() throws Exception {
        SocketChannel[] p = pair("slow");
        Listener l = new Listener();
        Conn conn = new Conn(p[0], 1, l);
        conn.startWriter();
        byte[] big = frame(1 << 20, 1);
        int n = 0;
        // the writer blocks once the socket buffers are full; the rest piles up in the outbox
        while (!conn.isClosed() && n < 200) {
            conn.sendFrame(big.clone());
            n++;
        }
        assertTrue(conn.isClosed(), "a non-reading peer must be disconnected, sent " + n + " MiB");
        assertTrue(n < 100, "within 32 MiB plus socket buffers: " + n);
        p[1].close();
    }

    @Test
    void closeAfterFlushDeliversEveryQueuedFrameThenCloses() throws Exception {
        for (int i = 0; i < 50; i++) {
            SocketChannel[] p = pair("flush" + i);
            Conn conn = new Conn(p[0], i, new Listener());
            conn.startWriter();
            byte[] a = frame(10, 1);
            byte[] b = frame(300_000, 2); // larger than the 64 KiB write chunk
            byte[] expectA = Msgpack.encode(Map.of("id", 1L, "pad", "x".repeat(10)), false);
            conn.sendFrame(a);
            conn.sendFrame(b);
            conn.closeAfterFlush();
            InputStream in = Channels.newInputStream(p[1]);
            assertArrayEquals(expectA, Framing.read(in), "iteration " + i);
            byte[] second = Framing.read(in);
            assertNotNull(second, "iteration " + i);
            assertEquals(300_000, ((String) ((Map<?, ?>) Msgpack.decode(second)).get("pad")).length());
            assertNull(Framing.read(in), "then a clean EOF, iteration " + i);
            assertTrue(waitClosed(conn), "the writer closes the connection itself");
            p[1].close();
        }
    }

    @Test
    void framesAreWrittenInQueueOrderAcrossManySmallAndLargeFrames() throws Exception {
        SocketChannel[] p = pair("order");
        Conn conn = new Conn(p[0], 1, new Listener());
        conn.startWriter();
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            int size = i % 7 == 0 ? 100_000 : 5 + i;
            sizes.add(size);
            conn.sendFrame(frame(size, i));
        }
        conn.closeAfterFlush();
        InputStream in = Channels.newInputStream(p[1]);
        for (int i = 0; i < 300; i++) {
            Map<?, ?> m = (Map<?, ?>) Msgpack.decode(Framing.read(in));
            assertEquals((long) i, m.get("id"));
            assertEquals(sizes.get(i), ((String) m.get("pad")).length());
        }
        assertNull(Framing.read(in));
        p[1].close();
    }

    private static boolean waitClosed(Conn c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!c.isClosed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }
        return c.isClosed();
    }
}
