package com.playermanager.hytale;

/** Version strings: BRIDGE_VERSION env wins, else the jar manifest (written by Gradle from /VERSION). */
public final class BridgeVersion {
    private BridgeVersion() {
    }

    public static String bridgeVersion() {
        String env = System.getenv("BRIDGE_VERSION");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String v = BridgeVersion.class.getPackage().getImplementationVersion();
        return v == null ? "0.0.0-dev" : v;
    }

    /** Pinned Hytale server build this jar was compiled against (manifest attribute Hytale-Build). */
    public static String hytaleBuild() {
        String v = BridgeVersion.class.getPackage().getSpecificationVersion();
        return v == null ? "unknown" : v;
    }
}
