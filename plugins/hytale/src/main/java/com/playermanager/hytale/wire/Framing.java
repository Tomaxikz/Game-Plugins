package com.playermanager.hytale.wire;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 4-byte big-endian unsigned length prefix followed by the body (docs/protocol/README.md §2-3). */
public final class Framing {
    public static final int MAX_FRAME = 4 * 1024 * 1024;

    private Framing() {
    }

    /** Reads one frame body. Returns null on clean EOF before a frame starts. */
    public static byte[] read(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        int first = in.read();
        if (first < 0) {
            return null;
        }
        byte[] header = new byte[4];
        header[0] = (byte) first;
        din.readFully(header, 1, 3);
        long len = ((header[0] & 0xffL) << 24) | ((header[1] & 0xffL) << 16)
                | ((header[2] & 0xffL) << 8) | (header[3] & 0xffL);
        if (len > MAX_FRAME) {
            throw new FrameTooLargeException(len);
        }
        byte[] body = new byte[(int) len];
        try {
            din.readFully(body);
        } catch (EOFException e) {
            throw new IOException("truncated frame", e);
        }
        return body;
    }

    public static void write(OutputStream out, byte[] body) throws IOException {
        if (body.length > MAX_FRAME) {
            throw new IllegalArgumentException("frame exceeds 4 MiB");
        }
        int n = body.length;
        out.write(new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
        out.write(body);
        out.flush();
    }

    public static final class FrameTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public FrameTooLargeException(long len) {
            super("frame of " + len + " bytes exceeds 4 MiB");
        }
    }
}
