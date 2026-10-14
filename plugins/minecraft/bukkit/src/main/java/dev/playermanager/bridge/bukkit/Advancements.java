package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.Deadline;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

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
        Def(Advancement adv, String key, int criteriaTotal) { this.adv = adv; this.key = key; this.criteriaTotal = criteriaTotal; }
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
            l.add(new Def(a, a.getKey().toString(), total));
        }
        cache = new Defs(l, now);
        return l;
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
            out.put(def.key, m);
        }
        return out;
    }
}
