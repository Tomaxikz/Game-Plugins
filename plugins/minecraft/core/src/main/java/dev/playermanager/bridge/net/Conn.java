package dev.playermanager.bridge.net;

import java.io.InputStream;
import java.io.OutputStream;

/** An accepted client connection. */
public interface Conn {
    InputStream in();
    OutputStream out();
    void close();
    String describe();
}
