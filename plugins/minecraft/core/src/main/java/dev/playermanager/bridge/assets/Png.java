package dev.playermanager.bridge.assets;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Minimal PNG codec (no ImageIO: it may be absent on minimal JREs). Decodes non-interlaced PNGs of every colour type and
 * bit depth to 8-bit ARGB; encodes 8-bit RGBA. Used to compose multi-layer items and to measure sizes.
 */
final class Png {
    private Png() {}

    static final int MAX_PIXELS = 64 * 1024 * 1024;

    /** Decoded image, non-premultiplied ARGB. */
    static final class Img {
        final int w, h;
        final int[] argb;

        Img(int w, int h, int[] argb) { this.w = w; this.h = h; this.argb = argb; }
    }

    /** @return {width, height} or null if not a PNG. */
    static int[] size(byte[] b) {
        if (b == null || b.length < 24) return null;
        if ((b[0] & 0xff) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        int w = ((b[16] & 0xff) << 24) | ((b[17] & 0xff) << 16) | ((b[18] & 0xff) << 8) | (b[19] & 0xff);
        int h = ((b[20] & 0xff) << 24) | ((b[21] & 0xff) << 16) | ((b[22] & 0xff) << 8) | (b[23] & 0xff);
        if (w <= 0 || h <= 0) return null;
        return new int[] {w, h};
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    /** @return the image, or null when it is not a supported PNG (interlaced, corrupt, too large). */
    static Img decode(byte[] b) {
        try {
            int[] sz = size(b);
            if (sz == null || (long) sz[0] * sz[1] > MAX_PIXELS) return null;
            int w = sz[0], h = sz[1];
            int depth = b[24] & 0xff, ctype = b[25] & 0xff, interlace = b[28] & 0xff;
            if (interlace != 0) return null;
            byte[] plte = null, trns = null;
            ByteArrayOutputStream idat = new ByteArrayOutputStream();
            int o = 8;
            while (o + 12 <= b.length) {
                int len = be32(b, o);
                if (len < 0 || o + 12L + len > b.length) return null;
                String type = new String(b, o + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (type.equals("IDAT")) idat.write(b, o + 8, len);
                else if (type.equals("PLTE")) { plte = new byte[len]; System.arraycopy(b, o + 8, plte, 0, len); }
                else if (type.equals("tRNS")) { trns = new byte[len]; System.arraycopy(b, o + 8, trns, 0, len); }
                else if (type.equals("IEND")) break;
                o += 12 + len;
            }
            int channels;
            switch (ctype) {
                case 0: channels = 1; break;
                case 2: channels = 3; break;
                case 3: channels = 1; break;
                case 4: channels = 2; break;
                case 6: channels = 4; break;
                default: return null;
            }
            if (depth != 1 && depth != 2 && depth != 4 && depth != 8 && depth != 16) return null;
            int bitsPerPixel = channels * depth;
            int stride = (int) (((long) w * bitsPerPixel + 7) / 8);
            int bpp = Math.max(1, bitsPerPixel / 8);
            byte[] raw = new byte[(stride + 1) * h];
            Inflater inf = new Inflater();
            try {
                inf.setInput(idat.toByteArray());
                int got = 0;
                while (got < raw.length) {
                    int n = inf.inflate(raw, got, raw.length - got);
                    if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break;
                    got += n;
                }
                if (got < raw.length) return null;
            } finally {
                inf.end();
            }
            byte[] cur = new byte[stride];
            byte[] prev = new byte[stride];
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) {
                int ro = y * (stride + 1);
                int ft = raw[ro] & 0xff;
                for (int i = 0; i < stride; i++) {
                    int x = raw[ro + 1 + i] & 0xff;
                    int a = i >= bpp ? cur[i - bpp] & 0xff : 0;
                    int up = prev[i] & 0xff;
                    int c = i >= bpp ? prev[i - bpp] & 0xff : 0;
                    int v;
                    switch (ft) {
                        case 0: v = x; break;
                        case 1: v = x + a; break;
                        case 2: v = x + up; break;
                        case 3: v = x + ((a + up) >> 1); break;
                        case 4: {
                            int p = a + up - c, pa = Math.abs(p - a), pb = Math.abs(p - up), pc = Math.abs(p - c);
                            v = x + (pa <= pb && pa <= pc ? a : pb <= pc ? up : c);
                            break;
                        }
                        default: return null;
                    }
                    cur[i] = (byte) v;
                }
                for (int x = 0; x < w; x++) out[y * w + x] = pixel(cur, x, depth, ctype, plte, trns);
                byte[] t = prev; prev = cur; cur = t;
            }
            return new Img(w, h, out);
        } catch (DataFormatException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int sample(byte[] row, int index, int depth) {
        switch (depth) {
            case 8: return row[index] & 0xff;
            case 16: return ((row[index * 2] & 0xff) << 8) | (row[index * 2 + 1] & 0xff);
            default: {
                int perByte = 8 / depth;
                int by = row[index / perByte] & 0xff;
                int shift = 8 - depth * (index % perByte + 1);
                return (by >> shift) & ((1 << depth) - 1);
            }
        }
    }

    private static int to8(int v, int depth) {
        if (depth == 8) return v;
        if (depth == 16) return v >> 8;
        return v * 255 / ((1 << depth) - 1);
    }

    private static int pixel(byte[] row, int x, int depth, int ctype, byte[] plte, byte[] trns) {
        switch (ctype) {
            case 0: {
                int raw = sample(row, x, depth);
                int g = to8(raw, depth);
                int a = 255;
                if (trns != null && trns.length >= 2 && raw == (((trns[0] & 0xff) << 8) | (trns[1] & 0xff))) a = 0;
                return (a << 24) | (g << 16) | (g << 8) | g;
            }
            case 2: {
                int r = sample(row, x * 3, depth), g = sample(row, x * 3 + 1, depth), bl = sample(row, x * 3 + 2, depth);
                int a = 255;
                if (trns != null && trns.length >= 6
                        && r == (((trns[0] & 0xff) << 8) | (trns[1] & 0xff)) && g == (((trns[2] & 0xff) << 8) | (trns[3] & 0xff))
                        && bl == (((trns[4] & 0xff) << 8) | (trns[5] & 0xff))) a = 0;
                return (a << 24) | (to8(r, depth) << 16) | (to8(g, depth) << 8) | to8(bl, depth);
            }
            case 3: {
                int i = sample(row, x, depth);
                if (plte == null || i * 3 + 2 >= plte.length) return 0;
                int a = trns != null && i < trns.length ? trns[i] & 0xff : 255;
                return (a << 24) | ((plte[i * 3] & 0xff) << 16) | ((plte[i * 3 + 1] & 0xff) << 8) | (plte[i * 3 + 2] & 0xff);
            }
            case 4: {
                int g = to8(sample(row, x * 2, depth), depth), a = to8(sample(row, x * 2 + 1, depth), depth);
                return (a << 24) | (g << 16) | (g << 8) | g;
            }
            default: {
                int r = to8(sample(row, x * 4, depth), depth), g = to8(sample(row, x * 4 + 1, depth), depth);
                int bl = to8(sample(row, x * 4 + 2, depth), depth), a = to8(sample(row, x * 4 + 3, depth), depth);
                return (a << 24) | (r << 16) | (g << 8) | bl;
            }
        }
    }

    /** 8-bit RGBA, no interlace, filter type 0. */
    static byte[] encode(Img img) {
        int w = img.w, h = img.h;
        byte[] raw = new byte[(w * 4 + 1) * h];
        for (int y = 0; y < h; y++) {
            int ro = y * (w * 4 + 1);
            raw[ro] = 0;
            for (int x = 0; x < w; x++) {
                int p = img.argb[y * w + x];
                int o = ro + 1 + x * 4;
                raw[o] = (byte) (p >> 16);
                raw[o + 1] = (byte) (p >> 8);
                raw[o + 2] = (byte) p;
                raw[o + 3] = (byte) (p >>> 24);
            }
        }
        Deflater d = new Deflater(9);
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[8192];
        while (!d.finished()) { int n = d.deflate(buf); z.write(buf, 0, n); }
        d.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}, 0, 8);
        byte[] ihdr = new byte[13];
        put32(ihdr, 0, w);
        put32(ihdr, 4, h);
        ihdr[8] = 8; ihdr[9] = 6;
        chunk(out, "IHDR", ihdr);
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] len = new byte[4];
        put32(len, 0, data.length);
        out.write(len, 0, 4);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        put32(c, 0, (int) crc.getValue());
        out.write(c, 0, 4);
    }

    /** Copy of the rectangle (x, y, cw, ch) scaled nearest-neighbour to (tw, th). */
    static Img cropScale(Img src, int x0, int y0, int cw, int ch, int tw, int th) {
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int sy = y0 + Math.min(ch - 1, (int) ((long) y * ch / th));
            for (int x = 0; x < tw; x++) {
                int sx = x0 + Math.min(cw - 1, (int) ((long) x * cw / tw));
                out[y * tw + x] = (sx < src.w && sy < src.h) ? src.argb[sy * src.w + sx] : 0;
            }
        }
        return new Img(tw, th, out);
    }

    /** Alpha-over of {@code src} onto {@code dst} (same size, non-premultiplied ARGB). */
    static void over(int[] dst, int[] src) {
        for (int i = 0; i < dst.length; i++) {
            int s = src[i], d = dst[i];
            int sa = s >>> 24;
            if (sa == 0) continue;
            int da = d >>> 24;
            if (sa == 255 || da == 0) { dst[i] = s; continue; }
            int oa = sa * 255 + da * (255 - sa);   // scaled by 255
            int r = ((s >> 16 & 0xff) * sa * 255 + (d >> 16 & 0xff) * da * (255 - sa)) / oa;
            int g = ((s >> 8 & 0xff) * sa * 255 + (d >> 8 & 0xff) * da * (255 - sa)) / oa;
            int b = ((s & 0xff) * sa * 255 + (d & 0xff) * da * (255 - sa)) / oa;
            dst[i] = ((oa / 255) << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
