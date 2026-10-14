package dev.playermanager.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.item.ItemRecords;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.spi.MoveLogic;
import org.junit.jupiter.api.Test;

class MoveLogicTest {
    private static RawItem item(String id, int n) { return new RawItem(id, n, null, null, null, 0); }

    private static void conflict(Runnable r) {
        BridgeException e = assertThrows(BridgeException.class, r::run);
        assertEquals(ErrorCode.CONFLICT, e.code());
    }

    @Test void emptyDestinationMoves() {
        MoveLogic.Plan p = MoveLogic.plan(10, true, false, 0, 0, true, true);
        assertEquals("move", p.op);
        assertEquals(10, p.moved);
        assertEquals(0, p.srcCount);
        assertEquals(10, p.destCount);
    }

    @Test void similarWithRoomStacksAndLeavesRemainder() {
        MoveLogic.Plan p = MoveLogic.plan(30, false, true, 50, 64, true, true);
        assertEquals("stack", p.op);
        assertEquals(14, p.moved);
        assertEquals(16, p.srcCount);
        assertEquals(64, p.destCount);
        MoveLogic.Plan q = MoveLogic.plan(5, false, true, 10, 64, true, true);
        assertEquals(0, q.srcCount);
        assertEquals(15, q.destCount);
    }

    @Test void similarButFullDestinationSwaps() {
        MoveLogic.Plan p = MoveLogic.plan(3, false, true, 64, 64, true, true);
        assertEquals("swap", p.op);
        assertEquals(3, p.moved);
        assertEquals(64, p.srcCount);
        assertEquals(3, p.destCount);
    }

    @Test void differentItemsSwap() {
        assertEquals("swap", MoveLogic.plan(1, false, false, 7, 64, true, true).op);
    }

    @Test void misfitIsConflict() {
        conflict(() -> MoveLogic.plan(1, true, false, 0, 0, false, true));
        conflict(() -> MoveLogic.plan(1, false, false, 7, 64, false, true));
        conflict(() -> MoveLogic.plan(1, false, false, 7, 64, true, false));
        // stacking never needs the reverse fit
        assertEquals("stack", MoveLogic.plan(1, false, true, 7, 64, true, false).op);
    }

    @Test void destinationExpectations() {
        RawItem dirt = item("minecraft:dirt", 4);
        // empty
        MoveLogic.verifyDestination(null, null, null, null);
        MoveLogic.verifyDestination("minecraft:air", 0, "none", null);
        conflict(() -> MoveLogic.verifyDestination("minecraft:dirt", null, null, null));
        // occupied
        conflict(() -> MoveLogic.verifyDestination(null, null, null, dirt));
        conflict(() -> MoveLogic.verifyDestination("minecraft:air", null, null, dirt));
        conflict(() -> MoveLogic.verifyDestination("minecraft:stone", null, null, dirt));
        conflict(() -> MoveLogic.verifyDestination("minecraft:dirt", 5, null, dirt));
        conflict(() -> MoveLogic.verifyDestination("minecraft:dirt", null, "none", dirt));
        conflict(() -> MoveLogic.verifyDestination("minecraft:dirt", null, "0".repeat(64), dirt));
        MoveLogic.verifyDestination("minecraft:dirt", 4, ItemRecords.fingerprintOf(dirt), dirt);
        MoveLogic.verifyDestination("minecraft:dirt", null, null, dirt);
    }
}
