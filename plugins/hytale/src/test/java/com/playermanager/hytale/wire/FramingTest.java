package com.playermanager.hytale.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FramingTest {

    /** Counts write calls (one per frame is the contract: one syscall on a channel stream). */
    static final class CountingOut extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int writes;

        @Override
        public void write(int b) {
            writes++;
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writes++;
            bytes.write(b, off, len);
        }
    }

    /** Hands out at most {@code chunk} bytes per read, like a socket. */
    static final class Trickle extends InputStream {
        private final byte[] data;
        private final int chunk;
        private int pos;
        int reads;

        Trickle(byte[] data, int chunk) {
            this.data = data;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            reads++;
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            reads++;
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, chunk), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    @Test
    void aFrameIsOneWrite() throws Exception {
        CountingOut out = new CountingOut();
        Framing.write(out, new byte[] {1, 2, 3});
        assertEquals(1, out.writes);
        assertArrayEquals(WireTest.hex("00000003 010203"), out.bytes.toByteArray());

        CountingOut big = new CountingOut();
        Framing.write(big, new byte[300_000]);
        assertEquals(1, big.writes);
        assertEquals(300_004, big.bytes.size());
    }

    @Test
    void framedEncodingWritesTheSameBytesWithOneWrite() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sub", 1L);
        m.put("data", Map.of("k", "v"));
        byte[] body = Msgpack.encode(m, false);
        byte[] framed = Msgpack.encodeFramed(m, false);
        assertEquals(Framing.PREFIX + body.length, framed.length);
        CountingOut a = new CountingOut();
        Framing.writeFramed(a, framed);
        CountingOut b = new CountingOut();
        Framing.write(b, body);
        assertEquals(1, a.writes);
        assertArrayEquals(b.bytes.toByteArray(), a.bytes.toByteArray());
        assertArrayEquals(body, Framing.read(new ByteArrayInputStream(a.bytes.toByteArray())));
    }

    @Test
    void writeRejectsOversizeBodies() {
        assertThrows(IllegalArgumentException.class,
                () -> Framing.write(new ByteArrayOutputStream(), new byte[Framing.MAX_FRAME + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> Framing.writeFramed(new ByteArrayOutputStream(), new byte[Framing.PREFIX + Framing.MAX_FRAME + 1]));
    }

    @Test
    void payloadBudgetLeavesHalfAMebiForTheEnvelope() {
        assertEquals(3 * 1024 * 1024 + 512 * 1024, Framing.PAYLOAD_BUDGET);
        assertEquals(Framing.MAX_FRAME - 512 * 1024, Framing.PAYLOAD_BUDGET);
    }

    @Test
    void readIsCappedByMaxLenBeforeAnyBodyByteIsRead() throws Exception {
        byte[] frame = WireTest.hex("00010001 00"); // announces 65537 bytes, delivers 1
        Framing.FrameTooLargeException e = assertThrows(Framing.FrameTooLargeException.class,
                () -> Framing.read(new ByteArrayInputStream(frame), 65_536));
        assertEquals("frame of 65537 bytes exceeds 65536 bytes", e.getMessage());
        assertThrows(Framing.FrameTooLargeException.class,
                () -> new Framing.Reader(new ByteArrayInputStream(frame)).read(65_536));
        // a cap above the global limit never raises it
        assertThrows(Framing.FrameTooLargeException.class, () -> Framing.read(
                new ByteArrayInputStream(WireTest.hex("00400001")), Integer.MAX_VALUE));
        // exactly at the cap is fine
        byte[] ok = new byte[4 + 5];
        ok[3] = 5;
        assertEquals(5, Framing.read(new ByteArrayInputStream(ok), 5).length);
    }

    @Test
    void readerHandlesBackToBackFramesAcrossShortReads() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] big = new byte[100_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        Framing.write(wire, new byte[0]);
        Framing.write(wire, WireTest.hex("0102ff"));
        Framing.write(wire, big);
        Framing.write(wire, WireTest.hex("aa"));
        for (int chunk : new int[] {1, 3, 7, 8192, 1 << 20}) {
            Framing.Reader r = new Framing.Reader(new Trickle(wire.toByteArray(), chunk));
            assertArrayEquals(new byte[0], r.read(), "chunk " + chunk);
            assertArrayEquals(WireTest.hex("0102ff"), r.read());
            assertArrayEquals(big, r.read());
            assertArrayEquals(WireTest.hex("aa"), r.read());
            assertNull(r.read(), "clean EOF between frames");
        }
    }

    @Test
    void readerNeedsFewSystemCallsForSmallFrames() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int i = 0; i < 100; i++) {
            Framing.write(wire, new byte[] {(byte) i});
        }
        Trickle in = new Trickle(wire.toByteArray(), 1 << 20);
        Framing.Reader r = new Framing.Reader(in);
        for (int i = 0; i < 100; i++) {
            assertEquals(1, r.read().length);
        }
        assertTrue(in.reads <= 2, "100 frames were read with " + in.reads + " reads");
    }

    @Test
    void truncatedInputIsAnErrorNotEof() {
        assertThrows(IOException.class, () -> new Framing.Reader(new ByteArrayInputStream(WireTest.hex("0000"))).read());
        assertThrows(IOException.class,
                () -> new Framing.Reader(new ByteArrayInputStream(WireTest.hex("00000005 0102"))).read());
        assertThrows(IOException.class, () -> Framing.read(new ByteArrayInputStream(WireTest.hex("0000"))));
        assertThrows(IOException.class, () -> Framing.read(new ByteArrayInputStream(WireTest.hex("00000005 0102"))));
    }
}
