package dev.playermanager.bridge.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class FramingTest {
    @Test void roundTrip() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Framing.write(o, new byte[] {1, 2, 3});
        Framing.write(o, new byte[0]);
        byte[] all = o.toByteArray();
        assertArrayEquals(new byte[] {0, 0, 0, 3, 1, 2, 3, 0, 0, 0, 0}, all);
        ByteArrayInputStream in = new ByteArrayInputStream(all);
        assertArrayEquals(new byte[] {1, 2, 3}, Framing.read(in));
        assertArrayEquals(new byte[0], Framing.read(in));
        assertNull(Framing.read(in));
    }

    @Test void capIs4MiB() {
        byte[] hdr = {0x00, 0x40, 0x00, 0x01}; // 4 MiB + 1
        assertThrows(IOException.class, () -> Framing.read(new ByteArrayInputStream(hdr)));
        byte[] ok = {0x00, 0x40, 0x00, 0x00};
        assertThrows(EOFException.class, () -> Framing.read(new ByteArrayInputStream(ok)));
        assertThrows(IOException.class, () -> Framing.write(new ByteArrayOutputStream(), new byte[Framing.MAX_FRAME + 1]));
    }

    @Test void truncatedHeaderAndBody() {
        assertThrows(EOFException.class, () -> Framing.read(new ByteArrayInputStream(new byte[] {0, 0})));
        assertThrows(EOFException.class, () -> Framing.read(new ByteArrayInputStream(new byte[] {0, 0, 0, 5, 1})));
    }
}
