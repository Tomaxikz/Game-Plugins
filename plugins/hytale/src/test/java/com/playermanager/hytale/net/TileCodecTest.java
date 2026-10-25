package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.playermanager.hytale.game.TileImage;
import com.playermanager.hytale.wire.Canonical;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TileCodecTest {

    @Test
    void packedIndicesRoundTripForEveryWidthLikeTheServersBitFieldArr() {
        Random r = new Random(7);
        for (int bits = 1; bits <= 13; bits++) {
            int[] idx = new int[333];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = r.nextInt(1 << bits);
            }
            byte[] packed = TileCodec.pack(idx, bits);
            assertEquals((idx.length * bits + 7) / 8, packed.length);
            for (int i = 0; i < idx.length; i++) {
                assertEquals(idx[i], TileCodec.index(packed, i, bits), "bits=" + bits + " i=" + i);
            }
        }
    }

    @Test
    void lsbFirstLayoutKnownVector() {
        // 2-bit indices 1,2,3,0 -> byte 0b00_11_10_01 = 0x39 (least significant bits first)
        assertArrayEquals(new byte[] {0x39}, TileCodec.pack(new int[] {1, 2, 3, 0}, 2));
        assertEquals(3, TileCodec.index(new byte[] {0x39}, 2, 2));
    }

    @Test
    void rgbaUsesThePaletteAndOutOfRangeIsTransparent() {
        TileImage img = new TileImage(0, 0, 2, 2, new int[] {0x11223344, 0x55667788}, 2,
                TileCodec.pack(new int[] {0, 1, 3, 1}, 2));
        assertArrayEquals(new int[] {0x11223344, 0x55667788, 0, 0x55667788}, TileCodec.rgba(img));
    }

    @Test
    void pngIsDeterministicDecodableAndHashedOverItsBytes() throws Exception {
        TileImage img = FakeMap.image(3, -2, 1);
        int[] rgba = TileCodec.rgba(img);
        byte[] a = TileCodec.png(img.width(), img.height(), rgba);
        byte[] b = TileCodec.png(img.width(), img.height(), rgba.clone());
        assertArrayEquals(a, b);
        assertEquals(Canonical.sha256Hex(a).substring(0, 16), TileCodec.hash(a));
        BufferedImage dec = ImageIO.read(new ByteArrayInputStream(a));
        assertEquals(16, dec.getWidth());
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int c = rgba[y * 16 + x];
                int argb = ((c & 0xff) << 24) | (c >>> 8);
                assertEquals(argb, dec.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
        byte[] other = TileCodec.png(16, 16, TileCodec.rgba(FakeMap.image(3, -2, 2)));
        assertNotEquals(TileCodec.hash(a), TileCodec.hash(other));
    }

    /** The straightforward encoder (a fresh level-6 Deflater per image, chunked output): the reference bytes. */
    static byte[] referencePng(int width, int height, int[] rgba) {
        byte[] raw = new byte[height * (1 + width * 4)];
        int p = 0;
        for (int y = 0; y < height; y++) {
            raw[p++] = 0;
            for (int x = 0; x < width; x++) {
                int c = rgba[y * width + x];
                raw[p++] = (byte) (c >>> 24);
                raw[p++] = (byte) (c >>> 16);
                raw[p++] = (byte) (c >>> 8);
                raw[p++] = (byte) c;
            }
        }
        java.util.zip.Deflater d = new java.util.zip.Deflater(6);
        java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            z.write(buf, 0, d.deflate(buf));
        }
        d.end();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        byte[] ihdr = new byte[13];
        java.nio.ByteBuffer.wrap(ihdr).putInt(width).putInt(height);
        ihdr[8] = 8;
        ihdr[9] = 6;
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(java.io.ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    @Test
    void reusedDeflaterAndBuffersGiveTheReferenceBytesForEveryImageInAnyOrder() {
        Random r = new Random(11);
        int[][] sizes = {{16, 16}, {32, 32}, {3, 5}, {64, 17}, {1, 1}, {32, 32}, {16, 16}};
        for (int round = 0; round < 3; round++) {
            for (int[] sz : sizes) {
                int[] px = new int[sz[0] * sz[1]];
                boolean noisy = r.nextBoolean(); // incompressible and flat images exercise the buffer bound
                for (int i = 0; i < px.length; i++) {
                    px[i] = noisy ? r.nextInt() : 0x336699ff;
                }
                assertArrayEquals(referencePng(sz[0], sz[1], px), TileCodec.png(sz[0], sz[1], px),
                        sz[0] + "x" + sz[1] + " noisy=" + noisy + " round " + round);
            }
        }
    }

    @Test
    void pngsFromSeveralThreadsAreIdenticalToTheReference() throws Exception {
        TileImage img = FakeMap.image(5, 6, 7);
        int[] px = TileCodec.rgba(img);
        byte[] want = referencePng(img.width(), img.height(), px);
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger bad = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    if (!java.util.Arrays.equals(want, TileCodec.png(img.width(), img.height(), px))) {
                        bad.incrementAndGet();
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(0, bad.get());
    }
}
