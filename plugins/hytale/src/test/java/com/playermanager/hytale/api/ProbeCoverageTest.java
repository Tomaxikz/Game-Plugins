package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard for "never call an unprobed member": scans the constant pool of every compiled class under
 * {@code com/playermanager/hytale/api} (so also lambdas, method references and super-constructor calls) and fails
 * when it references a field, method or constructor of a {@code com/hypixel/**} class that probes.txt does not list.
 * The member is named the way javac wrote it: owner = the static type of the receiver, so a member inherited from a
 * supertype is probed on the type the code uses.
 */
class ProbeCoverageTest {
    /**
     * Members the code references that probes.txt deliberately does not list. Every entry names why; an entry that
     * is no longer referenced fails the test, so the list cannot rot.
     */
    static final Map<String, String> ALLOWED = Map.of();

    /** java.lang.Object's methods resolve on every class: javac names the receiver type as the owner. */
    static final Set<String> OBJECT_METHODS = Set.of("equals(java.lang.Object)", "hashCode()", "getClass()",
            "toString()");

    static boolean isObjectMethod(String key) {
        return key.contains("#") && OBJECT_METHODS.contains(key.substring(key.indexOf('#') + 1));
    }

    static Path apiClasses() throws Exception {
        Path root = Path.of(HytaleProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return root.resolve("com/playermanager/hytale/api");
    }

    /** {@code (Lcom/x/Y;I[B)V} -> [com.x.Y, int, [B], the spelling Class.getName() gives. */
    static List<String> params(String descriptor) {
        List<String> out = new ArrayList<>();
        for (ClassDesc p : MethodTypeDesc.ofDescriptor(descriptor).parameterList()) {
            if (p.isPrimitive()) {
                out.add(p.displayName());
            } else if (p.isArray()) {
                out.add(p.descriptorString().replace('/', '.'));
            } else {
                out.add(p.descriptorString().substring(1, p.descriptorString().length() - 1).replace('/', '.'));
            }
        }
        return out;
    }

    /** Every com.hypixel member the compiled api classes reference: key -> the classes that reference it. */
    static Map<String, Set<String>> referenced() throws Exception {
        Map<String, Set<String>> out = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(apiClasses())) {
            files = walk.filter(f -> f.toString().endsWith(".class")).toList();
        }
        for (Path f : files) {
            ClassModel cm = ClassFile.of().parse(Files.readAllBytes(f));
            String user = cm.thisClass().asInternalName();
            for (PoolEntry e : cm.constantPool()) {
                if (!(e instanceof MemberRefEntry ref)) {
                    continue;
                }
                String owner = ref.owner().asInternalName();
                if (!owner.startsWith("com/hypixel/")) {
                    continue;
                }
                String name = ref.name().stringValue();
                String type = ref.type().stringValue();
                String dotted = owner.replace('/', '.');
                boolean method = type.startsWith("(");
                String key = method ? Probe.key(dotted, name, params(type)) : dotted + "#" + name;
                out.computeIfAbsent(key, k -> new TreeSet<>()).add(user.substring(user.lastIndexOf('/') + 1));
            }
        }
        return out;
    }

    @Test
    void everyReferencedGameMemberIsProbed() throws Exception {
        Map<String, Set<String>> refs = referenced();
        assertTrue(refs.size() > 150, "the scan should see the whole api package, saw " + refs.size());
        assertTrue(refs.containsKey("com.hypixel.hytale.server.core.universe.Universe#get()"));
        Set<String> probed = Probe.load(getClass().getClassLoader()).memberKeys();
        Map<String, Set<String>> missing = new TreeMap<>();
        refs.forEach((key, users) -> {
            if (!probed.contains(key) && !ALLOWED.containsKey(key) && !isObjectMethod(key)) {
                missing.put(key, users);
            }
        });
        assertEquals(Map.of(), missing, "add these to probes.txt (in the section of the capability that needs them)");
    }

    @Test
    void theAllowlistHasNoStaleEntries() throws Exception {
        Set<String> refs = referenced().keySet();
        for (String key : ALLOWED.keySet()) {
            assertTrue(refs.contains(key), "no longer referenced, remove from ALLOWED: " + key);
            assertTrue(!ALLOWED.get(key).isBlank());
        }
    }

    /** Probed members the compiled code does not reference (reflective or planned use), each with its reason. */
    static final Map<String, String> UNUSED_OK = Map.of(
            "com.hypixel.hytale.math.util.ChunkUtil#SIZE", "compile-time constant: javac inlines it, no reference remains",
            "com.hypixel.hytale.math.util.ChunkUtil#BITS", "compile-time constant: javac inlines it, no reference remains",
            "com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn#NO_HEIGHT",
            "compile-time constant: javac inlines it, no reference remains");

    @Test
    void probesAreNotKeptForMembersNobodyUsesAnyMore() throws Exception {
        Set<String> refs = referenced().keySet();
        Set<String> unused = new TreeSet<>();
        for (String key : Probe.load(getClass().getClassLoader()).memberKeys()) {
            if (!refs.contains(key) && !UNUSED_OK.containsKey(key)) {
                unused.add(key);
            }
        }
        assertEquals(Set.of(), unused, "remove from probes.txt, or list in UNUSED_OK with the reason");
    }
}
