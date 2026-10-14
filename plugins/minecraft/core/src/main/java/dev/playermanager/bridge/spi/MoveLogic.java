package dev.playermanager.bridge.spi;

import dev.playermanager.bridge.item.ItemRecords;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.proto.BridgeException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure decision table of {@code move_item} / {@code move_item_live} (NOTES.md D24). Platform code gathers the facts and applies the plan. */
public final class MoveLogic {
    private MoveLogic() {}

    public static final String MOVE = "move", STACK = "stack", SWAP = "swap";

    /** What to write into the two slots; counts of 0 mean "empty". */
    public static final class Plan {
        public final String op;
        public final int moved;
        /** For MOVE/SWAP the destination receives the source stack (SWAP: and vice versa) unchanged; for STACK only the counts change. */
        public final int srcCount, destCount;

        Plan(String op, int moved, int srcCount, int destCount) { this.op = op; this.moved = moved; this.srcCount = srcCount; this.destCount = destCount; }

        public Map<String, Object> result() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("op", op);
            m.put("moved", moved);
            return m;
        }
    }

    /**
     * Verifies the destination expectation. Empty destination: {@code destination_expected_id}, if given, must be {@code minecraft:air}
     * (count 0 and fingerprint {@code none} are accepted too). Occupied: the id must be given and equal; count / fingerprint are
     * checked when given ({@code none} never matches an occupied slot).
     *
     * @param current the destination stack or null when empty
     * @throws BridgeException conflict
     */
    public static void verifyDestination(String expId, Integer expCount, String expFingerprint, RawItem current) {
        boolean air = expId != null && (expId.equals("minecraft:air") || expId.equals("air"));
        if (current == null) {
            if (expId != null && !air) throw BridgeException.conflict("destination slot is empty (expected " + expId + ")");
            if (expCount != null && expCount.intValue() != 0) throw BridgeException.conflict("destination slot is empty");
            if (expFingerprint != null && !expFingerprint.equals("none")) throw BridgeException.conflict("destination slot is empty");
            return;
        }
        if (expId == null) throw BridgeException.conflict("destination slot is not empty");
        if (air) throw BridgeException.conflict("destination slot is not empty (now " + current.id + ")");
        if (!expId.equals(current.id)) throw BridgeException.conflict("destination item changed (now " + current.id + ")");
        if (expCount != null && expCount.intValue() != current.count) throw BridgeException.conflict("destination count changed (now " + current.count + ")");
        if (expFingerprint != null && (expFingerprint.equals("none") || !expFingerprint.equals(ItemRecords.fingerprintOf(current)))) {
            throw BridgeException.conflict("destination item changed");
        }
    }

    /**
     * @param srcCount     count of the (non-empty) source stack
     * @param destEmpty    destination holds no item
     * @param similar      both stacks are the same item (id + components); ignored when the destination is empty
     * @param destCount    destination count (ignored when empty)
     * @param destMax      max stack size of the destination item
     * @param srcFitsDest  the source item may be placed into the destination slot
     * @param destFitsSrc  the destination item may be placed into the source slot (ignored when empty and for stacking)
     * @throws BridgeException conflict when an item does not fit a slot
     */
    public static Plan plan(int srcCount, boolean destEmpty, boolean similar, int destCount, int destMax, boolean srcFitsDest, boolean destFitsSrc) {
        if (destEmpty) {
            if (!srcFitsDest) throw BridgeException.conflict("item does not fit the destination slot");
            return new Plan(MOVE, srcCount, 0, srcCount);
        }
        int room = destMax - destCount;
        if (similar && room > 0) {
            int n = Math.min(room, srcCount);
            return new Plan(STACK, n, srcCount - n, destCount + n);
        }
        if (!srcFitsDest || !destFitsSrc) throw BridgeException.conflict("item does not fit the slot it would be swapped into");
        return new Plan(SWAP, srcCount, destCount, srcCount);
    }
}
