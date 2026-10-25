package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Expect;
import com.playermanager.hytale.game.GameProvider;
import java.util.Map;

/** action.run: checks the action against what is advertised, validates its arguments, then calls the provider. */
final class ActionOp implements OpHandler {
    private final GameProvider provider;
    private final Advertise advertise;
    private final ReadCache reads;

    ActionOp(GameProvider provider, Advertise advertise, ReadCache reads) {
        this.provider = provider;
        this.advertise = advertise;
        this.reads = reads;
    }

    @Override
    public Dispatcher.Result handle(Args args, Conn conn) {
        String action = args.str("action", "action is required");
        Map<String, Object> spec = advertise.actionSpec(action);
        if (spec == null) {
            throw BridgeError.unsupported("action not supported by this build: " + action);
        }
        String target = Boolean.TRUE.equals(spec.get("target")) ? args.uuid("target") : null;
        Args actionArgs = args.map("args");
        Map<String, Object> clean = ActionArgs.validate(spec, actionArgs);
        Expect expect = args.has("expect") ? ActionArgs.parseExpect(args.map("expect"), clean) : null;
        // Any action may change what a cached read shows: drop the target's entries before and after it runs.
        reads.invalidate(target);
        try {
            return Dispatcher.Result.of(provider.runAction(action, target, clean, expect));
        } finally {
            reads.invalidate(target);
        }
    }
}
