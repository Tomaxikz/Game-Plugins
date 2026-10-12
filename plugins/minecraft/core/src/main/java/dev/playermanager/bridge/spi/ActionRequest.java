package dev.playermanager.bridge.spi;

import java.util.Map;

/** A validated {@code action.run} call handed to the platform. {@code args} contains only keys declared in the ActionSpec, already type/range checked. */
public final class ActionRequest {
    public final ActionSpec spec;
    /** Player id (lowercase UUID) or, when {@link #targetIsIp}, the IP literal; null for server-wide actions. */
    public final String target;
    public final boolean targetIsIp;
    public final Map<String, Object> args;
    /** Non-null for item-editing actions (built from {@code expect} or {@code expected_*} args). */
    public final Expect expect;
    public final Deadline deadline;

    public ActionRequest(ActionSpec spec, String target, boolean targetIsIp, Map<String, Object> args, Expect expect, Deadline deadline) {
        this.spec = spec; this.target = target; this.targetIsIp = targetIsIp; this.args = args; this.expect = expect; this.deadline = deadline;
    }
}
