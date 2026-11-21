// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A GraalVM with native-image for a test that links an image, or null where this host has none:
 * {@code $GRAALVM_HOME}, then a {@code graalvm*} install under {@code ~/.jdks} or jk's own JDK
 * directory. A test that needs one assumes it is there, so a host without GraalVM skips it.
 */
final class NativeTestGraal {

    private NativeTestGraal() {}

    static @Nullable Path home() {
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("GRAALVM_HOME");
        if (env != null && !env.isBlank()) candidates.add(Path.of(env));
        List<Path> dirs = new ArrayList<>();
        for (String home : new String[] {System.getenv("HOME"), System.getProperty("user.home")}) {
            if (home != null && !home.isBlank()) dirs.add(Path.of(home, ".jdks"));
        }
        dirs.add(JkDirs.jdks());
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> installs = Files.list(dir)) {
                installs.filter(p -> p.getFileName().toString().startsWith("graalvm"))
                        .sorted()
                        .forEach(candidates::add);
            } catch (IOException unreadable) {
                // Not a candidate.
            }
        }
        for (Path candidate : candidates) {
            String launcher = System.getProperty("os.name").startsWith("Windows") ? "native-image.cmd" : "native-image";
            if (Files.isExecutable(candidate.resolve("bin").resolve(launcher))) return candidate;
        }
        return null;
    }

    /** What {@code binary} prints, run with no arguments; it must exit 0. */
    static String run(Path binary) throws IOException {
        assertThat(binary).isRegularFile();
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(binary.toString()).redirectErrorStream(true), Duration.ofSeconds(60));
        assertThat(run.timedOut()).as(binary + " timed out").isFalse();
        assertThat(run.exit()).as(binary + " exit; printed " + run.output()).isZero();
        return run.output();
    }
}
