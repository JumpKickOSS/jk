// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkFingerprintTest {

    @Test
    void includes_ownership_marker_and_is_order_stable(@TempDir Path tmp) throws IOException {
        Path install = Files.createDirectories(tmp.resolve("temurin-25.0.4"));
        Files.writeString(install.resolve("release"), "JAVA_VERSION=\"25.0.4\"\n");
        JdkOwnership.mark(install);
        Files.createDirectories(install.resolve("bin"));
        Files.writeString(install.resolve("bin").resolve("java"), "#!/java\n");
        Files.writeString(install.resolve("bin").resolve("javac"), "#!/javac\n");

        String first = JdkFingerprint.compute(install);
        String second = JdkFingerprint.compute(install);
        assertThat(first).isEqualTo(second).hasSize(64);

        Files.writeString(install.resolve(JdkOwnership.MARKER), "tampered\n");
        assertThat(JdkFingerprint.compute(install)).isNotEqualTo(first);
    }

    @Test
    void does_not_follow_a_symlink_sibling(@TempDir Path tmp) throws IOException {
        assumeFalse(HostPlatform.isWindows());
        Path install = Files.createDirectories(tmp.resolve("temurin-25.0.4"));
        Files.writeString(install.resolve("a"), "a\n");
        JdkOwnership.mark(install);
        Path other = Files.createDirectories(tmp.resolve("other"));
        Files.writeString(other.resolve("secret"), "nope\n");
        Files.createSymbolicLink(install.resolve("link-out"), other);

        String digest = JdkFingerprint.compute(install);
        Files.writeString(other.resolve("secret"), "changed\n");
        assertThat(JdkFingerprint.compute(install)).isEqualTo(digest);
    }

    @Test
    void alias_dir_is_a_symlink_to_a_differently_named_install(@TempDir Path tmp) throws IOException {
        assumeFalse(HostPlatform.isWindows());
        Path real = Files.createDirectories(tmp.resolve("graalvm-25.0.4"));
        Path alias = tmp.resolve("graalvm-25");
        Files.createSymbolicLink(alias, real);
        assertThat(JdkFingerprint.isAliasDir(alias)).isTrue();
        assertThat(JdkFingerprint.isAliasDir(real)).isFalse();
    }

    @Test
    void empty_tree_still_fingerprints(@TempDir Path tmp) throws IOException {
        Path install = Files.createDirectories(tmp.resolve("jdk"));
        assertThat(JdkFingerprint.compute(install)).hasSize(64);
    }

    /**
     * The launcher accessors are the single owner of {@code <javaHome>/bin/java} (JK-2393). A
     * hand-built path drops the {@code .exe} and every fork built that way is dead on Windows, so
     * the suffix is pinned here rather than left to whichever host runs the suite.
     */
    @Test
    void launcher_paths_carry_the_windows_exe_suffix(@TempDir Path tmp) {
        String saved = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertThat(JdkFingerprint.java(tmp)).isEqualTo(tmp.resolve("bin").resolve("java.exe"));
            assertThat(JdkFingerprint.javac(tmp)).isEqualTo(tmp.resolve("bin").resolve("javac.exe"));
            System.setProperty("os.name", "Linux");
            assertThat(JdkFingerprint.java(tmp)).isEqualTo(tmp.resolve("bin").resolve("java"));
            assertThat(JdkFingerprint.javac(tmp)).isEqualTo(tmp.resolve("bin").resolve("javac"));
        } finally {
            System.setProperty("os.name", saved);
        }
    }
}
