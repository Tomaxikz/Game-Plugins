package dev.playermanager.bridge.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Minecraft action catalogue of minecraft.md section 7: ids, labels, fields, ranges. Platforms register
 * a handler for each spec they implement (same specs everywhere keeps capabilities identical across loaders).
 */
public final class McActions {
    private McActions() {}

    private static final Map<String, ActionSpec> ALL = new LinkedHashMap<String, ActionSpec>();

    private static ActionSpec def(ActionSpec s) { ALL.put(s.id, s); return s; }

    private static Field reason() { return Field.text("reason", "Reason").maxLength(256); }
    private static Field container() { return Field.text("container", "Container").maxLength(32).required(); }
    private static Field slot() { return Field.slot("slot", "Slot").required(); }
    private static Field expId() { return Field.identifier("expected_id", "Expected item"); }
    private static Field expCount() { return Field.integer("expected_count", "Expected count", 0, 2147483647); }
    private static Field expFp() { return Field.text("expected_fingerprint", "Expected fingerprint").maxLength(64); }

    public static final ActionSpec KICK = def(ActionSpec.of("kick", true, true, reason()));
    public static final ActionSpec BAN = def(ActionSpec.of("ban", true, true, reason()).offline());
    public static final ActionSpec UNBAN = def(ActionSpec.of("unban", true, false).offline());
    public static final ActionSpec BAN_IP = def(ActionSpec.of("ban_ip", true, true, reason()).offline().ipTarget());
    public static final ActionSpec UNBAN_IP = def(ActionSpec.of("unban_ip", true, false).offline().ipTarget());
    public static final ActionSpec WHITELIST = def(ActionSpec.of("whitelist", true, false).offline());
    public static final ActionSpec UNWHITELIST = def(ActionSpec.of("unwhitelist", true, false).offline());
    public static final ActionSpec WHITELIST_ON = def(ActionSpec.of("whitelist_on", false, false));
    public static final ActionSpec WHITELIST_OFF = def(ActionSpec.of("whitelist_off", false, false));
    public static final ActionSpec OP = def(ActionSpec.of("op", true, false).offline());
    public static final ActionSpec DEOP = def(ActionSpec.of("deop", true, false).offline());
    public static final ActionSpec KILL = def(ActionSpec.of("kill", true, true));
    public static final ActionSpec GAMEMODE = def(ActionSpec.of("gamemode", true, false,
            Field.select("mode", "Mode", "survival", "creative", "adventure", "spectator").required()));
    public static final ActionSpec TELEPORT = def(ActionSpec.of("teleport", true, false,
            Field.number("x", "X", -30000000d, 30000000d), Field.number("y", "Y", -2048d, 2048d), Field.number("z", "Z", -30000000d, 30000000d),
            Field.target("to_player", "To player"), Field.text("world", "World").maxLength(128), Field.identifier("dimension", "Dimension"),
            Field.number("yaw", "Yaw", -360d, 360d), Field.number("pitch", "Pitch", -90d, 90d)));
    public static final ActionSpec WHISPER = def(ActionSpec.of("whisper", true, false, Field.text("message", "Message").maxLength(1000).required()));
    public static final ActionSpec GIVE_ITEM = def(ActionSpec.of("give_item", true, false,
            Field.identifier("item", "Item").required(), Field.integer("amount", "Amount", 1, 64000)));
    public static final ActionSpec GIVE_CUSTOM_ITEM = def(ActionSpec.of("give_custom_item", true, false, Field.item("item", "Item").required()));
    public static final ActionSpec CLEAR_INVENTORY = def(ActionSpec.of("clear_inventory", true, true));
    public static final ActionSpec REMOVE_ITEM = def(ActionSpec.of("remove_item", true, true, container(), slot(), expId(), expCount(), expFp()));
    public static final ActionSpec SET_ITEM_COUNT = def(ActionSpec.of("set_item_count", true, false, container(), slot(), expId(), expCount(), expFp(),
            Field.integer("amount", "Amount", 1, 64).required()));
    public static final ActionSpec MOVE_ITEM = def(ActionSpec.of("move_item", true, false, container(), slot(), expId(), expCount(), expFp(),
            Field.slot("destination_slot", "Destination slot").required()));
    public static final ActionSpec REMOVE_ITEM_LIVE = def(ActionSpec.of("remove_item_live", true, true, container(), slot(), expId(), expCount(), expFp()));
    public static final ActionSpec REPLACE_ITEM_LIVE = def(ActionSpec.of("replace_item_live", true, true, container(), slot(), expId(), expCount(), expFp(),
            Field.item("item", "Item").required(), Field.integer("amount", "Amount", 1, 64)));
    public static final ActionSpec SET_ITEM_LIVE = def(ActionSpec.of("set_item_live", true, true, container(), slot(), expId(), expCount(), expFp(),
            Field.item("item", "Item").required()));
    public static final ActionSpec MOVE_ITEM_LIVE = def(ActionSpec.of("move_item_live", true, false, container(), slot(), expId(), expCount(), expFp(),
            Field.text("destination_container", "Destination container").maxLength(32).required(), Field.slot("destination_slot", "Destination slot").required()));
    public static final ActionSpec SET_SCALE = def(ActionSpec.of("set_scale", true, false,
            Field.number("scale", "Scale", 0.0625, 16d).required(), Field.identifier("attribute", "Attribute")));
    public static final ActionSpec RESET_SCALE = def(ActionSpec.of("reset_scale", true, false, Field.identifier("attribute", "Attribute")));
    public static final ActionSpec HEAL = def(ActionSpec.of("heal", true, false));
    public static final ActionSpec SET_HEALTH = def(ActionSpec.of("set_health", true, false, Field.number("amount", "Health", 0d, 20d).required()));
    public static final ActionSpec SET_FOOD = def(ActionSpec.of("set_food", true, false, Field.integer("amount", "Food", 0, 20).required()));
    public static final ActionSpec SET_SATURATION = def(ActionSpec.of("set_saturation", true, false, Field.number("amount", "Saturation", 0d, 20d).required()));
    public static final ActionSpec SET_EXPERIENCE = def(ActionSpec.of("set_experience", true, false, Field.integer("amount", "Levels", 0, 21863).required()));
    public static final ActionSpec APPLY_EFFECT = def(ActionSpec.of("apply_effect", true, false,
            Field.identifier("effect", "Effect").required(), Field.integer("duration", "Duration (s)", 1, 1000000).required(),
            Field.integer("amplifier", "Amplifier", 0, 255)));
    public static final ActionSpec CLEAR_EFFECTS = def(ActionSpec.of("clear_effects", true, false));
    public static final ActionSpec WIPE_DATA = def(ActionSpec.of("wipe_data", true, true).offline());
    public static final ActionSpec TIME = def(ActionSpec.of("time", false, false,
            Field.integer("amount", "Ticks", 0, 2147483647).required(), Field.text("world", "World").maxLength(128)));
    public static final ActionSpec WEATHER = def(ActionSpec.of("weather", false, false, Field.select("value", "Weather", "clear", "rain", "thunder").required()));
    public static final ActionSpec DIFFICULTY = def(ActionSpec.of("difficulty", false, false,
            Field.select("value", "Difficulty", "peaceful", "easy", "normal", "hard").required()));
    public static final ActionSpec GAMERULE = def(ActionSpec.of("gamerule", false, false,
            Field.text("rule", "Rule").maxLength(64).required(), Field.text("value", "Value").maxLength(16).required()));
    public static final ActionSpec SAVE = def(ActionSpec.of("save", false, false));
    public static final ActionSpec RELOAD = def(ActionSpec.of("reload", false, true));
    public static final ActionSpec MESSAGE = def(ActionSpec.of("message", true, false, Field.text("message", "Message").maxLength(1000).required()));
    public static final ActionSpec TITLE = def(ActionSpec.of("title", true, false,
            Field.text("title", "Title").maxLength(256).required(), Field.text("subtitle", "Subtitle").maxLength(256),
            Field.integer("fade_in_ticks", "Fade in", 0, 72000), Field.integer("stay_ticks", "Stay", 0, 72000), Field.integer("fade_out_ticks", "Fade out", 0, 72000)));
    public static final ActionSpec ACTIONBAR = def(ActionSpec.of("actionbar", true, false, Field.text("message", "Message").maxLength(256).required()));
    public static final ActionSpec BROADCAST = def(ActionSpec.of("broadcast", false, false,
            Field.text("message", "Message").maxLength(1000).required(), Field.text("server", "Server").maxLength(64)));
    public static final ActionSpec SET_PERMISSION_GROUP = def(ActionSpec.of("set_permission_group", true, false, Field.identifier("group", "Group").required()));

    /** Looks up a catalogue spec by id (null if unknown). */
    public static ActionSpec byId(String id) { return ALL.get(id); }

    public static List<ActionSpec> all() { return new ArrayList<ActionSpec>(ALL.values()); }

    /** Ids of the actions that edit inventory slots and honour {@code expect} (feature {@code inventory.write}). */
    public static final List<String> ITEM_ACTIONS = Arrays.asList("remove_item", "set_item_count", "move_item", "remove_item_live",
            "replace_item_live", "set_item_live", "move_item_live");
}
