package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reply envelopes: {@code {id, ok:true, data}} or {@code {id, ok:false, error:{code, message}}}, encoded straight
 * into frames ready for {@link Conn#sendFrame} (docs/protocol/README.md §3). Key order is part of the wire format.
 */
final class Replies {
    private Replies() {
    }

    /** An encoded reply and whether it carries the op's data (false: an error stands in, so no afterReply runs). */
    record Sealed(byte[] frame, boolean delivered) {
    }

    static byte[] error(long id, String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("ok", false);
        r.put("error", err);
        return Msgpack.encodeFramed(r, false);
    }

    static byte[] error(long id, BridgeError e) {
        return error(id, e.code(), e.getMessage());
    }

    /** A request failed outside the dispatcher: the cause goes to the log, the peer gets a generic internal error. */
    static byte[] internal(long id) {
        return error(id, "internal", "bridge error, see bridge.log");
    }

    /**
     * The success reply for {@code data}, or an error reply when it cannot be sent: larger than {@code maxFrame}
     * ({@code unsupported}) or not encodable ({@code internal}, logged).
     */
    static Sealed ok(long id, Map<String, Object> data, int maxFrame, BridgeLog log, String op) {
        try {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", id);
            r.put("ok", true);
            r.put("data", data == null ? Map.of() : data);
            byte[] frame = Msgpack.encodeFramed(r, false);
            if (frame.length - Framing.PREFIX > maxFrame) {
                return new Sealed(error(id, "unsupported", "response exceeds max_frame"), false);
            }
            return new Sealed(frame, true);
        } catch (RuntimeException e) {
            log.error("response for " + op + " could not be encoded", e);
            return new Sealed(internal(id), false);
        }
    }
}
