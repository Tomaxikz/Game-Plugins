package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The bounded nearest-N selection must equal "stable sort by distance, first N" (the previous implementation). */
class NearbyReadsTest {
    private record Item(double dist, int id) {
    }

    private static List<Item> reference(List<Item> items, int limit) {
        List<Item> all = new ArrayList<>(items);
        all.sort(Comparator.comparingDouble(Item::dist)); // List.sort is stable
        return new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
    }

    private static List<Item> viaHeap(List<Item> items, int limit) {
        NearbyReads.Nearest<Item> n = new NearbyReads.Nearest<>(limit);
        items.forEach(i -> n.offer(i.dist(), i));
        return n.sorted();
    }

    @Test
    void matchesSortAndLimitIncludingTies() {
        Random r = new Random(5);
        for (int round = 0; round < 300; round++) {
            int size = r.nextInt(300);
            int limit = 1 + r.nextInt(80);
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                // few distinct distances: many ties; some NaN and zero
                double d = r.nextInt(25) == 0 ? Double.NaN : r.nextInt(12) / 2.0;
                items.add(new Item(d, i));
            }
            assertEquals(reference(items, limit), viaHeap(items, limit), "size " + size + " limit " + limit);
        }
    }

    @Test
    void holdsAtMostTheLimitAndHandlesEmptyAndShortInput() {
        assertEquals(List.of(), viaHeap(List.of(), 64));
        List<Item> few = List.of(new Item(3, 0), new Item(1, 1), new Item(2, 2));
        assertEquals(List.of(few.get(1), few.get(2), few.get(0)), viaHeap(few, 64));
        List<Item> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add(new Item(1000 - i, i));
        }
        List<Item> top = viaHeap(many, NearbyReads.MAX_ENTITIES);
        assertEquals(64, top.size());
        assertEquals(1.0, top.get(0).dist());
        assertEquals(64.0, top.get(63).dist());
    }
}
