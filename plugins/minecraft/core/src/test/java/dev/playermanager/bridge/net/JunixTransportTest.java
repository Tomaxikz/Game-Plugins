package dev.playermanager.bridge.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.support.FakePlatform;
import dev.playermanager.bridge.support.TestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JunixTransportTest {
    @TempDir Path root;

    @Test void serverWorksOverJunixsocketAsUsedOnJava8() throws Exception {
        assertTrue(JunixTransport.isAvailable());
        Files.createDirectories(root.resolve(".playermanager"));
        Files.write(root.resolve(".playermanager/config.json"), "{\"platform\":{\"transport\":\"junixsocket\"}}".getBytes(StandardCharsets.UTF_8));
        BridgeCore core = new BridgeCore(new FakePlatform(root));
        try {
            assertTrue(core.start());
            assertEquals("unix", core.transport().name());
            assertTrue(core.transport() instanceof JunixTransport);
            TestClient c = TestClient.unix(root.resolve(".playermanager/bridge.sock"));
            try {
                assertTrue(Boolean.TRUE.equals(c.hello().get("ok")));
                assertTrue(Boolean.TRUE.equals(c.call("players.list", null).get("ok")));
            } finally {
                c.close();
            }
        } finally {
            core.stop();
        }
    }
}
