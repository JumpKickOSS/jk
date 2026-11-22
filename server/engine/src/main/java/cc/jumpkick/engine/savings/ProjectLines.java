// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import cc.jumpkick.host.OutputDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The size of a project as the bench measures its repositories: the non-blank lines of its Java,
 * Kotlin and Groovy sources, main and test, outside build output and tool directories. Counted at
 * most once an hour per checkout, and only up to {@code cap}: past the largest repository the bench
 * measured, every size is credited alike.
 */
public final class ProjectLines {

    /** No size known: every call is credited the rate over every task. */
    public static final ProjectLines NONE = new ProjectLines(0, Clock.SYSTEM);

    private static final Set<String> SOURCES = Set.of(".java", ".kt", ".groovy");
    private static final Set<String> SKIP = Set.of(
            OutputDirs.TARGET,
            "build",
            ".git",
            ".gradle",
            "graphify-out",
            ".idea",
            "node_modules",
            ".kotlin",
            ".jk",
            ".repoql",
            "out",
            "bin");
    private static final long TTL_MILLIS = 3_600_000;

    private static volatile @Nullable ProjectLines counted;

    private record Counted(long at, long lines) {}

    private final long cap;
    private final Clock clock;
    private final Map<String, Counted> byDir = new ConcurrentHashMap<>();

    ProjectLines(long cap, Clock clock) {
        this.cap = cap;
        this.clock = clock;
    }

    /** The engine's counts, capped at the largest repository behind the shipped rates. */
    public static ProjectLines counted() {
        ProjectLines c = counted;
        if (c == null) {
            c = new ProjectLines(SavingsRates.shipped().largest(), Clock.SYSTEM);
            counted = c;
        }
        return c;
    }

    /** {@code dir}'s non-blank source lines, at most the cap; 0 for no directory, or one that cannot be read. */
    public long of(@Nullable String dir) {
        if (dir == null || dir.isBlank() || cap <= 0) return 0;
        long now = clock.millis();
        Counted c = byDir.get(dir);
        if (c == null || now - c.at() >= TTL_MILLIS) {
            c = new Counted(now, count(Path.of(dir), cap));
            byDir.put(dir, c);
        }
        return c.lines();
    }

    /** The non-blank source lines under {@code root}, stopping once they reach {@code cap}. */
    static long count(Path root, long cap) {
        long[] lines = {0};
        try {
            PathUtil.forEachEntry(root, d -> SKIP.contains(String.valueOf(d.getFileName())), (f, attrs) -> {
                if (attrs.isRegularFile() && source(f.getFileName().toString())) lines[0] += nonBlank(f);
                return lines[0] < cap;
            });
        } catch (IOException e) {
            // A tree that changed under the walk is counted as far as it was read.
        }
        return Math.min(lines[0], cap);
    }

    private static boolean source(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && SOURCES.contains(name.substring(dot));
    }

    /** The lines of {@code f} holding anything but whitespace; 0 when it cannot be read. */
    static long nonBlank(Path f) {
        long n = 0;
        boolean seen = false;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(f)) {
            for (int r; (r = in.read(buf)) > 0; ) {
                for (int i = 0; i < r; i++) {
                    byte b = buf[i];
                    if (b == '\n' || b == '\r') {
                        if (seen) n++;
                        seen = false;
                    } else if (b != ' ' && b != '\t' && b != '\f' && b != 0x0B) {
                        seen = true;
                    }
                }
            }
        } catch (IOException e) {
            return n;
        }
        return seen ? n + 1 : n;
    }
}
