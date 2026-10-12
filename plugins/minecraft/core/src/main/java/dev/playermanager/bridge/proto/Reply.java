package dev.playermanager.bridge.proto;

import dev.playermanager.bridge.sub.Subscription;

/** Handler result that must activate a subscription only after the response frame was queued. */
public final class Reply {
    public final Object data;
    public final Subscription activate;

    public Reply(Object data, Subscription activate) { this.data = data; this.activate = activate; }
}
