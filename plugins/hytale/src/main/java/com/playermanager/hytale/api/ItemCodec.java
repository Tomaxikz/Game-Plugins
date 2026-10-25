package com.playermanager.hytale.api;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.playermanager.hytale.wire.Canonical;
import com.playermanager.hytale.wire.MsgpackSize;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Encodes an ItemStack as the generic item record (items-and-assets.md §1) and computes hash and fingerprint, in two
 * steps so the world thread does as little as possible: {@link #snapshot} (world thread) copies primitives plus a
 * clone of the metadata document; {@link #encode} (any thread, normally the bridge thread that asked) builds the maps,
 * the canonical encoding, the hash and the fingerprint. Hytale item ids are sent exactly as the API reports them
 * (CamelCase_with_underscores): the bridge never invents a namespace.
 */
final class ItemCodec {
    /** Component payloads above this are dropped from player.inventory records and flagged truncated. */
    static final int INVENTORY_COMPONENT_LIMIT = 64 * 1024;
    /** item.get and expect checks allow far larger payloads. */
    static final int FULL_COMPONENT_LIMIT = 1024 * 1024;

    /** Everything encode needs from an ItemStack; immutable apart from the private metadata clone. */
    record Snapshot(String id, int count, double maxDurability, double durability, boolean unbreakable, String model,
                    int quality, BsonDocument metadata) {
    }

    private ItemCodec() {
    }

    /** World thread: reads the stack, nothing else (no maps, no encoding, no hashing). */
    static Snapshot snapshot(ItemStack s) {
        String id = s.getItemId();
        int count = s.getQuantity();
        double max = s.getMaxDurability();
        boolean unbreakable = s.isUnbreakable();
        double durability = s.getDurability();
        String model = modelOf(s);
        BsonDocument meta = s.getMetadata();
        BsonDocument copy = meta == null || meta.isEmpty() ? null : meta.clone();
        int quality = s.getQualityIndex();
        return new Snapshot(id, count, max, durability, unbreakable, model, quality, copy);
    }

    /** Snapshots every non-empty stack of a container on the world thread, keyed by slot. */
    static Map<Integer, Snapshot> snapshotSlots(ItemContainer c) {
        Map<Integer, Snapshot> out = new LinkedHashMap<>();
        for (int i = 0; i < c.getCapacity(); i++) {
            ItemStack s = c.getItemStack((short) i);
            if (s != null && !s.isEmpty()) {
                out.put(i, snapshot(s));
            }
        }
        return out;
    }

    /** Bridge thread: the item record. {@code truncated} replaces oversized components (see the limits above). */
    static Map<String, Object> encode(Snapshot s, int componentLimit) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", s.id());
        rec.put("count", (long) s.count());
        double max = s.maxDurability();
        if (max > 0 && !s.unbreakable()) {
            long maxL = Math.round(max);
            rec.put("max_damage", maxL);
            rec.put("damage", Math.max(0L, Math.round(max - s.durability())));
        }
        String model = s.model();
        if (model != null && !model.isEmpty()) {
            rec.put("model", model);
        }
        Map<String, Object> comps = new LinkedHashMap<>();
        if (s.metadata() != null) {
            comps.put("metadata", fromBson(s.metadata(), 0));
        }
        if (s.quality() > 0) {
            comps.put("quality", (long) s.quality());
        }
        boolean truncated = false;
        if (!comps.isEmpty()) {
            // the limit applies to the plain (non-canonical) encoding of the components
            if (MsgpackSize.of(comps, false) > componentLimit) {
                truncated = true;
            } else {
                rec.put("components", comps);
            }
        }
        rec.put("hash", Canonical.hash(Canonical.renderSubset(rec)));
        rec.put("fingerprint", Canonical.fingerprint(rec));
        if (truncated) {
            rec.put("truncated", true);
        }
        return rec;
    }

    /** Snapshot plus encode in one call, for the rare caller that has to decide on the world thread. */
    static Map<String, Object> encode(ItemStack s, int componentLimit) {
        return encode(snapshot(s), componentLimit);
    }

    private static String modelOf(ItemStack s) {
        try {
            Item item = s.getItem();
            return item == null ? null : item.getModel();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Object fromBson(BsonValue v, int depth) {
        if (v == null || v.isNull() || depth > 8) {
            return null;
        }
        switch (v.getBsonType()) {
            case DOCUMENT -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<String, BsonValue> e : v.asDocument().entrySet()) {
                    m.put(e.getKey(), fromBson(e.getValue(), depth + 1));
                }
                return m;
            }
            case ARRAY -> {
                List<Object> l = new ArrayList<>();
                for (BsonValue x : v.asArray()) {
                    l.add(fromBson(x, depth + 1));
                }
                return l;
            }
            case STRING -> {
                return v.asString().getValue();
            }
            case INT32 -> {
                return (long) v.asInt32().getValue();
            }
            case INT64 -> {
                return v.asInt64().getValue();
            }
            case DOUBLE -> {
                return v.asDouble().getValue();
            }
            case BOOLEAN -> {
                return v.asBoolean().getValue();
            }
            case DATE_TIME -> {
                return v.asDateTime().getValue();
            }
            case BINARY -> {
                return v.asBinary().getData();
            }
            case OBJECT_ID -> {
                return HexFormat.of().formatHex(v.asObjectId().getValue().toByteArray());
            }
            default -> {
                return String.valueOf(v);
            }
        }
    }
}
