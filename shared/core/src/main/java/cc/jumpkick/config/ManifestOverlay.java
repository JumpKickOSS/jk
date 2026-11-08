// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Manifest text read in place of the files on disk while a {@link #reading} call runs on its thread:
 * how {@code jk update} resolves pin moves nobody has accepted without writing them. {@link
 * JkBuildParser} and the rules-file reader consult it; while one is bound, {@link RequestScope}
 * remembers nothing, so no fact derived from the overlay outlives it and none from disk leaks in.
 */
public final class ManifestOverlay {

    private static final ScopedValue<Map<Path, String>> TEXTS = ScopedValue.newInstance();

    private ManifestOverlay() {}

    /** A body run with an overlay bound. */
    @FunctionalInterface
    public interface Body<T> {
        T run() throws Exception;
    }

    /** {@code body}'s result, every file in {@code texts} (path to its text) read as that text. */
    public static <T> T reading(Map<Path, String> texts, Body<T> body) throws Exception {
        Map<Path, String> keyed = new LinkedHashMap<>();
        texts.forEach((path, text) -> keyed.put(key(path), text));
        return ScopedValue.where(TEXTS, Map.copyOf(keyed)).call(body::run);
    }

    /** The text the bound overlay holds for {@code file}, or {@code null} to read the file. */
    public static @Nullable String text(Path file) {
        return TEXTS.isBound() ? TEXTS.get().get(key(file)) : null;
    }

    /** True while a {@link #reading} call runs on this thread. */
    public static boolean active() {
        return TEXTS.isBound();
    }

    private static Path key(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
