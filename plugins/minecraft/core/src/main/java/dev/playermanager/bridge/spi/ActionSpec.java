package dev.playermanager.bridge.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Capability description of an action (operations.md section 1 "ActionSpec"). */
public final class ActionSpec {
    public final String id;
    public final String label;
    public final boolean target;
    public final boolean destructive;
    public final boolean offline;
    public final List<Field> fields;
    /** {@code target} may also be an IP literal (ban_ip / unban_ip). */
    public final boolean ipTarget;

    public ActionSpec(String id, String label, boolean target, boolean destructive, boolean offline, boolean ipTarget, List<Field> fields) {
        this.id = id; this.label = label; this.target = target; this.destructive = destructive; this.offline = offline;
        this.ipTarget = ipTarget;
        this.fields = Collections.unmodifiableList(new ArrayList<Field>(fields));
    }

    public static ActionSpec of(String id, boolean target, boolean destructive, Field... fields) {
        return new ActionSpec(id, id, target, destructive, false, false, Arrays.asList(fields));
    }

    public ActionSpec offline() { return new ActionSpec(id, label, target, destructive, true, ipTarget, fields); }
    public ActionSpec ipTarget() { return new ActionSpec(id, label, target, destructive, offline, true, fields); }

    public Field field(String key) {
        for (Field f : fields) if (f.key.equals(key)) return f;
        return null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("label", label);
        m.put("target", target);
        m.put("destructive", destructive);
        m.put("offline", offline);
        List<Object> fs = new ArrayList<Object>();
        for (Field f : fields) fs.add(f.toMap());
        m.put("fields", fs);
        return m;
    }
}
