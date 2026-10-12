package dev.playermanager.bridge.assets;

import dev.playermanager.bridge.codec.Json;
import java.util.HashMap;
import java.util.Map;

/**
 * Parent models of the plain cube family, authored here as geometry facts (not copied files), so that
 * mod/plugin block items whose models extend them resolve even though the server has no vanilla client assets.
 */
final class BuiltinModels {
    private BuiltinModels() {}

    private static final Map<String, String> DEFS = new HashMap<String, String>();

    static {
        DEFS.put("block/block", "{\"display\":{"
                + "\"gui\":{\"rotation\":[30,225,0],\"translation\":[0,0,0],\"scale\":[0.625,0.625,0.625]},"
                + "\"ground\":{\"rotation\":[0,0,0],\"translation\":[0,3,0],\"scale\":[0.25,0.25,0.25]},"
                + "\"fixed\":{\"rotation\":[0,0,0],\"translation\":[0,0,0],\"scale\":[0.5,0.5,0.5]},"
                + "\"thirdperson_righthand\":{\"rotation\":[75,45,0],\"translation\":[0,2.5,0],\"scale\":[0.375,0.375,0.375]},"
                + "\"firstperson_righthand\":{\"rotation\":[0,45,0],\"scale\":[0.4,0.4,0.4]}}}");
        DEFS.put("block/cube", "{\"parent\":\"minecraft:block/block\",\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                + "\"down\":{\"texture\":\"#down\",\"cullface\":\"down\"},"
                + "\"up\":{\"texture\":\"#up\",\"cullface\":\"up\"},"
                + "\"north\":{\"texture\":\"#north\",\"cullface\":\"north\"},"
                + "\"south\":{\"texture\":\"#south\",\"cullface\":\"south\"},"
                + "\"west\":{\"texture\":\"#west\",\"cullface\":\"west\"},"
                + "\"east\":{\"texture\":\"#east\",\"cullface\":\"east\"}}}]}");
        DEFS.put("block/cube_all", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#all\",\"down\":\"#all\",\"up\":\"#all\","
                + "\"north\":\"#all\",\"east\":\"#all\",\"south\":\"#all\",\"west\":\"#all\"}}");
        DEFS.put("block/cube_column", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#side\",\"down\":\"#end\",\"up\":\"#end\","
                + "\"north\":\"#side\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
        DEFS.put("block/cube_bottom_top", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#side\",\"down\":\"#bottom\",\"up\":\"#top\","
                + "\"north\":\"#side\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
    }

    static Map<String, Object> get(String path) {
        String d = DEFS.get(path);
        return d == null ? null : Json.parseObject(d);
    }

    static boolean isGenerated(String path) {
        return path.equals("builtin/generated") || path.equals("item/generated") || path.equals("item/handheld")
                || path.equals("item/handheld_rod") || path.equals("item/handheld_mace");
    }
}
