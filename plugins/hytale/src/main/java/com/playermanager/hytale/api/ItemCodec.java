package com.playermanager.hytale.api;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.playermanager.hytale.wire.Canonical;
import com.playermanager.hytale.wire.Msgpack;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Encodes an ItemStack as the generic item record (items-and-assets.md §1) and computes hash and
 * fingerprint. Must be called on the world thread (it reads the stack); the returned map is an
 * immutable-by-convention copy. Hytale item ids are sent exactly as the API reports them
 * (CamelCase_with_underscores): the bridge never invents a namespace.
 */
final class ItemCodec {
    /** Component payloads above this are dropped from player.inventory records and flagged truncated. */
    static final int INVENTORY_COMPONENT_LIMIT = 64 * 1024;
    /** item.get and expect checks allow far larger payloads. */
    static final int FULL_COMPONENT_LIMIT = 1024 * 1024;

    private ItemCodec() {
    }

    static Map<String, Object> encode(ItemStack s, int componentLimit) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", s.getItemId());
        rec.put("count", (long) s.getQuantity());
        double max = s.getMaxDurability();
        if (max > 0 && !s.isUnbreakable()) {
            long maxL = Math.round(max);
            rec.put("max_damage", maxL);
            rec.put("damage", Math.max(0L, Math.round(max - s.getDurability())));
        }
        String model = modelOf(s);
        if (model != null && !model.isEmpty()) {
            rec.put("model", model);
        }
        Map<String, Object> comps = new LinkedHashMap<>();
        BsonDocument meta = s.getMetadata();
        if (meta != null && !meta.isEmpty()) {
            comps.put("metadata", fromBson(meta, 0));
        }
        int quality = s.getQualityIndex();
        if (quality > 0) {
            comps.put("quality", (long) quality);
        }
        boolean truncated = false;
        if (!comps.isEmpty()) {
            if (Msgpack.encode(comps, false).length > componentLimit) {
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
