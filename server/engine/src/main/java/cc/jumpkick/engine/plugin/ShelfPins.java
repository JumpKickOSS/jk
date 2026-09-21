// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.model.BuildIdentity;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The shelf manifest this engine launches its first-party workers by ({@link ShelfManifest}).
 * The file beside the engine pointer is adopted while it names this engine's jar, and re-read
 * when an install rewrites it for the same engine; once another install has written it for
 * another engine, the manifest adopted last stays in force, so a displaced engine drains with the
 * workers it was installed with. An engine with no jar identity (a classes directory) pins nothing.
 */
public final class ShelfPins {

    private static final ShelfPins ENGINE = new ShelfPins(
            BuildIdentity::codeSha256, () -> EngineInstall.current().shelfFile());

    private final Supplier<String> engineSha256;
    private final Supplier<Path> file;

    private @Nullable ShelfManifest adopted;

    ShelfPins(Supplier<String> engineSha256, Supplier<Path> file) {
        this.engineSha256 = engineSha256;
        this.file = file;
    }

    /** The manifest the running engine is pinned to, or {@code null} when it has none. */
    public static @Nullable ShelfManifest current() {
        return ENGINE.manifest();
    }

    /** The checkout the running engine's shelf was installed from; {@code ""} when it has no manifest. */
    public static String source() {
        ShelfManifest m = current();
        return m == null ? "" : m.source();
    }

    /**
     * Re-read on every call: the file is a few kilobytes and a fork is rare, and a stat memo would
     * miss an install that rewrote it to the same size within one clock tick.
     */
    synchronized @Nullable ShelfManifest manifest() {
        String sha = engineSha256.get();
        if (sha == null || sha.isEmpty()) return null;
        ShelfManifest read = ShelfManifest.read(file.get()).orElse(null);
        if (read != null && read.pins(sha) && !read.equals(adopted)) adopted = read;
        return adopted;
    }
}
