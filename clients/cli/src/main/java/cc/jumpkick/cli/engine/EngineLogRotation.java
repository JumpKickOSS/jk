// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.time.Clock;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * The engine log at spawn time: two generations kept, and a log another spawner opened moments ago
 * appended to rather than rotated.
 *
 * <p>The spawner writes the first line of every fresh log — {@code jk engine: spawning <artifact>
 * (<how>) at <instant>} — after moving {@code <key>.log} to {@code .1} and {@code .1} to {@code .2},
 * so a dead engine's last lines survive the respawn that follows its death. Two clients that find
 * no engine within the same seconds both spawn one; the second engine yields to the first and
 * exits. Rotating for it would push the dead predecessor's log out and put the live engine's under
 * {@code .1}, so a spawn that finds a header younger than {@value #SPAWN_WINDOW_MS} milliseconds
 * appends to that log instead: the live engine, the yielding one and the spawner's header share
 * the file, each line whole.
 */
final class EngineLogRotation {

    /** A header younger than this names a spawn still in progress, not a dead engine. */
    static final long SPAWN_WINDOW_MS = 15_000;

    static final String HEADER = "jk engine: spawning ";
    private static final String AT = " at ";

    private EngineLogRotation() {}

    /** The first line of a spawn's log; the instant is the spawning client's clock. */
    static String header(String artifactPath, String how, Clock clock) {
        return HEADER + artifactPath + " (" + how + ")" + AT + Instant.ofEpochMilli(clock.millis());
    }

    /**
     * Rotate {@code log} unless its header was written inside the spawn window. {@code true} when
     * the spawn starts a fresh file (the log rotated or absent); {@code false} when it appends to a
     * spawn moments old. Best-effort: a rename that fails still lets the engine start.
     */
    static boolean rotate(Path log, Clock clock) {
        if (!Files.exists(log)) return true;
        Long spawnedAt = spawnedAt(log);
        if (spawnedAt != null && Math.abs(clock.millis() - spawnedAt) < SPAWN_WINDOW_MS) return false;
        Path one = generation(log, 1);
        try {
            if (Files.exists(one)) Files.move(one, generation(log, 2), StandardCopyOption.REPLACE_EXISTING);
            Files.move(log, one, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // the header's truncate starts the fresh log either way
        }
        return true;
    }

    /** {@code <key>.log.<n>}: the nth generation back. */
    static Path generation(Path log, int n) {
        return log.resolveSibling(log.getFileName() + "." + n);
    }

    /** Epoch millis of the spawn the log's first line records; {@code null} when it is not a header. */
    static @Nullable Long spawnedAt(Path log) {
        try (BufferedReader r = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null || !first.startsWith(HEADER)) return null;
            int at = first.lastIndexOf(AT);
            if (at < 0) return null;
            return Instant.parse(first.substring(at + AT.length()).trim()).toEpochMilli();
        } catch (IOException | DateTimeParseException unreadable) {
            return null;
        }
    }
}
