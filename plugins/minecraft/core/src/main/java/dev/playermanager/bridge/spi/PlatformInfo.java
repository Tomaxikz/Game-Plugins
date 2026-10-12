package dev.playermanager.bridge.spi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static identity and capability declaration of a platform. Build with {@link Builder}. */
public final class PlatformInfo {
    public final String platform;          // paper, spigot, folia, velocity, bungeecord, fabric, neoforge, forge
    public final String platformVersion;
    public final String gameVersion;
    public final String game;              // minecraft_java
    public final Path serverRoot;
    public final String bridgeVersion;
    public final List<String> playerFields;
    public final List<String> events;
    public final List<String> features;     // extra features beyond the ones core derives
    public final List<String> integrations;
    public final Map<String, Object> helloGame;   // hello.game extras, may be null
    public final boolean proxy;
    /** Server name shown nowhere by core; informational. */
    public final int javaMajor;

    private PlatformInfo(Builder b) {
        platform = b.platform; platformVersion = b.platformVersion; gameVersion = b.gameVersion; game = b.game;
        serverRoot = b.serverRoot; bridgeVersion = b.bridgeVersion;
        playerFields = Collections.unmodifiableList(new ArrayList<String>(b.playerFields));
        events = Collections.unmodifiableList(new ArrayList<String>(b.events));
        features = Collections.unmodifiableList(new ArrayList<String>(b.features));
        integrations = Collections.unmodifiableList(new ArrayList<String>(b.integrations));
        helloGame = b.helloGame == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(b.helloGame));
        proxy = b.proxy; javaMajor = b.javaMajor;
    }

    public static Builder builder(String platform, Path serverRoot) { return new Builder(platform, serverRoot); }

    public static final class Builder {
        String platform, platformVersion = "unknown", gameVersion = "unknown", game = "minecraft_java", bridgeVersion = "0.0.0";
        Path serverRoot;
        List<String> playerFields = new ArrayList<String>(), events = new ArrayList<String>(), features = new ArrayList<String>(), integrations = new ArrayList<String>();
        Map<String, Object> helloGame;
        boolean proxy;
        int javaMajor = 8;

        Builder(String platform, Path serverRoot) { this.platform = platform; this.serverRoot = serverRoot; }

        public Builder platformVersion(String v) { platformVersion = v; return this; }
        public Builder gameVersion(String v) { gameVersion = v; return this; }
        public Builder game(String v) { game = v; return this; }
        public Builder bridgeVersion(String v) { bridgeVersion = v; return this; }
        public Builder playerFields(String... f) { playerFields = new ArrayList<String>(Arrays.asList(f)); return this; }
        public Builder playerFields(List<String> f) { playerFields = new ArrayList<String>(f); return this; }
        public Builder events(String... e) { events = new ArrayList<String>(Arrays.asList(e)); return this; }
        public Builder events(List<String> e) { events = new ArrayList<String>(e); return this; }
        public Builder features(String... f) { features = new ArrayList<String>(Arrays.asList(f)); return this; }
        public Builder integrations(List<String> i) { integrations = new ArrayList<String>(i); return this; }
        public Builder helloGame(Map<String, Object> g) { helloGame = g; return this; }
        public Builder proxy(boolean p) { proxy = p; return this; }
        public Builder javaMajor(int j) { javaMajor = j; return this; }
        public PlatformInfo build() { return new PlatformInfo(this); }
    }
}
