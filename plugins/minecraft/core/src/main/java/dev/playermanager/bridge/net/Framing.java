package dev.playermanager.bridge.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Frame = u32 big-endian length + body (README section 3). */
public final class Framing {
    private Framing() {}

    public static final int MAX_FRAME = 4 * 1024 * 1024;

    /** @return body, or null on clean EOF before the first header byte. @throws IOException on truncation or oversize (&gt; 4 MiB). */
    public static byte[] read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b1 | b2 | b3) < 0) throw new EOFException("truncated frame header");
        long len = ((long) b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        if (len > MAX_FRAME) throw new IOException("frame too large: " + len);
        byte[] body = new byte[(int) len];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n < 0) throw new EOFException("truncated frame body");
            off += n;
        }
        return body;
    }

    public static byte[] frame(byte[] body) {
        byte[] f = new byte[body.length + 4];
        f[0] = (byte) (body.length >>> 24);
        f[1] = (byte) (body.length >>> 16);
        f[2] = (byte) (body.length >>> 8);
        f[3] = (byte) body.length;
        System.arraycopy(body, 0, f, 4, body.length);
        return f;
    }

    public static void write(OutputStream out, byte[] body) throws IOException {
        if (body.length > MAX_FRAME) throw new IOException("frame too large: " + body.length);
        out.write(frame(body));
        out.flush();
    }
}
