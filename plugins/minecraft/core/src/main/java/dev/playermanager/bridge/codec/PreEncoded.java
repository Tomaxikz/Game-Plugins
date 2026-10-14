package dev.playermanager.bridge.codec;

/**
 * A value that is MessagePack-encoded at most once and then written verbatim wherever it appears in a message. Cached read
 * results are wrapped in this so N identical responses cost one encode; the source tree is released after encoding.
 */
public final class PreEncoded {
    private Object value;
    private volatile byte[] bytes;

    public PreEncoded(Object value) { this.value = value; }

    /** For callers that already encoded the value (e.g. to check its size). */
    public PreEncoded(Object value, byte[] encoded) { this.value = value; this.bytes = encoded; }

    public byte[] bytes() {
        byte[] b = bytes;
        if (b == null) {
            synchronized (this) {
                b = bytes;
                if (b == null) {
                    b = MsgPack.encode(value);
                    bytes = b;
                    value = null;
                }
            }
        }
        return b;
    }
}
