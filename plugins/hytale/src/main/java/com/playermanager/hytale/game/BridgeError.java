package com.playermanager.hytale.game;

/** A protocol error with one of the codes from docs/protocol/README.md §4. */
public final class BridgeError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public BridgeError(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static BridgeError unsupported(String message) {
        return new BridgeError("unsupported", message);
    }

    public static BridgeError invalid(String message) {
        return new BridgeError("invalid_args", message);
    }

    public static BridgeError notFound(String message) {
        return new BridgeError("not_found", message);
    }

    public static BridgeError timeout(String message) {
        return new BridgeError("timeout", message);
    }

    public static BridgeError internal(String message) {
        return new BridgeError("internal", message);
    }
}
