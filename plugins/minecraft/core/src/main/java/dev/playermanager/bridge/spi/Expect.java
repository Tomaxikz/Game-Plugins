package dev.playermanager.bridge.spi;

import dev.playermanager.bridge.item.ItemRecords;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.proto.BridgeException;

/** Item precondition of {@code action.run} (operations.md section 9). Verify on the owning game thread. */
public final class Expect {
    public final String container;
    public final Object slot;          // Long or String
    public final String id;            // nullable
    public final Integer count;        // nullable
    public final String fingerprint;   // nullable

    public Expect(String container, Object slot, String id, Integer count, String fingerprint) {
        this.container = container; this.slot = slot; this.id = id; this.count = count; this.fingerprint = fingerprint;
    }

    /** @param current the item currently in {@link #container}/{@link #slot}, or null if empty. @throws BridgeException conflict */
    public void verify(RawItem current) {
        if (current == null) throw BridgeException.conflict("slot is empty");
        if (id != null && !id.equals(current.id)) throw BridgeException.conflict("item id changed (now " + current.id + ")");
        if (count != null && count.intValue() != current.count) throw BridgeException.conflict("item count changed (now " + current.count + ")");
        if (fingerprint != null && !fingerprint.equals(ItemRecords.fingerprintOf(current))) throw BridgeException.conflict("item changed");
    }
}
