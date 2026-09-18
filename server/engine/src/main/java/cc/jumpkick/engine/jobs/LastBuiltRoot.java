// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * The directory the plan job admitted last ran in, process-wide: what the idle trim keeps warm,
 * since the next build on this machine is most likely that workspace's.
 */
public final class LastBuiltRoot {

    private static final AtomicReference<@Nullable Path> LAST = new AtomicReference<>();

    private LastBuiltRoot() {}

    /** Note {@code dir} as the last built; blank or unparseable is ignored. */
    public static void note(String dir) {
        if (dir.isBlank()) return;
        try {
            LAST.set(Path.of(dir));
        } catch (InvalidPathException ignored) {
            // a request whose dir is not a path of this host keeps the previous note
        }
    }

    /** The last noted directory, or null before any plan job ran. */
    public static @Nullable Path get() {
        return LAST.get();
    }
}
