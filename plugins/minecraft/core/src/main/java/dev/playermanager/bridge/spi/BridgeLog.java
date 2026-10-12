package dev.playermanager.bridge.spi;

/** Minimal logging facade implemented by each platform. */
public interface BridgeLog {
    void info(String msg);
    void warn(String msg);
    void error(String msg, Throwable t);
    void debug(String msg);

    BridgeLog STDERR = new BridgeLog() {
        public void info(String m) { System.err.println("[pm-bridge] " + m); }
        public void warn(String m) { System.err.println("[pm-bridge] WARN " + m); }
        public void error(String m, Throwable t) { System.err.println("[pm-bridge] ERROR " + m + (t == null ? "" : " " + t)); }
        public void debug(String m) { }
    };
}
