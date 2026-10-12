package dev.playermanager.bridge.proto;

/** Thrown by providers/handlers to produce a protocol error response. */
public class BridgeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;

    public BridgeException(ErrorCode code, String message) { super(message, null, false, false); this.code = code; }

    public ErrorCode code() { return code; }

    public static BridgeException invalid(String m) { return new BridgeException(ErrorCode.INVALID_ARGS, m); }
    public static BridgeException notFound(String m) { return new BridgeException(ErrorCode.NOT_FOUND, m); }
    public static BridgeException offline(String m) { return new BridgeException(ErrorCode.OFFLINE, m); }
    public static BridgeException unsupported(String m) { return new BridgeException(ErrorCode.UNSUPPORTED, m); }
    public static BridgeException conflict(String m) { return new BridgeException(ErrorCode.CONFLICT, m); }
    public static BridgeException timeout(String m) { return new BridgeException(ErrorCode.TIMEOUT, m); }
    public static BridgeException busy(String m) { return new BridgeException(ErrorCode.BUSY, m); }
    public static BridgeException internal(String m) { return new BridgeException(ErrorCode.INTERNAL, m); }
}
