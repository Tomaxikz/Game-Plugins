package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.util.Versions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/** Runtime detection of the server flavour and versions (never trusts a single signal). */
final class Env {
    final boolean folia;
    final boolean paper;
    final String platform;          // folia | paper | spigot  (values of bridge.json "platform")
    final String platformVersion;
    final String gameVersion;
    final int javaMajor;

    private Env(boolean folia, boolean paper, String platformVersion, String gameVersion) {
        this.folia = folia;
        this.paper = paper;
        this.platform = folia ? "folia" : paper ? "paper" : "spigot";
        this.platformVersion = platformVersion;
        this.gameVersion = gameVersion;
        this.javaMajor = Versions.javaMajor();
    }

    boolean atLeast(String mc) { return Versions.atLeast(gameVersion, mc); }

    static Env detect() {
        String name = String.valueOf(Bukkit.getName());
        String version = String.valueOf(Bukkit.getVersion());
        boolean folia = Reflect.hasClass("io.papermc.paper.threadedregions.RegionizedServer") || name.equalsIgnoreCase("Folia");
        boolean paper = folia
                || Reflect.hasClass("com.destroystokyo.paper.PaperConfig")
                || Reflect.hasClass("io.papermc.paper.configuration.Configuration")
                || Reflect.hasClass("io.papermc.paper.ServerBuildInfo")
                || name.toLowerCase().contains("paper") || version.toLowerCase().contains("paper");
        String mc = null;
        Object mv = Reflect.callStatic("org.bukkit.Bukkit", "getMinecraftVersion");
        if (mv instanceof String) mc = (String) mv;
        if (mc == null) {
            Matcher m = Pattern.compile("\\(MC: ([0-9][^)]*)\\)").matcher(version);
            if (m.find()) mc = m.group(1);
        }
        if (mc == null) {
            String bv = String.valueOf(Bukkit.getBukkitVersion());
            int dash = bv.indexOf('-');
            mc = dash > 0 ? bv.substring(0, dash) : bv;
        }
        String pv = version;
        Matcher b = Pattern.compile("git-(?:Paper|Folia|Purpur|Pufferfish|Leaves|Leaf)[^-]*-(\\d+)").matcher(version);
        if (b.find()) pv = mc + "-" + b.group(1);
        else {
            Matcher b2 = Pattern.compile("^(\\S+)").matcher(version);
            if (paper && b2.find() && Character.isDigit(b2.group(1).charAt(0))) pv = b2.group(1);
        }
        if (pv.length() > 96) pv = pv.substring(0, 96);
        return new Env(folia, paper, pv, mc);
    }
}
