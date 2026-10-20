package dev.playermanager.bridge.sub;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.spi.PositionSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Off-thread cost of quantise + diff + framed encode for 500 players, samples pre-built so only the pipeline is timed (printed, loosely asserted). */
class PositionPipelineBenchTest {
    private static final int N = 500;

    private static List<List<PositionSample>> frames(int count) {
        Random rnd = new Random(5);
        double[] x = new double[N], z = new double[N];
        float[] yaw = new float[N];
        for (int i = 0; i < N; i++) { x[i] = rnd.nextInt(2000) - 1000; z[i] = rnd.nextInt(2000) - 1000; }
        String[] ids = new String[N], names = new String[N];
        for (int i = 0; i < N; i++) { ids[i] = "00000000-0000-0000-0000-" + String.format("%012d", i); names[i] = "Player" + i; }
        List<List<PositionSample>> out = new ArrayList<List<PositionSample>>();
        for (int f = 0; f < count; f++) {
            List<PositionSample> l = new ArrayList<PositionSample>(N);
            for (int i = 0; i < N; i++) {
                if (rnd.nextInt(4) == 0) { x[i] += rnd.nextDouble() * 0.5; z[i] += rnd.nextDouble() * 0.5; yaw[i] = rnd.nextInt(360); }
                l.add(new PositionSample(ids[i], names[i], "world", "minecraft:overworld", "normal", x[i], 64, z[i], yaw[i], 10f, "alive"));
            }
            out.add(l);
        }
        return out;
    }

    private static PositionSubscription sub(int id) {
        return new PositionSubscription(id, new SubscriptionSink() {
            public void wake(Subscription s) { }
            public boolean isOpen() { return true; }
        }, 250, null, null);
    }

    private double run(int subscribers) {
        List<List<PositionSample>> fr = frames(300);
        PositionSubscription[] subs = new PositionSubscription[subscribers];
        for (int i = 0; i < subscribers; i++) subs[i] = sub(i + 1);
        long bytes = 0, t0 = 0;
        int rounds = fr.size() - 100;
        for (int f = 0; f < fr.size(); f++) {
            if (f == 100) t0 = System.nanoTime();
            long now = f * 250L;
            for (PositionSubscription s : subs) {
                for (Map<String, Object> m : s.computeAll(fr.get(f), now)) bytes += MsgPack.encodeFramed(m).length;
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6 / rounds;
        System.out.println("positions pipeline: " + subscribers + " subscriber(s): " + String.format("%.3f", ms) + " ms per 500-player frame, " + bytes / fr.size() + " bytes/frame");
        return ms;
    }

    @Test void pipelineStaysCheap() {
        double one = run(1), four = run(4);
        assertTrue(one < 10.0 && four < 40.0, "too slow: " + one + " / " + four);
    }
}
