package dev.playermanager.bridge;

import dev.playermanager.bridge.assets.AssetService;
import dev.playermanager.bridge.config.BridgeConfig;
import dev.playermanager.bridge.config.BridgeFiles;
import dev.playermanager.bridge.net.Connection;
import dev.playermanager.bridge.net.TcpTransport;
import dev.playermanager.bridge.net.Transport;
import dev.playermanager.bridge.net.UnixTransport;
import dev.playermanager.bridge.proto.Capabilities;
import dev.playermanager.bridge.proto.Ops;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.Platform;
import dev.playermanager.bridge.sub.ChunkService;
import dev.playermanager.bridge.sub.CommandRedactor;
import dev.playermanager.bridge.sub.EventBus;
import dev.playermanager.bridge.sub.PositionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point for platforms. Typical use:
 * <pre>
 *   BridgeCore core = new BridgeCore(platform);
 *   core.start();                 // never throws; returns false if the bridge could not start
 *   core.events().emit("join", ...);   // from platform listeners
 *   core.stop();                  // on disable
 * </pre>
 * Instances are single-use.
 */
public final class BridgeCore {
    public static final String VERSION_FALLBACK = "0.0.0-dev";

    private final Platform platform;
    private final BridgeLog log;
    private final BridgeFiles files;
    private BridgeConfig config;
    private Capabilities capabilities;
    private final AtomicInteger subIds = new AtomicInteger();
    private final AtomicInteger connIds = new AtomicInteger();
    private final long startedAtMillis = System.currentTimeMillis();

    private ExecutorService pool;
    private ScheduledExecutorService timer;
    private ExecutorService assetPool;
    private Transport transport;
    private Thread acceptThread;
    private String token;
    private volatile boolean running;
    private EventBus events;
    private PositionService positions;
    private AssetService assets;
    private ChunkService chunks;
    private Ops ops;
    private final dev.playermanager.bridge.util.ReadCache readCache = new dev.playermanager.bridge.util.ReadCache(Long.getLong("pm.readcache.ms", 500L));
    private final Set<Connection> connections = java.util.Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());

    public BridgeCore(Platform platform) {
        this.platform = platform;
        this.log = platform.log();
        this.files = new BridgeFiles(platform.info().serverRoot);
    }

    // ------------------------------------------------------------ accessors

    public Platform platform() { return platform; }
    public BridgeLog log() { return log; }
    public BridgeFiles files() { return files; }
    public BridgeConfig config() { return config; }
    public Capabilities capabilities() { return capabilities; }
    public EventBus events() { return events; }
    public PositionService positions() { return positions; }
    public AssetService assets() { return assets; }
    public ChunkService chunks() { return chunks; }
    public Ops ops() { return ops; }
    public dev.playermanager.bridge.util.ReadCache readCache() { return readCache; }

    /** Platform hook: a player joined, left, died, was kicked or banned. Drops that player's cached reads and the roster-wide ones. */
    public void playerChanged(String playerId) { readCache.invalidatePlayer(playerId); }
    public ExecutorService pool() { return pool; }
    public ScheduledExecutorService timer() { return timer; }
    public String token() { return token; }
    public boolean running() { return running; }
    public Transport transport() { return transport; }
    public int nextSubId() { return subIds.incrementAndGet(); }
    public long uptimeSeconds() { return (System.currentTimeMillis() - startedAtMillis) / 1000L; }
    public boolean tcpMode() { return transport != null && "tcp".equals(transport.name()); }

    // ------------------------------------------------------------ lifecycle

    /** Starts everything. Never throws. @return false if the bridge is disabled because startup failed. */
    public boolean start() {
        try {
            files.ensureDirs();
            config = BridgeConfig.loadOrCreate(files.config, log);
            capabilities = new Capabilities(platform, config);
            // Request workers mostly wait for the game thread: a bounded pool (32 threads, idle ones retire after 30 s) instead of one
            // thread per concurrent request; the asset indexer runs at minimum priority so it never competes with the server.
            ThreadPoolExecutor workers = new ThreadPoolExecutor(32, 32, 30, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<Runnable>(), daemon("pm-bridge-worker", Thread.NORM_PRIORITY - 1));
            workers.allowCoreThreadTimeOut(true);
            pool = workers;
            timer = Executors.newScheduledThreadPool(2, daemon("pm-bridge-timer", Thread.NORM_PRIORITY - 1));
            assetPool = Executors.newSingleThreadExecutor(daemon("pm-bridge-assets", Thread.MIN_PRIORITY));
            events = new EventBus(new CommandRedactor(config.platformStrings("redact_commands", null)), subIds);
            if (platform.positions() != null) {
                positions = new PositionService(platform.positions(), timer, pool, log, subIds, config.positionsMinIntervalMs);
            }
            if (platform.chunks() != null) {
                chunks = new ChunkService(platform.chunks(), timer, pool, log, subIds);
                if (platform.positions() != null) chunks.sweepWith(platform.positions());
            }
            if (platform.assets() != null) assets = new AssetService(platform.assets(), files.cache, log, assetPool);
            ops = new Ops(this);
            if (!bind()) { stop(); return false; }
            running = true;
            acceptThread = new Thread(new Runnable() { public void run() { acceptLoop(); } }, "pm-bridge-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            timer.scheduleWithFixedDelay(new Runnable() { public void run() { housekeeping(); } }, 5, 5, TimeUnit.SECONDS);
            log.info("bridge listening (" + transport.name() + (transport.port() != null ? ":" + transport.port() : "") + ")");
            return true;
        } catch (Throwable t) {
            log.error("bridge failed to start; continuing without it", t);
            try { stop(); } catch (Throwable ignored) { }
            return false;
        }
    }

    private boolean bind() {
        String mode = String.valueOf(config.platform.get("transport"));
        boolean wantUnix = !"tcp".equals(mode);
        boolean forceJunix = "junixsocket".equals(mode);
        try {
            if (wantUnix && !forceJunix && UnixTransport.isSupported()) {
                try {
                    transport = UnixTransport.bind(files.socket);
                } catch (IOException e) {
                    log.warn("unix socket bind failed (" + e.getMessage() + "), falling back to TCP + token");
                }
            }
            if (transport == null && wantUnix && dev.playermanager.bridge.net.JunixTransport.isAvailable()) {
                try {
                    transport = dev.playermanager.bridge.net.JunixTransport.bind(files.socket);
                } catch (IOException e) {
                    log.warn("junixsocket bind failed (" + e.getMessage() + "), falling back to TCP + token");
                }
            }
            String ver = platform.info().bridgeVersion;
            if (transport == null) {
                token = files.loadOrCreateToken();
                transport = TcpTransport.bind(config.tcpFallbackPort, Boolean.TRUE.equals(config.platform.get("tcp_loopback_only")));
            }
            files.deleteQuietly(files.bridgeJson);
            files.writeBridgeJson(transport.name(), transport.port(), platform.info().platform, platform.info().platformVersion,
                    platform.info().gameVersion, ver);
            return true;
        } catch (Throwable t) {
            log.error("could not open any transport; bridge disabled", t);
            return false;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                dev.playermanager.bridge.net.Conn c = transport.accept();
                if (connections.size() >= Math.max(8, config.maxConnections)) {
                    Connection.rejectBusy(c);
                    continue;
                }
                Connection conn = new Connection(this, c, tcpMode(), connIds.incrementAndGet());
                connections.add(conn);
                conn.start();
            } catch (Throwable e) {
                if (!running) break;
                log.debug("accept failed: " + e);
                try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void housekeeping() {
        try {
            for (Connection c : connections) c.housekeeping();
            readCache.sweep();
        } catch (Throwable t) {
            log.debug("housekeeping: " + t);
        }
    }

    public void connectionClosed(Connection c) { connections.remove(c); }

    private static ThreadFactory daemon(final String name, final int priority) {
        final AtomicInteger n = new AtomicInteger();
        return new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(priority);
                return t;
            }
        };
    }

    /** Stops listening, closes connections, removes socket and bridge.json. Idempotent. */
    public void stop() {
        running = false;
        try { if (transport != null) transport.close(); } catch (Throwable ignored) { }
        for (Connection c : connections) c.close();
        connections.clear();
        if (chunks != null) chunks.stop();
        if (pool != null) pool.shutdownNow();
        if (timer != null) timer.shutdownNow();
        if (assetPool != null) assetPool.shutdownNow();
        files.deleteQuietly(files.socket);
        files.deleteQuietly(files.bridgeJson);
    }
}
