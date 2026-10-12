package dev.playermanager.bridge.sub;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** {@code events.subscribe}: per-subscription queue capped at 1024 frames. */
public final class EventSubscription extends Subscription {
    public static final int QUEUE_CAP = 1024;
    public final Set<String> types;
    private final EventBus bus;

    EventSubscription(int id, SubscriptionSink sink, Set<String> types, EventBus bus) {
        super(id, "events", sink, QUEUE_CAP);
        this.types = types; this.bus = bus;
    }

    @Override protected Map<String, Object> onOverflow(long dropped) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("type", "overflow");
        d.put("dropped", dropped);
        return d;
    }

    @Override protected void onClose() { bus.remove(this); }
}
