// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StableJdkPointerTest {

    private static Path fakeJdk(Path root, String name, String version) throws IOException {
        Path home = root.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        return home;
    }

    @Test
    void a_jdk_jk_does_not_own_is_never_deleted_to_free_the_pointer_name(@TempDir Path tmp) throws IOException {
        // JK-2624. The pointer name is <vendor>-<major> and the jdks root is SHARED — it is
        // IntelliJ's `~/.jdks`, not a jk-private directory. So `graalvm-25` is both a name jk wants
        // and, on a real machine, very often a JDK the user or the IDE installed there first.
        // Claiming the name by deleting what is already there destroyed real GraalVM and Temurin
        // installs on the developer's machine.
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path theirs = fakeJdk(jdks, "graalvm-25", "25"); // somebody else's install, no .jk-owned
        Path ours = fakeJdk(jdks, "graalvm-25.0.4", "25.0.4");
        JdkOwnership.mark(ours);

        assertThatIOException()
                .isThrownBy(() -> new StableJdkPointer(jdks).ensure("graalvm-25", ours))
                .withMessageContaining("not installed by jk");

        assertThat(theirs.resolve("release")).as("their JDK is untouched").exists();
        assertThat(JdkFingerprint.java(theirs)).exists();
    }

    @Test
    void a_superseded_jk_install_at_the_pointer_name_is_still_replaced(@TempDir Path tmp) throws IOException {
        // The flip side: when the directory in the way IS jk's own — a repoint that left a real
        // directory behind — reclaiming the name is correct and must keep working.
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path stale = fakeJdk(jdks, "temurin-25", "25.0.3");
        JdkOwnership.mark(stale);
        Path fresh = fakeJdk(jdks, "temurin-25.0.4", "25.0.4");

        new StableJdkPointer(jdks).ensure("temurin-25", fresh);

        assertThat(jdks.resolve("temurin-25").toRealPath()).isEqualTo(fresh.toRealPath());
    }

    @Test
    void symlink_points_at_patch_dir_and_repoints_on_upgrade(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path p3 = fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        StableJdkPointer ptr = new StableJdkPointer(jdks);

        ptr.ensure("temurin-25", p3);
        Path link = jdks.resolve("temurin-25");
        // Junction on Windows is not Files.isSymbolicLink; resolve is the cross-platform check.
        assertThat(link.toRealPath()).isEqualTo(p3.toRealPath());

        // Idempotent: a second ensure at the same target is a no-op.
        ptr.ensure("temurin-25", p3);
        assertThat(link.toRealPath()).isEqualTo(p3.toRealPath());

        // Upgrade: the symlink repoints at the new patch, the path is unchanged.
        Path p4 = fakeJdk(jdks, "temurin-25.0.4", "25.0.4");
        ptr.ensure("temurin-25", p4);
        assertThat(link.toRealPath()).isEqualTo(p4.toRealPath());
        // No Contents/Home on this layout → java home is the pointer itself.
        assertThat(ptr.javaHome("temurin-25")).isEqualTo(link);
    }

    @Test
    void java_home_resolves_macos_contents_home(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path install = jdks.resolve("temurin-25.0.3");
        Files.createDirectories(install.resolve("Contents/Home/bin"));
        StableJdkPointer ptr = new StableJdkPointer(jdks);

        ptr.ensure("temurin-25", install);
        assertThat(ptr.javaHome("temurin-25"))
                .isEqualTo(jdks.resolve("temurin-25").resolve("Contents").resolve("Home"));
    }

    @Test
    void no_op_and_preserves_install_when_name_equals_install_dir(@TempDir Path tmp) throws IOException {
        // Vendor-level installs (e.g. graalvm-jdk-25) name the install dir the
        // same as the stable pointer — ensure() must not delete it.
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path install = fakeJdk(jdks, "graalvm-jdk-25", "25.0.3");
        StableJdkPointer ptr = new StableJdkPointer(jdks);

        ptr.ensure("graalvm-jdk-25", install);

        assertThat(JdkFingerprint.java(install)).exists();
        assertThat(Files.isSymbolicLink(install)).isFalse();
    }

    @Test
    void missing_install_is_a_no_op(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        StableJdkPointer ptr = new StableJdkPointer(jdks);
        ptr.ensure("temurin-25", jdks.resolve("does-not-exist"));
        assertThat(Files.exists(jdks.resolve("temurin-25"))).isFalse();
    }
}
