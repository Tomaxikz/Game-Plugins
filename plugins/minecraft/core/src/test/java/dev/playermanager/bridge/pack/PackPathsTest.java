package dev.playermanager.bridge.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The path rule of operations.md section 10a. */
class PackPathsTest {
    @Test void acceptedPaths() {
        String[] ok = {
            "assets/examplemod/blockstates/ruby_block.json",
            "assets/examplemod/models/block/ruby_block.json",
            "assets/examplemod/models/item/ruby.json",
            "assets/examplemod/textures/item/ruby.png",
            "assets/examplemod/textures/block/anim.png.mcmeta",
            "assets/minecraft/textures/block/stone.png",
            "assets/my-mod.v2_x/textures/a/b/c/d.png",
            "assets/a/models/x.json",
            "assets/a/textures/.hidden.png",
            "assets/a/textures/a...b.png",
            "assets/a/models/...json",
            "assets/9/blockstates/9.json",
        };
        for (String p : ok) assertTrue(PackPaths.valid(p), p);
    }

    @Test void rejectedPaths() {
        String[] bad = {
            "", "assets", "assets/", "assets/ns", "assets/ns/", "assets/ns/models", "assets/ns/models/", "assets/ns/models/.json/",
            "assets//models/a.json", "assets/ns/models//a.json", "assets/ns/models/a//b.json", "assets/ns/models/a/.json/",
            "/assets/ns/models/a.json", "assets/ns/models/a.json/", "Assets/ns/models/a.json", "xassets/ns/models/a.json",
            // kinds
            "assets/ns/items/a.json", "assets/ns/sounds/a.ogg", "assets/ns/lang/en_us.json", "assets/ns/font/a.json", "assets/ns/shaders/a.json",
            "assets/ns/atlases/blocks.json", "assets/ns/model/a.json", "assets/ns/Models/a.json", "assets/ns/models.json", "assets/ns/textures.png",
            // extensions
            "assets/ns/models/a.png", "assets/ns/blockstates/a.png", "assets/ns/textures/a.json", "assets/ns/models/a", "assets/ns/models/a.JSON",
            "assets/ns/textures/a.PNG", "assets/ns/textures/a.png.mcmeta.bak", "assets/ns/textures/a.mcmeta", "assets/ns/models/a.json.mcmeta",
            "assets/ns/textures/a.jpg", "assets/ns/textures/a.png ",
            // characters
            "assets/NS/models/a.json", "assets/ns/models/A.json", "assets/ns/models/a b.json", "assets/ns/models/a+b.json", "assets/ns/models/a:b.json",
            "assets/ns/models/ä.json", "assets/ns/models/a\\b.json", "assets/ns/models/a\n.json", "assets/ns/models/a%2e.json", "assets/n:s/models/a.json",
            // traversal
            "assets/../models/a.json", "assets/./models/a.json", "assets/ns/models/../a.json", "assets/ns/models/./a.json", "assets/ns/models/a/../b.json",
            "assets/ns/models/a/./b.json", "assets/../../etc/passwd", "assets/ns/models/..", "assets/ns/models/.",
        };
        for (String p : bad) assertFalse(PackPaths.valid(p), p.replace("\n", "\\n"));
    }

    @Test void lengthIsCappedAtFiveHundredTwelveBytes() {
        String head = "assets/ns/models/";
        StringBuilder sb = new StringBuilder(head);
        while (sb.length() < 512 - ".json".length()) sb.append('a');
        String exact = sb + ".json";
        assertEquals(512, exact.length());
        assertTrue(PackPaths.valid(exact));
        assertFalse(PackPaths.valid(sb.append('a') + ".json"));
    }

    @Test void foldersThatCanHoldPaths() {
        assertTrue(PackPaths.mayContain("assets"));
        assertTrue(PackPaths.mayContain("assets/ns"));
        assertTrue(PackPaths.mayContain("assets/ns/models"));
        assertTrue(PackPaths.mayContain("assets/ns/blockstates"));
        assertTrue(PackPaths.mayContain("assets/ns/textures/block/deep"));
        assertFalse(PackPaths.mayContain("assets/ns/sounds"));
        assertFalse(PackPaths.mayContain("assets/ns/lang"));
        assertFalse(PackPaths.mayContain("assets/ns/items"));
        assertFalse(PackPaths.mayContain("assets/NS"));
        assertFalse(PackPaths.mayContain("assets/.."));
        assertFalse(PackPaths.mayContain("assets/../models"));
        assertFalse(PackPaths.mayContain("data"));
        assertFalse(PackPaths.mayContain("data/ns"));
    }
}
