package dev.playermanager.bridge.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The typed actions a platform supports. Core validates arguments against the specs before calling the handler. */
public final class ActionRegistry {
    private final Map<String, ActionSpec> specs = new LinkedHashMap<String, ActionSpec>();
    private final Map<String, ActionHandler> handlers = new LinkedHashMap<String, ActionHandler>();

    public ActionRegistry register(ActionSpec spec, ActionHandler handler) {
        specs.put(spec.id, spec);
        handlers.put(spec.id, handler);
        return this;
    }

    public ActionSpec spec(String id) { return specs.get(id); }
    public ActionHandler handler(String id) { return handlers.get(id); }
    public List<ActionSpec> specs() { return new ArrayList<ActionSpec>(specs.values()); }
}
