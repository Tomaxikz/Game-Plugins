package dev.playermanager.bridge.sub;

/** Implemented by the connection: told when a subscription has frames to write. */
public interface SubscriptionSink {
    void wake(Subscription s);
    boolean isOpen();
}
