package dev.playermanager.bridge.spi;

import dev.playermanager.bridge.assets.PackSource;
import java.util.List;

/** Supplies the resource-pack roots (folders/zips/jars) that already exist on this server. Re-evaluated on demand. */
public interface PackSourceProvider {
    List<PackSource> packs();
}
