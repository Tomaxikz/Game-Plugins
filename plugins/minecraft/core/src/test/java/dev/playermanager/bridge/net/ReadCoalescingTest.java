package dev.playermanager.bridge.net;

import static dev.playermanager.bridge.support.TestClient.data;
import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.support.FakePlatform;
import dev.playermanager.bridge.support.TestClient;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Identical reads inside the coalescing window cost one game-thread computation; actions, quits and expiry bring fresh data. */
class ReadCoalescingTest {
    @TempDir Path root;
    FakePlatform platform;
    BridgeCore core;
    TestClient c;

    @BeforeEach void setUp() throws Exception {
        platform = new FakePlatform(root);
        core = new BridgeCore(platform);
        assertTrue(core.start());
        c = TestClient.unix(root.resolve(".playermanager/bridge.sock"));
        c.hello();
        platform.readCalls.set(0);
    }

    @AfterEach void tearDown() {
        if (c != null) c.close();
        if (core != null) core.stop();
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    @Test void burstOfIdenticalRequestsComputesOnceAndAnswersIdentically() throws Exception {
        platform.delayMs = 150;                                        // keep the first computation in flight while the rest arrive
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 14; i++) ids.add(c.send("player.get", args("id", FakePlatform.ALICE)));
        Map<String, Object> first = null;
        for (int id : ids) {
            Map<String, Object> d = data(c.await(id, 8000));
            if (first == null) first = d; else assertEquals(first, d);
        }
        assertEquals(1, platform.readCalls.get());
        platform.delayMs = 0;
        data(c.call("player.get", args("id", FakePlatform.ALICE)));    // still inside the window: a hit
        assertEquals(1, platform.readCalls.get());
    }

    @Test void sensitiveAndPlainAreSeparateEntries() throws Exception {
        Map<String, Object> plain = data(c.call("player.get", args("id", FakePlatform.ALICE)));
        Map<String, Object> sens = data(c.call("player.get", args("id", FakePlatform.ALICE, "include_sensitive", true)));
        assertFalse(plain.containsKey("ip"));
        assertEquals("1.2.3.4", sens.get("ip"));
        assertEquals(2, platform.readCalls.get());
        assertFalse(data(c.call("player.get", args("id", FakePlatform.ALICE))).containsKey("ip"));
        assertEquals(2, platform.readCalls.get());
    }

    @Test void differentFieldSetsAreSeparateEntries() throws Exception {
        data(c.call("player.get", args("id", FakePlatform.ALICE, "fields", Arrays.asList("health"))));
        data(c.call("player.get", args("id", FakePlatform.ALICE, "fields", Arrays.asList("food"))));
        assertEquals(2, platform.readCalls.get());
    }

    @Test void actionInvalidatesEverything() throws Exception {
        data(c.call("player.inventory", args("id", FakePlatform.ALICE)));
        data(c.call("players.list", args()));
        assertEquals(2, platform.readCalls.get());
        data(c.call("action.run", args("action", "gamemode", "target", FakePlatform.ALICE, "args", args("mode", "creative"))));
        data(c.call("player.inventory", args("id", FakePlatform.ALICE)));
        data(c.call("players.list", args()));
        assertEquals(4, platform.readCalls.get());
    }

    @Test void quitDropsThatPlayerAndTheRoster() throws Exception {
        data(c.call("player.get", args("id", FakePlatform.ALICE)));
        data(c.call("players.list", args()));
        assertEquals(2, platform.readCalls.get());
        core.playerChanged(FakePlatform.ALICE.toUpperCase());
        data(c.call("player.get", args("id", FakePlatform.ALICE)));
        data(c.call("players.list", args()));
        assertEquals(4, platform.readCalls.get());
    }

    @Test void entriesExpire() throws Exception {
        data(c.call("player.get", args("id", FakePlatform.ALICE)));
        Thread.sleep(650);
        data(c.call("player.get", args("id", FakePlatform.ALICE)));
        assertEquals(2, platform.readCalls.get());
    }

    @Test void errorsAreNotCached() throws Exception {
        String bad = "11111111-1111-1111-1111-111111111111";
        assertEquals("not_found", TestClient.errorCode(c.call("player.get", args("id", bad))));
        assertEquals("not_found", TestClient.errorCode(c.call("player.get", args("id", bad))));
        assertEquals(2, platform.readCalls.get());
    }
}
