// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.testing.Symlinks;
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
        Path install = Files.createDirectories(tmp.resolve("temurin-25.0.4"));
        Files.writeString(install.resolve("a"), "a\n");
        JdkOwnership.mark(install);
        Path other = Files.createDirectories(tmp.resolve("other"));
        Files.writeString(other.resolve("secret"), "nope\n");
        Symlinks.create(install.resolve("link-out"), other);

        String digest = JdkFingerprint.compute(install);
        Files.writeString(other.resolve("secret"), "changed\n");
        assertThat(JdkFingerprint.compute(install)).isEqualTo(digest);
    }

    @Test
    void alias_dir_is_a_symlink_to_a_differently_named_install(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectories(tmp.resolve("graalvm-25.0.4"));
        Path alias = tmp.resolve("graalvm-25");
        Symlinks.create(alias, real);
        assertThat(JdkFingerprint.isAliasDir(alias)).isTrue();
        assertThat(JdkFingerprint.isAliasDir(real)).isFalse();
    }

    /**
     * A symlinked tool home fingerprints the tree it points at — what
     * {@code jk doctor --verify-linked} needs when jk discovers a host install. The digest equals
     * the target's and moves when the target does.
     */
    @Test
    void a_symlinked_root_fingerprints_the_tree_it_points_at(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectories(tmp.resolve("temurin-25.0.4"));
        Files.createDirectories(real.resolve("bin"));
        Files.writeString(JdkFingerprint.java(real), "#!/java\n");
        Files.writeString(real.resolve("release"), "JAVA_VERSION=\"25.0.4\"\n");
        Path link = tmp.resolve("linked");
        Symlinks.create(link, real);

        String viaLink = JdkFingerprint.compute(link);
        assertThat(viaLink).isEqualTo(JdkFingerprint.compute(real)).isNotEqualTo(JdkFingerprint.EMPTY_TREE);

        Files.writeString(real.resolve("release"), "JAVA_VERSION=\"25.0.5\"\n");
        assertThat(JdkFingerprint.compute(link)).isNotEqualTo(viaLink);
    }

    /**
     * An empty tree hashes to {@link JdkFingerprint#EMPTY_TREE}, asserted by value — not merely by
     * length.
     */
    @Test
    void an_empty_tree_hashes_to_the_named_empty_digest(@TempDir Path tmp) throws IOException {
        Path install = Files.createDirectories(tmp.resolve("jdk"));
        assertThat(JdkFingerprint.compute(install)).isEqualTo(JdkFingerprint.EMPTY_TREE);
        // …and the constant is what it claims to be, so naming it is not a second place to be wrong.
        assertThat(JdkFingerprint.EMPTY_TREE).isEqualTo(Hashing.sha256Hex(new byte[0]));
    }

    /**
     * Launcher accessors own {@code <javaHome>/bin/java} (with {@code .exe} on Windows).
     * {@code tool} applies the same rule for a launcher named at runtime.
     */
    @Test
    void launcher_paths_carry_the_windows_exe_suffix(@TempDir Path tmp) {
        String saved = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertThat(JdkFingerprint.toolName("java")).isEqualTo("java.exe");
            assertThat(JdkFingerprint.java(tmp)).isEqualTo(tmp.resolve("bin").resolve("java.exe"));
            assertThat(JdkFingerprint.javac(tmp)).isEqualTo(tmp.resolve("bin").resolve("javac.exe"));
            assertThat(JdkFingerprint.tool(tmp, "jshell"))
                    .isEqualTo(tmp.resolve("bin").resolve("jshell.exe"));
            System.setProperty("os.name", "Linux");
            assertThat(JdkFingerprint.toolName("java")).isEqualTo("java");
            assertThat(JdkFingerprint.java(tmp)).isEqualTo(tmp.resolve("bin").resolve("java"));
            assertThat(JdkFingerprint.javac(tmp)).isEqualTo(tmp.resolve("bin").resolve("javac"));
            assertThat(JdkFingerprint.tool(tmp, "jshell"))
                    .isEqualTo(tmp.resolve("bin").resolve("jshell"));
        } finally {
            System.setProperty("os.name", saved);
        }
    }
}
