// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Where jk's own processes keep temporary files: the engine, the workers it forks (compilers,
 * plugin hosts, batch tools) and the client.
 *
 * <p>On Linux that is {@code /var/tmp}. Many distributions mount {@code /tmp} as tmpfs, so every
 * byte written there is RAM, and the JVM's {@code java.io.tmpdir} is {@code /tmp} whatever {@code
 * TMPDIR} says. A build writes compiler argfiles, calibration probes, plugin specs and AOT training
 * scratch by the thousand; in memory they compete with the build itself. {@code /var/tmp} is the
 * FHS place for temporary files on disk. Elsewhere the JVM's own default stands: macOS's is a
 * per-user folder on disk, and Windows' is the user's temp folder.
 *
 * <p>Only jk's own JVMs take it ({@link #jvmFlag()}). A test JVM keeps the per-run directory jk
 * already gives it, and a tool a build runs is not told where to put its files.
 */
@NullMarked
public final class HostTemp {

    /** The on-disk temporary directory on Linux. */
    public static final String LINUX = "/var/tmp";

    /** The system property the JVM reads its temporary directory from. */
    public static final String PROPERTY = "java.io.tmpdir";

    private HostTemp() {}

    /** The directory jk's processes put temporary files in. */
    public static Path dir() {
        String dir = override(Os.isLinux(), Files::isDirectory, Files::isWritable);
        return Path.of(dir != null ? dir : System.getProperty(PROPERTY, LINUX));
    }

    /**
     * {@code -Djava.io.tmpdir=/var/tmp} for a JVM jk launches on Linux, so its temporary files land
     * on disk; {@code null} where the JVM's default is already right, or when {@code /var/tmp} is
     * missing or not writable (then the default is the only place there is).
     */
    public static @Nullable String jvmFlag() {
        String dir = override(Os.isLinux(), Files::isDirectory, Files::isWritable);
        return dir == null ? null : "-D" + PROPERTY + "=" + dir;
    }

    /** Whether {@code args} already name the JVM's temporary directory: theirs wins. */
    public static boolean named(Iterable<String> args) {
        for (String a : args) {
            if (a != null && (a.startsWith("-D" + PROPERTY + "=") || a.startsWith("-J-D" + PROPERTY + "="))) {
                return true;
            }
        }
        return false;
    }

    /** The directory to use in place of the JVM default, or {@code null} to keep the default. */
    static @Nullable String override(boolean linux, Predicate<Path> isDirectory, Predicate<Path> writable) {
        if (!linux) return null;
        Path dir = Path.of(LINUX);
        return isDirectory.test(dir) && writable.test(dir) ? LINUX : null;
    }
}
