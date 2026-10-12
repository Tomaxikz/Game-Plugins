package dev.playermanager.bridge.sub;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.spi.PositionSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Off-thread cost of the position pipeline for 500 players (quantise + diff + encode); the game thread only copies primitives. */
class PositionThroughputTest {
    @Test void fiveHundredPlayersAtFourHertzIsCheap() {
        Random rnd = new Random(3);
        int n = 500;
        double[] x = new double[n], z = new double[n];
        for (int i = 0; i < n; i++) { x[i] = rnd.nextInt(2000) - 1000; z[i] = rnd.nextInt(2000) - 1000; }
        PositionSubscription s = new PositionSubscription(1, new SubscriptionSink() {
            public void wake(Subscription sub) { }
            public boolean isOpen() { return true; }
        }, 250, null, null);
        long bytes = 0;
        // warm up
        for (int round = 0; round < 40; round++) run(s, x, z, rnd, n, round * 250L);
        long t0 = System.nanoTime();
        int rounds = 200;
        for (int round = 0; round < rounds; round++) bytes += run(s, x, z, rnd, n, 20_000L + round * 250L);
        double msPerFrame = (System.nanoTime() - t0) / 1e6 / rounds;
        System.out.println("positions: " + msPerFrame + " ms per 500-player frame (quantise+diff+encode), " + bytes / rounds + " bytes/frame");
        assertTrue(msPerFrame < 15.0, "too slow: " + msPerFrame + " ms");
    }

    private static long run(PositionSubscription s, double[] x, double[] z, Random rnd, int n, long now) {
        List<PositionSample> samples = new ArrayList<PositionSample>(n);
        for (int i = 0; i < n; i++) {
            if (rnd.nextInt(4) == 0) { x[i] += rnd.nextDouble() * 0.5; z[i] += rnd.nextDouble() * 0.5; }
            samples.add(new PositionSample("00000000-0000-0000-0000-" + String.format("%012d", i), "Player" + i, "world", "minecraft:overworld", x[i], 64, z[i], rnd.nextInt(360), 10f, "alive"));
        }
        long bytes = 0;
        for (Map<String, Object> f : s.computeAll(samples, now)) bytes += MsgPack.encode(f).length;
        return bytes;
    }
}
