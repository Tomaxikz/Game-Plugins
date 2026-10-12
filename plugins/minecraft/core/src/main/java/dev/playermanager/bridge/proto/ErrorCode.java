package dev.playermanager.bridge.proto;

/** Protocol error codes (README section 4). */
public enum ErrorCode {
    UNSUPPORTED, INVALID_ARGS, NOT_FOUND, OFFLINE, UNAUTHORIZED, BUSY, TIMEOUT, CONFLICT, INTERNAL;

    public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
}
