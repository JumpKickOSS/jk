// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PATH tier's JDK home: the directory above the first {@code javac}'s {@code bin/}, when it is
 * a JDK. macOS's {@code /usr/bin/javac} is a launcher shim, a real executable with no JDK around
 * it: taken for a home, {@code /usr}, it sent the test forks of a build that found no JDK of its
 * own to {@code /usr/bin/java}, and CrossHostKeyEqualityTest's build never finished there.
 */
class ActiveJavacTest {

    @Test
    void a_launcher_shim_directory_is_no_jdk_home_and_the_next_entry_is(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(Os.isWindows(), "the shim layout is a POSIX one");
        Path shimBin = tmp.resolve("usr/bin");
        executable(shimBin.resolve("javac"));
        executable(shimBin.resolve("java"));
        Path jdk = tmp.resolve("jdk-25");
        executable(jdk.resolve("bin/javac"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"25.0.2\"\n");

        String path = shimBin + File.pathSeparator + jdk.resolve("bin");
        assertThat(ActiveJavac.home(Map.of("PATH", path)::get)).contains(jdk.toRealPath());
        assertThat(ActiveJavac.home(Map.of("PATH", shimBin.toString())::get)).isEmpty();
    }

    private static void executable(Path file) throws Exception {
        Files.createDirectories(Objects.requireNonNull(file.getParent()));
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
