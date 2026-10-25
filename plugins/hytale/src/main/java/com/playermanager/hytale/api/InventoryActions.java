package com.playermanager.hytale.api;

import static com.playermanager.hytale.api.GameAction.ok;
import static com.playermanager.hytale.api.GameAction.refused;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.playermanager.hytale.game.BridgeError;
import java.util.List;
import java.util.Map;

/** give_item and clear_inventory: item moves inside the player's containers, in the player's world task. */
final class InventoryActions {
    private InventoryActions() {
    }

    static final GameAction GIVE_ITEM = new GameAction("give_item", Verified.GIVE_ITEM,
            () -> Specs.action("give_item", true, false, false,
                    Specs.field("item", "Item", "item", null, null, null),
                    Specs.field("amount", "Amount", "integer", null, 1, 10_000),
                    Specs.select("container", "Container", Containers.GIVE)),
            InventoryActions::give);

    static final GameAction CLEAR_INVENTORY = new GameAction("clear_inventory", Verified.CLEAR_INVENTORY,
            () -> Specs.action("clear_inventory", true, true, false,
                    Specs.select("container", "Container", Containers.IDS)),
            InventoryActions::clear);

    /** The optional {@code container} arg, or null; {@code invalid_args} when it is not one of {@code allowed}. */
    private static String container(GameAction.Call c, List<String> allowed, String invalidPrefix) {
        String cid = c.optText("container");
        if (cid != null && !allowed.contains(cid)) {
            throw BridgeError.invalid(invalidPrefix + cid);
        }
        return cid;
    }

    private static Map<String, Object> give(GameAction.Call c) {
        if (!(c.args().get("item") instanceof String itemId) || !(c.args().get("amount") instanceof Long amt)) {
            throw BridgeError.invalid("item and amount are required");
        }
        if (Item.getAssetMap().getAsset(itemId) == null) {
            throw BridgeError.notFound("unknown item: " + itemId);
        }
        String cid = container(c, Containers.GIVE, "give_item cannot target container ");
        int amount = (int) (long) amt;
        return c.onPlayer((st, ref, loc) -> {
            ItemStack stack = new ItemStack(itemId, amount);
            ItemStackTransaction tx = cid != null
                    ? Containers.present(st, ref, cid).addItemStack(stack)
                    : Player.giveItem(stack, ref, st);
            ItemStack rest = tx.getRemainder();
            int left = rest == null || rest.isEmpty() ? 0 : rest.getQuantity();
            Map<String, Object> res = Map.of("given", (long) (amount - left));
            return left == 0 ? ok(res) : refused("Inventory full: gave " + (amount - left) + " of " + amount);
        });
    }

    private static Map<String, Object> clear(GameAction.Call c) {
        String cid = container(c, Containers.IDS, "unknown container: ");
        return c.onPlayer((st, ref, loc) -> {
            long cleared = 0;
            for (String id : cid != null ? List.of(cid) : Containers.IDS) {
                ItemContainer cont = cid != null ? Containers.present(st, ref, id) : Containers.lookup(st, ref, id);
                if (cont == null) {
                    continue; // this player has no such container (only possible without an explicit one)
                }
                for (int i = 0; i < cont.getCapacity(); i++) {
                    ItemStack s = cont.getItemStack((short) i);
                    if (s != null && !s.isEmpty()) {
                        cleared += s.getQuantity();
                    }
                }
                cont.clear();
            }
            return ok(Map.of("cleared", cleared));
        });
    }
}
