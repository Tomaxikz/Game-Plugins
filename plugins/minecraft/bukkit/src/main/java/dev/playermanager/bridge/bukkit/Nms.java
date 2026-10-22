package dev.playermanager.bridge.bukkit;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

/**
 * Guarded access to the server's own registries by their Mojang names, for {@code biomes.list} and {@code blocks.list}
 * (minecraft.md 6b, 6c). Only Paper and Folia 1.20.5+ qualify: from that version their runtime is Mojang-mapped. Spigot and older
 * Paper have obfuscated member names and are never probed, so nothing here can misread them.
 */
final class Nms {
    private Nms() {}

    static final String MIN_VERSION = "1.20.5";

    /** True for Paper-family servers new enough to run on Mojang names. Cheap; the callers still verify every member they use. */
    static boolean mojangMapped(Env env) { return env.paper && env.atLeast(MIN_VERSION); }

    /** The {@code MinecraftServer}, or null. */
    static Object server() { return Reflect.call(Bukkit.getServer(), "getServer"); }

    /**
     * Completes on the first server tick: by then the server has finished starting, so mods and datapacks can no longer change
     * the registries (T9, T10). Fails when the scheduler is gone (the plugin was disabled meanwhile).
     */
    static CompletableFuture<Void> firstTick(Ctx ctx) {
        return ctx.scheduler.global(new Callable<Void>() { public Void call() { return null; } });
    }

    /** A public method by class, name and parameter types; throws (naming the member) when missing or not accessible. */
    static Method required(Class<?> c, String name, Class<?>... types) throws ReflectiveOperationException {
        try {
            Method m = c.getMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new NoSuchMethodException(c.getName() + "#" + name + " (" + e + ")");
        }
    }

    /** Like {@link #required} but null when missing (for members that only some versions have). */
    static Method method(Class<?> c, String name, Class<?>... types) {
        try {
            return required(c, name, types);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** {@code Registry#getKey} of any registry object, or null. */
    static String keyOf(Method getKey, Object registry, Object value) throws ReflectiveOperationException {
        Object k = getKey.invoke(registry, value);
        return k == null ? null : k.toString();
    }
}
