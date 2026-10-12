package dev.playermanager.bridge.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * {@link Conn} over a blocking {@link SocketChannel}. Own stream adapters are used because
 * {@code Channels.newInputStream} serialises readers and writers on the channel's blocking lock.
 */
final class ChannelConn implements Conn {
    private final SocketChannel ch;
    private final String desc;
    private final InputStream in = new InputStream() {
        private final ByteBuffer one = ByteBuffer.allocate(1);

        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xff);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            return ch.read(ByteBuffer.wrap(b, off, len));
        }
    };
    private final OutputStream out = new OutputStream() {
        @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(b, off, len);
            while (bb.hasRemaining()) ch.write(bb);
        }
    };

    ChannelConn(SocketChannel ch, String desc) { this.ch = ch; this.desc = desc; }

    public InputStream in() { return in; }
    public OutputStream out() { return out; }
    public String describe() { return desc; }
    public void close() { try { ch.close(); } catch (IOException ignored) { } }
}
