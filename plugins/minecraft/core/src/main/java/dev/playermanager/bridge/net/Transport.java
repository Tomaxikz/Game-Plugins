package dev.playermanager.bridge.net;

import java.io.IOException;

/** A listening endpoint (Unix socket or TCP). */
public interface Transport {
    /** {@code "unix"} or {@code "tcp"} (values of bridge.json {@code transport}). */
    String name();
    /** TCP port, or null for Unix. */
    Integer port();
    /** Blocks until a client connects. Throws IOException when closed. */
    Conn accept() throws IOException;
    void close();
}
