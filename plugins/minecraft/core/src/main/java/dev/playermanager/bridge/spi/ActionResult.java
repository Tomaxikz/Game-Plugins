package dev.playermanager.bridge.spi;

/** Outcome of an executed action. {@code ok=false} means "executed, but the game refused". */
public final class ActionResult {
    public final boolean ok;
    public final String message;
    public final Object result;

    public ActionResult(boolean ok, String message, Object result) { this.ok = ok; this.message = message; this.result = result; }

    public static ActionResult ok() { return new ActionResult(true, null, null); }
    public static ActionResult ok(Object result) { return new ActionResult(true, null, result); }
    public static ActionResult refused(String message) { return new ActionResult(false, message, null); }
}
