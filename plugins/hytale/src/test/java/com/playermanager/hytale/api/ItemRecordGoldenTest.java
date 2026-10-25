package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * Pins the item record bytes (hash, fingerprint) and the truncation rule (64 KiB inventory / 1 MiB item.get limit,
 * measured on the non-canonical encoding of the components) for fixed inputs. The expected values were computed by
 * the encoder before it was split into a world-thread snapshot and a bridge-thread encode.
 */
class ItemRecordGoldenTest {
    static final int INV = ItemCodec.INVENTORY_COMPONENT_LIMIT;
    static final int FULL = ItemCodec.FULL_COMPONENT_LIMIT;

    /** What the test knows about one encoded record. */
    record Expected(String hash, String fingerprint, boolean truncated, boolean components, String keys) {
    }

    /** An Item that only knows its model (the real one needs the asset store). */
    static final class ModelItem extends Item {
        private final String model;

        ModelItem(String model) {
            this.model = model;
        }

        @Override
        public String getModel() {
            return model;
        }
    }

    /** An ItemStack with fixed fields: the public constructors look the item up in the (absent) asset store. */
    static final class Fixed extends ItemStack {
        private final Item item;
        private final boolean failing;

        Fixed(String id, int count, double durability, double max, int quality, BsonDocument meta, Item item,
              boolean failing) {
            this.itemId = id;
            this.quantity = count;
            this.durability = durability;
            this.maxDurability = max;
            this.qualityIndexOverride = quality;
            this.metadata = meta;
            this.item = item;
            this.failing = failing;
        }

        @Override
        public Item getItem() {
            if (failing) {
                throw new IllegalStateException("no asset store");
            }
            return item;
        }
    }

    static ItemStack stack(String id, int count, double durability, double max, int quality, BsonDocument meta) {
        return new Fixed(id, count, durability, max, quality, meta, null, false);
    }

    static ItemStack withModel(String model) {
        return new Fixed("Weapon_Sword_Iron", 1, 10, 50, 0, null, new ModelItem(model), false);
    }

    /** One entry per case: stack, limit. */
    record Case(ItemStack stack, int limit) {
    }

    static BsonDocument rich() {
        return new BsonDocument("name", new BsonString("Blade é中"))
                .append("small", new BsonInt32(7))
                .append("neg", new BsonInt32(-200))
                .append("big", new BsonInt64(5_000_000_000L))
                .append("whole", new BsonDouble(3.0))
                .append("frac", new BsonDouble(2.5))
                .append("flag", new BsonBoolean(true))
                .append("nothing", BsonNull.VALUE)
                .append("when", new BsonDateTime(1_760_000_000_000L))
                .append("blob", new BsonBinary(new byte[] {1, 2, 3, (byte) 0xff}))
                .append("oid", new BsonObjectId(new ObjectId("0123456789abcdef01234567")))
                .append("list", new BsonArray(java.util.List.of(new BsonInt32(1), new BsonString("two"),
                        new BsonDocument("z", new BsonInt32(1)).append("a", new BsonInt32(2)))))
                .append("zebra", new BsonString("last"))
                .append("alpha", new BsonString("first"));
    }

    static BsonDocument deep(int levels) {
        BsonDocument d = new BsonDocument("leaf", new BsonString("bottom"));
        for (int i = 0; i < levels; i++) {
            d = new BsonDocument("n" + i, d);
        }
        return d;
    }

    /** {"metadata":{"k":<n chars>}} encodes (non-canonical) to 16 + n bytes up to n=65535 and 18 + n above. */
    static BsonDocument padded(int n) {
        return new BsonDocument("k", new BsonString("x".repeat(n)));
    }

    static Map<String, Case> cases() {
        Map<String, Case> c = new LinkedHashMap<>();
        c.put("plain", new Case(stack("Plant_Fiber", 12, 0, 0, 0, null), INV));
        c.put("durable", new Case(stack("Weapon_Sword_Iron", 1, 37.6, 100, 0, null), INV));
        c.put("broken", new Case(stack("Weapon_Sword_Iron", 1, 0, 100, 0, null), INV));
        c.put("unbreakable", new Case(stack("Tool_Pickaxe", 1, 5, 0, 0, null), INV));
        c.put("model", new Case(withModel("Items/Weapons/Sword_Iron.blockymodel"), INV));
        c.put("emptyModel", new Case(withModel(""), INV));
        c.put("modelLookupFails", new Case(new Fixed("Weapon_Sword_Iron", 1, 10, 50, 0, null, null, true), INV));
        c.put("quality", new Case(stack("Armor_Head_Gold", 1, 10, 50, 3, null), INV));
        c.put("rich", new Case(stack("Weapon_Sword_Iron", 1, 10, 50, 2, rich()), INV));
        c.put("emptyMeta", new Case(stack("Plant_Fiber", 3, 0, 0, 0, new BsonDocument()), INV));
        c.put("deep", new Case(stack("Plant_Fiber", 3, 0, 0, 0, deep(12)), INV));
        c.put("atInvLimit", new Case(stack("Plant_Fiber", 1, 0, 0, 0, padded(65520)), INV));
        c.put("overInvLimit", new Case(stack("Plant_Fiber", 1, 0, 0, 0, padded(65521)), INV));
        c.put("overInvLimitFull", new Case(stack("Plant_Fiber", 1, 0, 0, 0, padded(65521)), FULL));
        c.put("atFullLimit", new Case(stack("Plant_Fiber", 1, 0, 0, 0, padded(1048558)), FULL));
        c.put("overFullLimit", new Case(stack("Plant_Fiber", 1, 0, 0, 0, padded(1048559)), FULL));
        c.put("truncatedWithQuality", new Case(stack("Armor_Head_Gold", 1, 10, 50, 3, padded(70000)), INV));
        return c;
    }

    static Map<String, Object> encode(Case c) {
        return ItemCodec.encode(ItemCodec.snapshot(c.stack()), c.limit());
    }

    static Expected describe(Map<String, Object> rec) {
        return new Expected((String) rec.get("hash"), (String) rec.get("fingerprint"),
                rec.containsKey("truncated"), rec.containsKey("components"), String.join(",", rec.keySet()));
    }

    static final Map<String, Expected> EXPECTED = expected();

    private static Map<String, Expected> expected() {
        Map<String, Expected> e = new LinkedHashMap<>();
        e.put("plain", new Expected("28a1e76f4931d525",
                "ee57e27a2fa4e2ece7689485ef7d97e93d104c637831b4b0c271fec3e8cc1ff7",
                false, false, "id,count,hash,fingerprint"));
        e.put("durable", new Expected("ddcdbb4532c78ba6",
                "c094502f0ee20cef093b6bab05d1643f620fac100480778a1d542d092b766acd",
                false, false, "id,count,max_damage,damage,hash,fingerprint"));
        e.put("broken", new Expected("ddcdbb4532c78ba6",
                "5333d5cc1ae3607fd34b77b0cb1304667fecd156bfb189eab4e3329a4b6f03bf",
                false, false, "id,count,max_damage,damage,hash,fingerprint"));
        e.put("unbreakable", new Expected("bd7d406091421ade",
                "af73c7c3767891ae1c807cc0927b255a8cfbf197096fc89c11177ed10b38f4f2",
                false, false, "id,count,hash,fingerprint"));
        e.put("model", new Expected("49330f21bd2c6aa9",
                "aefe0e38f511bbd0f34e0524927ab3ad61287644166ee1c3b3734b6a9d33b079",
                false, false, "id,count,max_damage,damage,model,hash,fingerprint"));
        e.put("emptyModel", new Expected("ddcdbb4532c78ba6",
                "b4c10e426fe6fe3915968c15c0616969a654bcfc097030530ec5a854b0fdc7aa",
                false, false, "id,count,max_damage,damage,hash,fingerprint"));
        e.put("modelLookupFails", new Expected("ddcdbb4532c78ba6",
                "b4c10e426fe6fe3915968c15c0616969a654bcfc097030530ec5a854b0fdc7aa",
                false, false, "id,count,max_damage,damage,hash,fingerprint"));
        e.put("quality", new Expected("0b95ba635cd0077e",
                "bdeac4404a3468136ff3c3a2842387fe6ae889dee0868cc5fbf0f47e9dea3685",
                false, true, "id,count,max_damage,damage,components,hash,fingerprint"));
        e.put("rich", new Expected("ddcdbb4532c78ba6",
                "87a334d939a1090a1573404f6a74240b37f770c054fd7defcd952720af46238f",
                false, true, "id,count,max_damage,damage,components,hash,fingerprint"));
        e.put("emptyMeta", new Expected("28a1e76f4931d525",
                "bcca8d9796131b432ad72822d2cce0d5800cf042d21097b2488250d093cfb21a",
                false, false, "id,count,hash,fingerprint"));
        e.put("deep", new Expected("28a1e76f4931d525",
                "90066eaf48d1ea898ed4c9a9a106bfd4e8fe02ce48536c6f07960e7e76f55a1f",
                false, true, "id,count,components,hash,fingerprint"));
        e.put("atInvLimit", new Expected("28a1e76f4931d525",
                "b1a04187e81618159fd80fcfd9cfd4cfaa597180f2f2d1674212f052163e9d28",
                false, true, "id,count,components,hash,fingerprint"));
        e.put("overInvLimit", new Expected("28a1e76f4931d525",
                "a68867083ce8840ff950722f1132fad4ec7e8ac53210fa8bd89a6567e7ea9864",
                true, false, "id,count,hash,fingerprint,truncated"));
        e.put("overInvLimitFull", new Expected("28a1e76f4931d525",
                "fd09df30afb45be0dcfd9a2c777d66b0aac37625359b7ff86067f077c3036537",
                false, true, "id,count,components,hash,fingerprint"));
        e.put("atFullLimit", new Expected("28a1e76f4931d525",
                "6c2d9e7642fafe18db3715f42e9e8c00bae2839cc5b53767fa28b01d6ebf64a0",
                false, true, "id,count,components,hash,fingerprint"));
        e.put("overFullLimit", new Expected("28a1e76f4931d525",
                "a68867083ce8840ff950722f1132fad4ec7e8ac53210fa8bd89a6567e7ea9864",
                true, false, "id,count,hash,fingerprint,truncated"));
        e.put("truncatedWithQuality", new Expected("0b95ba635cd0077e",
                "75e5dd2f4652d255307729489b5862acbd69b3b0e2ec0df9a6edf0011e023789",
                true, false, "id,count,max_damage,damage,hash,fingerprint,truncated"));
        return e;
    }

    @Test
    void everyCaseEncodesToTheRecordedBytes() {
        assertEquals(EXPECTED.keySet(), cases().keySet());
        cases().forEach((name, c) -> assertEquals(EXPECTED.get(name), describe(encode(c)), name));
    }

    @Test
    void theLimitIsInclusiveAndMeasuredOnTheNonCanonicalComponents() {
        // {"metadata":{"k":"x"*n}} is 16 + n bytes up to n = 65535
        assertEquals(INV, 16 + 65520);
        assertEquals(false, describe(encode(cases().get("atInvLimit"))).truncated());
        assertEquals(true, describe(encode(cases().get("overInvLimit"))).truncated());
        assertEquals(FULL, 18 + 1048558);
        assertEquals(false, describe(encode(cases().get("atFullLimit"))).truncated());
        assertEquals(true, describe(encode(cases().get("overFullLimit"))).truncated());
    }

    @Test
    void metadataIsConvertedToPlainJavaValuesAndNestingStopsAtDepthEight() {
        Map<String, Object> rec = encode(cases().get("rich"));
        @SuppressWarnings("unchecked")
        Map<String, Object> comps = (Map<String, Object>) rec.get("components");
        assertEquals(java.util.List.of("metadata", "quality"), new ArrayList<>(comps.keySet()));
        assertEquals(2L, comps.get("quality"));
        Map<?, ?> meta = (Map<?, ?>) comps.get("metadata");
        assertEquals(7L, meta.get("small"));
        assertEquals(5_000_000_000L, meta.get("big"));
        assertEquals(3.0, meta.get("whole"));
        assertEquals(1_760_000_000_000L, meta.get("when"));
        assertEquals("0123456789abcdef01234567", meta.get("oid"));
        assertEquals(true, meta.containsKey("nothing"));
        Object node = ((Map<?, ?>) ((Map<?, ?>) encode(cases().get("deep")).get("components")).get("metadata"));
        int levels = 0;
        while (node instanceof Map<?, ?> m && !m.isEmpty()) {
            node = m.values().iterator().next();
            levels++;
        }
        assertEquals(9, levels, "document, then 8 nested levels, the rest is null");
        assertEquals(null, node);
    }

    @Test
    void theSnapshotIsIndependentOfTheStackItWasTakenFrom() {
        BsonDocument meta = new BsonDocument("a", new BsonString("before"));
        ItemStack stack = stack("Plant_Fiber", 2, 0, 0, 1, meta);
        ItemCodec.Snapshot snap = ItemCodec.snapshot(stack);
        Map<String, Object> before = ItemCodec.encode(snap, INV);
        meta.put("a", new BsonString("after")); // the live document changes later
        assertEquals(before, ItemCodec.encode(snap, INV));
        assertEquals("before", ((Map<?, ?>) ((Map<?, ?>) before.get("components")).get("metadata")).get("a"));
        assertEquals(null, ItemCodec.snapshot(stack("Plant_Fiber", 1, 0, 0, 0, new BsonDocument())).metadata());
        assertEquals(null, ItemCodec.snapshot(stack("Plant_Fiber", 1, 0, 0, 0, null)).metadata());
    }

    @Test
    void encodingNeedsOnlyTheSnapshotFields() {
        ItemCodec.Snapshot snap = new ItemCodec.Snapshot("Weapon_Sword_Iron", 1, 50.0, 10.0, false,
                "Items/Weapons/Sword_Iron.blockymodel", 0, null);
        Map<String, Object> rec = ItemCodec.encode(snap, INV);
        Expected expected = EXPECTED.get("model");
        assertEquals(expected, describe(rec));
        assertEquals(40L, rec.get("damage"));
        assertEquals(50L, rec.get("max_damage"));
    }
}
