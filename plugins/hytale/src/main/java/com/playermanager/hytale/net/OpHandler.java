package com.playermanager.hytale.net;

/** One protocol op: validates its arguments (via {@link Args}), calls into the game or a service, returns the reply. */
@FunctionalInterface
interface OpHandler {
    /** Throws {@code BridgeError} on failure. {@code conn} is null when called outside a connection (tests). */
    Dispatcher.Result handle(Args args, Conn conn);
}
