// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * JDK home for the first {@code javac} on {@code PATH} (independent of {@code JAVA_HOME} / jk
 * default). Resolves through symlinks; home is parent of {@code bin/}.
 *
 * <p><b>Limitation:</b> version managers that put a <em>shim script</em> on {@code PATH} rather
 * than a symlink to the real binary (jenv, asdf) resolve to the shim's own directory, not a JDK
 * home, and so won't be matched. Direct {@code PATH} entries and symlink-based managers (SDKMAN,
 * jk, Homebrew) work.
 */
public final class ActiveJavac {

    private ActiveJavac() {}

    private static final boolean WINDOWS = Os.isWindows();

    /** Resolve the current JDK home from the process {@code PATH}. */
    public static Optional<Path> home() {
        return home(System::getenv);
    }

    /**
     * Test-friendly variant: resolves {@code javac} from the {@code PATH} value returned by {@code
     * env}. Returns the canonical JDK home, or empty when no {@code javac} is found on the path (or
     * none resolves to a {@code bin/} under a home directory).
     */
    static Optional<Path> home(Function<String, @Nullable String> env) {
        String path = env.apply("PATH");
        if (path == null || path.isBlank()) return Optional.empty();
        String exe = JdkFingerprint.toolName("javac");
        for (String dir : SearchPath.entries(path)) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir).resolve(exe);
            if (!Files.isRegularFile(candidate)) continue;
            // PathUtil.isRunnable already answers this per platform: an access check off Windows,
            // an extension test on it, instead of the 64x security-descriptor read.
            if (!PathUtil.isRunnable(candidate)) continue;
            try {
                Path real = candidate.toRealPath(); // follow symlinks (SDKMAN et al.)
                Path bin = real.getParent(); // <home>/bin
                Path jdkHome = bin != null ? bin.getParent() : null;
                if (jdkHome != null) return Optional.of(jdkHome);
            } catch (IOException ignored) {
                // Unreadable/broken symlink — keep walking the PATH.
            }
        }
        return Optional.empty();
    }
}
