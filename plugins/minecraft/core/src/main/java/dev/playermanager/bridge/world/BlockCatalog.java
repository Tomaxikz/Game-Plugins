package dev.playermanager.bridge.world;

import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.codec.PreEncoded;
import dev.playermanager.bridge.net.Framing;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.BlockRegistry;
import dev.playermanager.bridge.spi.BlockSink;
import dev.playermanager.bridge.spi.BridgeLog;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The {@code blocks.list} answer (operations.md 6e, T10). Built once in the background after the server has started, from the
 * platform's {@link BlockRegistry}, and kept as encoded pages: a request is a lookup and a byte copy, never a registry walk.
 *
 * <p>Complexity: the build is O(B log B + S) time and O(encoded size) memory for B blocks and S differing states (the platform
 * walks all states once; only differing ones are retained). A request is O(1).
 */
public final class BlockCatalog implements BlockSink {
    /** Bytes of one page budget: a frame minus room for the envelope, {@code hash}, the array header and {@code next}. */
    static final int PAGE_BUDGET = Framing.MAX_FRAME - 4096;

    private static final class StateRec {
        final String properties;
        final int color, flags;
        StateRec(String properties, int color, int flags) { this.properties = properties; this.color = color; this.flags = flags; }
    }

    private static final class Rec {
        final String id;
        final int color, flags, light;
        List<StateRec> states;
        Rec(String id, int color, int flags, int light) { this.id = id; this.color = color; this.flags = flags; this.light = light; }
    }

    /** The finished answer. */
    private static final class Built {
        final String hash;
        final byte[][] pages;            // the complete response data of each page
        final byte[] unchanged;
        Built(String hash, byte[][] pages, byte[] unchanged) { this.hash = hash; this.pages = pages; this.unchanged = unchanged; }
    }

    private final BlockRegistry source;
    private final BridgeLog log;
    private final int pageBudget;
    private volatile Built built;
    private volatile boolean failed;
    private ArrayList<Rec> recs = new ArrayList<Rec>(2048);     // build thread only
    private Rec open;

    public BlockCatalog(BlockRegistry source, BridgeLog log) { this(source, log, PAGE_BUDGET); }

    BlockCatalog(BlockRegistry source, BridgeLog log, int pageBudget) { this.source = source; this.log = log; this.pageBudget = pageBudget; }

    /** Starts the background build once the platform says the server has started. Never throws. */
    public void start(final Executor worker) {
        source.whenReady().whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
            public void accept(Void v, Throwable t) {
                if (t != null) { fail(t); return; }
                try {
                    worker.execute(new Runnable() { public void run() { build(); } });
                } catch (Throwable e) {
                    fail(e);
                }
            }
        });
    }

    private void fail(Throwable t) {
        failed = true;
        log.error("blocks.list: the block registry could not be read; the op answers unsupported", t);
    }

    private void build() {
        long t0 = System.nanoTime();
        try {
            source.read(this);
            Built b = assemble(recs);
            recs = null;
            open = null;
            built = b;
            log.info("blocks.list ready: " + b.hash + ", " + b.pages.length + " page(s), " + (System.nanoTime() - t0) / 1_000_000L + " ms");
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ------------------------------------------------------------------ BlockSink

    @Override public void block(String id, int mapColor, int flags, int light) {
        open = new Rec(id, mapColor, flags, light);
        recs.add(open);
    }

    @Override public boolean differs(int mapColor, int flags) { return mapColor != open.color || flags != open.flags; }

    @Override public void state(String properties, int mapColor, int flags) {
        if (open.states == null) open.states = new ArrayList<StateRec>(4);
        open.states.add(new StateRec(properties, mapColor, flags));
    }

    // ------------------------------------------------------------------ request

    /**
     * One page of the answer.
     * @param sinceHash the {@code hash} the panel already has, or null
     * @param cursor    {@code next} of the previous page, or null
     * @return the encoded response data
     * @throws BridgeException {@code busy} while the answer is being built, {@code unsupported} when it could not be built,
     *         {@code invalid_args} for a cursor this bridge did not issue
     */
    public PreEncoded list(String sinceHash, String cursor) {
        Built b = built;
        if (b == null) {
            if (failed) throw BridgeException.unsupported("the block registry could not be read on this server");
            throw BridgeException.busy("the block registry is still being read; retry in about a second");
        }
        if (cursor != null) return new PreEncoded(null, b.pages[pageOf(b, cursor)]);
        if (sinceHash != null && sinceHash.equals(b.hash)) return new PreEncoded(null, b.unchanged);
        return new PreEncoded(null, b.pages[0]);
    }

    private static int pageOf(Built b, String cursor) {
        int colon = cursor.indexOf(':');
        if (colon != 16 || !cursor.startsWith(b.hash)) throw BridgeException.invalid("cursor not issued by this bridge; start again without it");
        int page = 0;
        for (int i = colon + 1; i < cursor.length(); i++) {
            char c = cursor.charAt(i);
            if (c < '0' || c > '9' || i - colon > 9) throw BridgeException.invalid("bad cursor; start again without it");
            page = page * 10 + (c - '0');
        }
        if (page < 1 || page >= b.pages.length) throw BridgeException.invalid("bad cursor; start again without it");
        return page;
    }

    // ------------------------------------------------------------------ assembly

    private static final Comparator<Rec> BY_ID = new Comparator<Rec>() {
        public int compare(Rec a, Rec b) { return a.id.compareTo(b.id); }
    };
    private static final Comparator<StateRec> BY_PROPERTIES = new Comparator<StateRec>() {
        public int compare(StateRec a, StateRec b) { return a.properties.compareTo(b.properties); }
    };

    private Built assemble(List<Rec> all) {
        if (all.isEmpty()) throw new IllegalStateException("the registry holds no block");
        Collections.sort(all, BY_ID);
        int n = all.size();
        byte[][] entries = new byte[n][];
        MessageDigest sha = sha256();
        writeArrayHeader(sha, n);
        for (int i = 0; i < n; i++) {
            Rec r = all.get(i);
            Map<String, Object> entry = entry(r, true);
            byte[] enc = MsgPack.encode(entry);
            if (enc.length > pageBudget && r.states != null) {                      // does not fit a frame on its own: the panel uses the block default for its states
                log.warn("blocks.list: " + r.id + " has " + r.states.size() + " differing states and does not fit one frame; sending it without states");
                entry = entry(r, false);
                enc = MsgPack.encode(entry);
            }
            entries[i] = enc;
            sha.update(MsgPack.encodeCanonical(entry));
        }
        String hash = hex16(sha.digest());
        List<byte[]> pages = new ArrayList<byte[]>();
        int from = 0;
        while (from < n) {
            int to = from, size = 0;
            do { size += entries[to].length; to++; } while (to < n && size + entries[to].length <= pageBudget);
            pages.add(page(hash, entries, from, to, to < n ? hash + ":" + (pages.size() + 1) : null));
            from = to;
        }
        Map<String, Object> unchanged = new LinkedHashMap<String, Object>(4);
        unchanged.put("hash", hash);
        unchanged.put("unchanged", Boolean.TRUE);
        return new Built(hash, pages.toArray(new byte[pages.size()][]), MsgPack.encode(unchanged));
    }

    private static Map<String, Object> entry(Rec r, boolean withStates) {
        Map<String, Object> m = new LinkedHashMap<String, Object>(8);
        m.put("id", r.id);
        m.put("map_color", r.color == NO_COLOR ? null : Integer.valueOf(r.color));
        m.put("flags", r.flags);
        m.put("light", r.light);
        if (withStates && r.states != null) {
            Collections.sort(r.states, BY_PROPERTIES);
            List<Object> states = new ArrayList<Object>(r.states.size());
            for (StateRec s : r.states) {
                Map<String, Object> sm = new LinkedHashMap<String, Object>(4);
                sm.put("properties", s.properties);
                if (s.color != r.color) sm.put("map_color", s.color == NO_COLOR ? null : Integer.valueOf(s.color));
                sm.put("flags", s.flags);
                states.add(sm);
            }
            m.put("states", states);
        }
        return m;
    }

    /** {@code {hash, blocks: [entries from..to), next?}}: the entries are spliced in as already encoded. */
    private static byte[] page(String hash, byte[][] entries, int from, int to, String next) {
        int bytes = 0;
        for (int i = from; i < to; i++) bytes += entries[i].length;
        byte[] header = arrayHeader(to - from);
        byte[] array = new byte[header.length + bytes];
        System.arraycopy(header, 0, array, 0, header.length);
        int off = header.length;
        for (int i = from; i < to; i++) { System.arraycopy(entries[i], 0, array, off, entries[i].length); off += entries[i].length; }
        Map<String, Object> m = new LinkedHashMap<String, Object>(4);
        m.put("hash", hash);
        m.put("blocks", new PreEncoded(null, array));
        if (next != null) m.put("next", next);
        return MsgPack.encode(m);
    }

    /**
     * The {@code hash} of an answer: first 16 hex of SHA-256 over the canonical MessagePack of the entries array (operations.md 6e).
     * Same value as encoding the whole list at once (the build does it entry by entry); kept for the test vectors.
     */
    static String hashOf(List<Map<String, Object>> entries) {
        MessageDigest sha = sha256();
        writeArrayHeader(sha, entries.size());
        for (Map<String, Object> e : entries) sha.update(MsgPack.encodeCanonical(e));
        return hex16(sha.digest());
    }

    private static void writeArrayHeader(MessageDigest sha, int n) { sha.update(arrayHeader(n)); }

    private static byte[] arrayHeader(int n) {
        MsgPack.Out out = new MsgPack.Out();
        MsgPack.arrayHeader(out, n);
        return out.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex16(byte[] digest) {
        char[] c = new char[16];
        for (int i = 0; i < 8; i++) {
            c[i * 2] = Character.forDigit((digest[i] >> 4) & 0xf, 16);
            c[i * 2 + 1] = Character.forDigit(digest[i] & 0xf, 16);
        }
        return new String(c);
    }
}
