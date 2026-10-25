package com.playermanager.hytale.game;

/**
 * Receives statistic increments from game listeners and systems (called on world threads; implementations
 * must be cheap, non-blocking and allocation-free on the hot path).
 */
public interface StatsSink {

    /** Item/block/NPC keyed categories of the stats file (games/hytale.md §17). */
    enum Cat {
        BROKEN("broken"), PLACED("placed"), CRAFTED("crafted"), USED("used"), PICKED_UP("picked_up"),
        DROPPED("dropped"), KILLED("killed"), KILLED_BY("killed_by");

        public final String key;

        Cat(String key) {
            this.key = key;
        }
    }

    /** Keys of the {@code custom} category; {@code integer} ones are stored as whole numbers, the rest as decimals. */
    enum Custom {
        PLAY_TIME_SECONDS("play_time_seconds", true), SESSIONS("sessions", true), DEATHS("deaths", true),
        PLAYER_KILLS("player_kills", true), NPC_KILLS("npc_kills", true), DAMAGE_DEALT("damage_dealt", false),
        DAMAGE_TAKEN("damage_taken", false), JUMPS("jumps", true), DISTANCE_WALK("distance_walk_blocks", false),
        DISTANCE_SPRINT("distance_sprint_blocks", false), DISTANCE_SWIM("distance_swim_blocks", false),
        DISTANCE_GLIDE("distance_glide_blocks", false), DISTANCE_MOUNT("distance_mount_blocks", false),
        DISTANCE_FALLEN("distance_fallen_blocks", false);

        public final String key;
        public final boolean integer;

        Custom(String key, boolean integer) {
            this.key = key;
            this.integer = integer;
        }
    }

    StatsSink NONE = new StatsSink() {
        @Override
        public void session(String id, String name, boolean start) {
        }

        @Override
        public void count(String id, String name, Cat cat, String key, long n) {
        }

        @Override
        public void add(String id, String name, Custom key, double amount) {
        }

        @Override
        public void gauges(String id, String name, int zonesDiscovered, int objectivesCompleted) {
        }
    };

    /** Session start (join) or end (leave). */
    void session(String id, String name, boolean start);

    void count(String id, String name, Cat cat, String key, long n);

    void add(String id, String name, Custom key, double amount);

    /**
     * All movement deltas of one sample (non-positive or non-finite values are ignored), so an implementation can
     * apply them under one lock. The default adds them one by one.
     */
    default void addMovement(String id, String name, double walk, double sprint, double swim, double glide,
                             double mount, double fallen, double jumps) {
        add(id, name, Custom.DISTANCE_WALK, walk);
        add(id, name, Custom.DISTANCE_SPRINT, sprint);
        add(id, name, Custom.DISTANCE_SWIM, swim);
        add(id, name, Custom.DISTANCE_GLIDE, glide);
        add(id, name, Custom.DISTANCE_MOUNT, mount);
        add(id, name, Custom.DISTANCE_FALLEN, fallen);
        add(id, name, Custom.JUMPS, jumps);
    }

    /** Current totals read from the player's own data; -1 = unknown (keeps the stored value). */
    void gauges(String id, String name, int zonesDiscovered, int objectivesCompleted);
}
