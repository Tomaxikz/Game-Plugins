package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.assets.PackScanner;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

/**
 * The resource pack(s) the server really sends: SHA-1 hashes (and URLs, for the log) from {@code server.properties}, from
 * {@code PlayerResourcePackStatusEvent#getHash} where the server API still has it (Spigot/older Paper), from Paper's
 * {@code Server#getServerResourcePack}, and from Oraxen ({@code OraxenPackUploadEvent#getPackUrl/getHash}, {@code OraxenPack.getPack()}).
 * Hashes are matched against local zips by {@link PackScanner}; nothing is downloaded.
 */
final class SentPacks {
    private final Ctx ctx;
    private final Set<String> hashes = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<String> logged = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<Path> files = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());

    SentPacks(Ctx ctx) { this.ctx = ctx; }

    void record(Object url, Object hash) {
        String h = hash instanceof String ? PackScanner.normSha1((String) hash) : hash instanceof byte[] ? hex((byte[]) hash) : null;
        if (h != null) hashes.add(h);
        if (h != null && logged.add(h)) ctx.log.info("server sends resource pack sha1=" + h + (url != null ? " url=" + url : ""));
    }

    private static String hex(byte[] b) {
        if (b.length != 20) return null;
        StringBuilder sb = new StringBuilder(40);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** Hooks the events; call once on the server thread after the plugin is enabled. */
    void register() {
        hook("org.bukkit.event.player.PlayerResourcePackStatusEvent", false);
        hook("io.th0rgal.oraxen.api.events.OraxenPackUploadEvent", true);
    }

    @SuppressWarnings("unchecked")
    private void hook(String className, boolean withUrl) {
        try {
            Class<?> c = Reflect.cls(className);
            if (c == null) return;
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) c, new Listener() { }, EventPriority.MONITOR, new EventExecutor() {
                public void execute(Listener l, Event e) {
                    record(withUrl ? Reflect.call(e, "getPackUrl") : null, Reflect.call(e, "getHash"));
                }
            }, ctx.plugin, true);
        } catch (Throwable t) {
            ctx.log.debug("resource pack hook " + className + " unavailable: " + t);
        }
    }

    /** Called from the asset worker: current set of sent hashes plus local pack files exposed by plugin APIs. */
    Set<String> hashes(Path root) {
        Set<String> out = new LinkedHashSet<String>(hashes);
        Properties pr = serverProperties(root);
        String h = pr == null ? null : PackScanner.normSha1(pr.getProperty("resource-pack-sha1"));
        if (h != null) {
            out.add(h);
            if (logged.add(h)) ctx.log.info("server.properties resource-pack-sha1=" + h + " url=" + pr.getProperty("resource-pack"));
        }
        Object info = Reflect.call(Bukkit.getServer(), "getServerResourcePack");        // Paper 1.20.3+: org.bukkit.packs.ResourcePack
        if (info != null) record(viaInterface(info, "getUrl"), viaInterface(info, "getHash"));
        for (Player p : Bukkit.getOnlinePlayers()) record(null, Reflect.call(p, "getResourcePackHash"));   // Paper: the pack the client was sent
        out.addAll(hashes);
        return out;
    }

    /** Calls through the public API interface: the implementation class is not accessible to reflection. */
    private static Object viaInterface(Object o, String method) {
        try {
            Class<?> c = Reflect.cls("org.bukkit.packs.ResourcePack");
            return c == null ? null : c.getMethod(method).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Local pack files that plugin APIs hand out directly (Oraxen). */
    Set<Path> files() {
        Object f = Reflect.callStatic("io.th0rgal.oraxen.api.OraxenPack", "getPack");
        if (f instanceof File) files.add(((File) f).toPath().toAbsolutePath().normalize());
        return files;
    }

    static Properties serverProperties(Path root) {
        File f = root.resolve("server.properties").toFile();
        if (!f.isFile()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                Properties pr = new Properties();
                pr.load(in);
                return pr;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
