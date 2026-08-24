// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
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
        assumeFalse(Os.isWindows());
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
        assumeFalse(Os.isWindows());
        Path real = Files.createDirectories(tmp.resolve("graalvm-25.0.4"));
        Path alias = tmp.resolve("graalvm-25");
        Files.createSymbolicLink(alias, real);
        assertThat(JdkFingerprint.isAliasDir(alias)).isTrue();
        assertThat(JdkFingerprint.isAliasDir(real)).isFalse();
    }

    /**
     * A symlinked tool home fingerprints the tree it points at — which is the entire content of
     * {@code jk doctor --verify-linked}, because jk symlinks a tool home whenever it discovers a
     * host install instead of downloading one (JK-2467).
     *
     * <p>Before the fix, {@code Files.walkFileTree} without {@code FOLLOW_LINKS} handed the
     * symlinked root to {@code visitFile}, which rejected it, so the manifest was empty and every
     * linked tool on every run reported {@link JdkFingerprint#EMPTY_TREE}. Both halves matter here:
     * the digest must equal the target's, and it must move when the target does.
     */
    @Test
    void a_symlinked_root_fingerprints_the_tree_it_points_at(@TempDir Path tmp) throws IOException {
        assumeFalse(Os.isWindows());
        Path real = Files.createDirectories(tmp.resolve("temurin-25.0.4"));
        Files.createDirectories(real.resolve("bin"));
        Files.writeString(JdkFingerprint.java(real), "#!/java\n");
        Files.writeString(real.resolve("release"), "JAVA_VERSION=\"25.0.4\"\n");
        Path link = tmp.resolve("linked");
        Files.createSymbolicLink(link, real);

        String viaLink = JdkFingerprint.compute(link);
        assertThat(viaLink).isEqualTo(JdkFingerprint.compute(real)).isNotEqualTo(JdkFingerprint.EMPTY_TREE);

        Files.writeString(real.resolve("release"), "JAVA_VERSION=\"25.0.5\"\n");
        assertThat(JdkFingerprint.compute(link)).isNotEqualTo(viaLink);
    }

    /**
     * An empty tree hashes to a digest with a name, asserted by value.
     *
     * <p>This test used to assert {@code .hasSize(64)} and nothing else, which is how JK-2467
     * shipped: {@code e3b0c442…} is 64 characters, so the test passed for the one value the broken
     * {@code compute} could produce. A length assertion on a digest is not a test of a digest.
     */
    @Test
    void an_empty_tree_hashes_to_the_named_empty_digest(@TempDir Path tmp) throws IOException {
        Path install = Files.createDirectories(tmp.resolve("jdk"));
        assertThat(JdkFingerprint.compute(install)).isEqualTo(JdkFingerprint.EMPTY_TREE);
        // …and the constant is what it claims to be, so naming it is not a second place to be wrong.
        assertThat(JdkFingerprint.EMPTY_TREE).isEqualTo(Hashing.sha256Hex(new byte[0]));
    }

    /**
     * The launcher accessors are the single owner of {@code <javaHome>/bin/java} (JK-2393,
     * JK-2457). A hand-built path drops the {@code .exe} and every fork built that way is dead on
     * Windows, so the suffix is pinned here rather than left to whichever host runs the suite.
     *
     * <p>The shape is asserted at the owner, not at a call site: guard G1 is what proves the 22
     * former hand-rolled sites go through here, and four of them — the android bundletool fork, the
     * image-builder jarmode fork, the engine's AOT trainer sidecar and its `[build] logic` fork —
     * were the ones missing the suffix. {@code tool} carries the same rule for a launcher named at
     * runtime, which is what a plugin worker does.
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
