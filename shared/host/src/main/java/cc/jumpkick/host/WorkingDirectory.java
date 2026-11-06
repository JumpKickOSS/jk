// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The directory a command's relative paths resolve against: the process working directory, unless
 * a host that runs commands in-process has {@linkplain #pin pinned} another.
 *
 * <p>Client code reads its working directory here and never through {@code Path.of("")}, {@code
 * user.dir} or a bare {@link Path#toAbsolutePath()}, and turns every relative path it was handed
 * into an absolute one with {@link #resolve} before it touches the file system. The JVM resolves a
 * relative path against the directory it started in, which no Java code can change, so a pin only
 * holds where every read goes through this class.
 *
 * <p>A test suite pins a temporary directory, so a command it runs with a relative target writes
 * there rather than into the source tree the test JVM started in. A real {@code jk} never pins.
 */
public final class WorkingDirectory {

    private static volatile @Nullable Path pinned;

    private WorkingDirectory() {}

    /** The working directory, absolute and normalized. */
    public static Path current() {
        Path p = pinned;
        return p != null ? p : Path.of("").toAbsolutePath().normalize();
    }

    /** {@code path} made absolute against {@link #current()} when relative, then normalized. */
    public static Path resolve(Path path) {
        return (path.isAbsolute() ? path : current().resolve(path)).normalize();
    }

    /** {@link #resolve(Path)} of {@code Path.of(path)}. */
    public static Path resolve(String path) {
        return resolve(Path.of(path));
    }

    /**
     * Make {@code dir} the working directory of every command this process runs from now on, or
     * restore the process working directory with {@code null}. For in-process hosts only.
     *
     * @return the pin this one replaced, or {@code null} when there was none
     */
    public static @Nullable Path pin(@Nullable Path dir) {
        Path previous = pinned;
        pinned = dir == null ? null : dir.toAbsolutePath().normalize();
        return previous;
    }
}
