package com.playermanager.hytale.wire;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 4-byte big-endian unsigned length prefix followed by the body (docs/protocol/README.md §2-3). */
public final class Framing {
    public static final int MAX_FRAME = 4 * 1024 * 1024;
    /** Bytes of payload (PNGs) one reply may carry: the frame cap minus room for the envelope. */
    public static final int PAYLOAD_BUDGET = MAX_FRAME - 512 * 1024;
    /** Length of the prefix; {@link Msgpack#encodeFramed} reserves this many leading bytes. */
    public static final int PREFIX = 4;

    private Framing() {
    }

    /** Reads one frame body of at most {@link #MAX_FRAME} bytes. Returns null on clean EOF before a frame starts. */
    public static byte[] read(InputStream in) throws IOException {
        return read(in, MAX_FRAME);
    }

    /**
     * Reads one frame body of at most {@code maxLen} bytes (callers cap pre-auth frames). Reads the prefix in one
     * call; for a hot read loop prefer {@link Reader}, which also avoids a system call per frame header.
     */
    public static byte[] read(InputStream in, int maxLen) throws IOException {
        byte[] header = new byte[PREFIX];
        int got = in.readNBytes(header, 0, PREFIX);
        if (got == 0) {
            return null;
        }
        if (got < PREFIX) {
            throw new IOException("truncated frame");
        }
        int len = checkedLength(header, 0, maxLen);
        byte[] body = new byte[len];
        if (in.readNBytes(body, 0, len) < len) {
            throw new IOException("truncated frame");
        }
        return body;
    }

    /** Writes prefix and body with a single {@code write} call (one syscall on a channel stream). */
    public static void write(OutputStream out, byte[] body) throws IOException {
        if (body.length > MAX_FRAME) {
            throw new IllegalArgumentException("frame exceeds 4 MiB");
        }
        byte[] frame = new byte[PREFIX + body.length];
        System.arraycopy(body, 0, frame, PREFIX, body.length);
        writeFramed(out, frame);
    }

    /**
     * Writes a buffer that already has {@link #PREFIX} reserved leading bytes (see {@link Msgpack#encodeFramed}):
     * fills the length in and writes the whole frame in one call.
     */
    public static void writeFramed(OutputStream out, byte[] frame) throws IOException {
        int n = frame.length - PREFIX;
        if (n < 0 || n > MAX_FRAME) {
            throw new IllegalArgumentException("frame exceeds 4 MiB");
        }
        frame[0] = (byte) (n >>> 24);
        frame[1] = (byte) (n >>> 16);
        frame[2] = (byte) (n >>> 8);
        frame[3] = (byte) n;
        out.write(frame);
        out.flush();
    }

    private static int checkedLength(byte[] h, int at, int maxLen) throws FrameTooLargeException {
        long len = ((h[at] & 0xffL) << 24) | ((h[at + 1] & 0xffL) << 16) | ((h[at + 2] & 0xffL) << 8)
                | (h[at + 3] & 0xffL);
        int cap = Math.min(maxLen, MAX_FRAME);
        if (len > cap) {
            throw new FrameTooLargeException(len, cap);
        }
        return (int) len;
    }

    /** Frame reader over one stream with a reusable read-ahead buffer; use it from a single thread. */
    public static final class Reader {
        private final InputStream in;
        private final byte[] buf = new byte[8192];
        private int pos;
        private int lim;

        public Reader(InputStream in) {
            this.in = in;
        }

        public byte[] read() throws IOException {
            return read(MAX_FRAME);
        }

        /** Next frame body (at most {@code maxLen} bytes), or null on clean EOF between frames. */
        public byte[] read(int maxLen) throws IOException {
            if (!ensure(PREFIX)) {
                if (lim == pos) {
                    return null;
                }
                throw new IOException("truncated frame");
            }
            int len = checkedLength(buf, pos, maxLen);
            pos += PREFIX;
            byte[] body = new byte[len];
            int have = Math.min(len, lim - pos);
            System.arraycopy(buf, pos, body, 0, have);
            pos += have;
            if (have < len && in.readNBytes(body, have, len - have) < len - have) {
                throw new IOException("truncated frame");
            }
            return body;
        }

        /** Buffers at least {@code n} bytes; false when the stream ends first. */
        private boolean ensure(int n) throws IOException {
            if (lim - pos >= n) {
                return true;
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, lim - pos);
                lim -= pos;
                pos = 0;
            }
            while (lim < n) {
                int r = in.read(buf, lim, buf.length - lim);
                if (r < 0) {
                    return false;
                }
                lim += r;
            }
            return true;
        }
    }

    public static final class FrameTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public FrameTooLargeException(long len) {
            this(len, MAX_FRAME);
        }

        public FrameTooLargeException(long len, int cap) {
            super("frame of " + len + " bytes exceeds " + (cap == MAX_FRAME ? "4 MiB" : cap + " bytes"));
        }
    }
}
