package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.Deadline;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Per-player advancement progress. The advancement definitions (handle, key string, criteria count) are read once and kept
 * until the datapacks are reloaded ({@link #invalidate()}) or a minute has passed; only the player's progress is read on
 * the owning thread each time.
 */
final class Advancements {
    private Advancements() {}

    private static final long LIST_TTL_NANOS = 60_000_000_000L;

    private static final class Def {
        final Advancement adv;
        final String key;
        final int criteriaTotal;
        final Map<String, Object> display;      // parent / title / description / frame / hidden / icon, as far as the API gives them; immutable
        Def(Advancement adv, String key, int criteriaTotal, Map<String, Object> display) {
            this.adv = adv; this.key = key; this.criteriaTotal = criteriaTotal; this.display = display;
        }
    }

    private static final class Defs {
        final List<Def> list;
        final long at;
        Defs(List<Def> list, long at) { this.list = list; this.at = at; }
    }

    private static volatile Defs cache;

    static void invalidate() { cache = null; }

    private static List<Def> defs() {
        Defs d = cache;
        long now = System.nanoTime();
        if (d != null && now - d.at < LIST_TTL_NANOS) return d.list;
        List<Def> l = new ArrayList<Def>();
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            Advancement a = it.next();
            int total;
            try { total = a.getCriteria().size(); } catch (Throwable t) { total = -1; }
            l.add(new Def(a, a.getKey().toString(), total, display(a)));
        }
        cache = new Defs(l, now);
        return l;
    }

    /**
     * Parent and display data, all through reflection: {@code Advancement#getParent} is Paper only, {@code Advancement#getDisplay} exists
     * from 1.19.4 and returns Paper's {@code io.papermc.paper.advancement.AdvancementDisplay} ({@code title()}, {@code frame()},
     * {@code icon()}, Adventure components) or the Bukkit one ({@code getTitle()}, {@code getType()}, {@code getIcon()}, legacy strings).
     * Text is plain; hidden advancements and non-display ones (recipes) simply lack the keys.
     */
    private static Map<String, Object> display(Advancement a) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String parent = Reflect.keyOf(Reflect.call(a, "getParent"));
        if (parent != null) m.put("parent", parent);
        Object d = Reflect.call(a, "getDisplay");
        if (d == null) return m;
        String title = plain(d, "title", "getTitle");
        if (title != null) m.put("title", title);
        String desc = plain(d, "description", "getDescription");
        if (desc != null) m.put("description", desc);
        Object frame = Reflect.call(d, "frame");
        if (frame == null) frame = Reflect.call(d, "getType");
        if (frame instanceof Enum) m.put("frame", ((Enum<?>) frame).name().toLowerCase(Locale.ROOT));
        Object hidden = Reflect.call(d, "isHidden");
        if (hidden instanceof Boolean) m.put("hidden", hidden);
        Object icon = Reflect.call(d, firstPresent(d, "icon", "getIcon"));
        if (icon instanceof ItemStack) m.put("icon", ItemConverter.idOf(((ItemStack) icon).getType()));
        return m;
    }

    private static String firstPresent(Object o, String a, String b) { return Reflect.has(o, a, 0) ? a : b; }

    private static final Object PLAIN = Reflect.callStatic("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer", "plainText");

    /** A text of {@code d}: the Adventure component method serialised to plain text, else the legacy string getter without colour codes. */
    private static String plain(Object d, String component, String legacy) {
        Object c = Reflect.call(d, component);
        if (c != null && PLAIN != null) {
            Object s = Reflect.call(PLAIN, "serialize", c);
            if (s instanceof String) return (String) s;
        }
        Object l = Reflect.call(d, legacy);
        return l instanceof String ? ChatColor.stripColor((String) l) : null;
    }

    static Map<String, Object> collect(Player p, boolean includeUnobtained, Deadline deadline) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int n = 0;
        for (Def def : defs()) {
            if ((++n & 63) == 0 && deadline.expired()) break;
            AdvancementProgress pr;
            Collection<String> awarded;
            boolean done;
            try {
                pr = p.getAdvancementProgress(def.adv);
                awarded = pr.getAwardedCriteria();
                done = pr.isDone();
            } catch (Throwable t) {
                continue;                       // removed by a reload since the list was read
            }
            if (awarded.isEmpty() && !done && !includeUnobtained) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("done", done);
            Map<String, Object> crit = new LinkedHashMap<String, Object>();
            for (String c : awarded) {
                Date d = pr.getDateAwarded(c);
                crit.put(c, d == null ? null : RosterFiles.formatDate(d));
            }
            m.put("criteria", crit);
            m.put("criteria_total", def.criteriaTotal >= 0 ? def.criteriaTotal : awarded.size() + pr.getRemainingCriteria().size());
            m.putAll(def.display);
            out.put(def.key, m);
        }
        return out;
    }
}
