package com.playermanager.hytale.net;

import com.playermanager.hytale.game.TileImage;
import com.playermanager.hytale.wire.Canonical;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Turns a server map image (palette + LSB-first packed indices, the layout of the server's
 * {@code BitFieldArr}) into a deterministic RGBA PNG, and hashes it. Pure Java; runs on bridge worker threads.
 */
final class TileCodec {
    private TileCodec() {
    }

    /** Unpacks palette indices (LSB first) into 0xRRGGBBAA pixels. Out-of-range indices become transparent. */
    static int[] rgba(TileImage img) {
        int n = img.width() * img.height();
        int[] out = new int[n];
        int bits = img.bitsPerIndex();
        int[] pal = img.palette();
        byte[] data = img.packed();
        if (bits <= 0 || pal == null || data == null) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            int idx = index(data, i, bits);
            out[i] = idx >= 0 && idx < pal.length ? pal[idx] : 0;
        }
        return out;
    }

    /** Same algorithm as the server's {@code BitFieldArr.get(int)}. */
    static int index(byte[] array, int i, int bits) {
        long bitOffset = (long) i * bits;
        int byteIndex = (int) (bitOffset >>> 3);
        int bit = (int) (bitOffset & 7);
        if (byteIndex >= array.length) {
            return -1;
        }
        if (bit + bits <= 8) {
            return ((array[byteIndex] & 0xff) >>> bit) & ((1 << bits) - 1);
        }
        int value = 0;
        int remaining = bits;
        int shift = 0;
        while (remaining > 0) {
            if (byteIndex >= array.length) {
                return -1;
            }
            int take = Math.min(8 - bit, remaining);
            int mask = (1 << take) - 1;
            value |= (((array[byteIndex] & 0xff) >>> bit) & mask) << shift;
            shift += take;
            remaining -= take;
            byteIndex++;
            bit = 0;
        }
        return value;
    }

    /** Packs indices LSB first (the inverse of {@link #index}); used by the fake provider and tests. */
    static byte[] pack(int[] indices, int bits) {
        byte[] out = new byte[(indices.length * bits + 7) / 8];
        for (int i = 0; i < indices.length; i++) {
            long bitOffset = (long) i * bits;
            int v = indices[i];
            for (int k = 0; k < bits; k++) {
                long b = bitOffset + k;
                if (((v >>> k) & 1) != 0) {
                    out[(int) (b >>> 3)] |= (byte) (1 << (b & 7));
                }
            }
        }
        return out;
    }

    /** Reused per thread: the compressor, the CRC and the unfiltered scanlines. */
    private static final class Scratch {
        /** Level 6 on purpose: the tile hashes (and the golden PNG bytes) depend on it. */
        final Deflater deflater = new Deflater(6);
        final CRC32 crc = new CRC32();
        byte[] raw = new byte[0];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    /** Scanline buffers above this are not kept by the thread. */
    private static final int KEEP_RAW = 1 << 20;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    /** Signature, IHDR chunk (length, type, 13 bytes, crc) and the IDAT chunk's length and type. */
    private static final int IDAT_DATA = 8 + (4 + 4 + 13 + 4) + 8;
    private static final int IEND_CHUNK = 12;

    /** RGBA 8-bit truecolour PNG, filter 0 on every row, fixed deflate level: same pixels, same bytes. */
    static byte[] png(int width, int height, int[] rgba) {
        if (width <= 0 || height <= 0 || rgba.length != width * height) {
            throw new IllegalArgumentException("bad image size");
        }
        Scratch sc = SCRATCH.get();
        int rawLen = height * (1 + width * 4);
        byte[] raw = sc.raw.length >= rawLen ? sc.raw : new byte[rawLen];
        if (rawLen <= KEEP_RAW) {
            sc.raw = raw;
        }
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
        // zlib's own worst case (deflateBound plus the 6 byte wrapper), so one deflate call normally fits
        int bound = rawLen + (rawLen >> 12) + (rawLen >> 14) + (rawLen >> 25) + 13 + 6;
        byte[] out = new byte[IDAT_DATA + bound + 4 + IEND_CHUNK];
        System.arraycopy(SIGNATURE, 0, out, 0, 8);
        int o = 8;
        putInt(out, o, 13);
        out[o + 4] = 'I';
        out[o + 5] = 'H';
        out[o + 6] = 'D';
        out[o + 7] = 'R';
        putInt(out, o + 8, width);
        putInt(out, o + 12, height);
        out[o + 16] = 8; // bit depth
        out[o + 17] = 6; // RGBA
        crc(sc.crc, out, o + 4, 4 + 13, o + 21);
        o += 25;

        Deflater d = sc.deflater;
        d.reset();
        d.setInput(raw, 0, rawLen);
        d.finish();
        int n = 0;
        while (!d.finished()) {
            if (n >= bound) {
                // the bound is classic zlib's; other builds the JDK may link (zlib-ng) can exceed it on noisy input
                bound += Math.max(64, bound >> 3);
                out = Arrays.copyOf(out, IDAT_DATA + bound + 4 + IEND_CHUNK);
            }
            n += d.deflate(out, IDAT_DATA + n, bound - n);
        }
        putInt(out, o, n);
        out[o + 4] = 'I';
        out[o + 5] = 'D';
        out[o + 6] = 'A';
        out[o + 7] = 'T';
        crc(sc.crc, out, o + 4, 4 + n, IDAT_DATA + n);
        o = IDAT_DATA + n + 4;
        putInt(out, o, 0);
        out[o + 4] = 'I';
        out[o + 5] = 'E';
        out[o + 6] = 'N';
        out[o + 7] = 'D';
        crc(sc.crc, out, o + 4, 4, o + 8);
        return Arrays.copyOf(out, o + IEND_CHUNK);
    }

    /** CRC-32 of {@code out[from, from + len)} stored big-endian at {@code at}. */
    private static void crc(CRC32 crc, byte[] out, int from, int len, int at) {
        crc.reset();
        crc.update(out, from, len);
        putInt(out, at, (int) crc.getValue());
    }

    /** First 16 lowercase hex of SHA-256 over the PNG bytes (the map.tiles hash). */
    static String hash(byte[] png) {
        return Canonical.sha256Hex(png).substring(0, 16);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
