// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    void healing_re_aims_the_pointer_at_the_newest_survivor(@TempDir Path tmp) throws IOException {
        // JK-2627. The pointer is a symlink; the install is the tree it aims at. Delete the tree and
        // the link survives pointing at nothing, so an IDE holding the stable path has a broken SDK.
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path p3 = fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        Path p10 = fakeJdk(jdks, "temurin-25.0.10", "25.0.10");
        Path p4 = fakeJdk(jdks, "temurin-25.0.4", "25.0.4");
        StableJdkPointer ptr = new StableJdkPointer(jdks);
        ptr.ensure("temurin-25", p4);

        // p4 is removed; 25.0.10 must win over 25.0.3 — and over 25.0.4 lexicographically, which is
        // why the ordering goes through JdkSelector.versionKey rather than String.compareTo.
        PathUtil.deleteRecursively(p4);
        ptr.healAfterRemoval("temurin-25", hits(p3, p10));

        assertThat(jdks.resolve("temurin-25").toRealPath()).isEqualTo(p10.toRealPath());
    }

    @Test
    void healing_retires_the_pointer_when_nothing_of_that_major_survives(@TempDir Path tmp) throws IOException {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path only = fakeJdk(jdks, "temurin-25.0.4", "25.0.4");
        Path other = fakeJdk(jdks, "temurin-26.0.1", "26.0.1"); // different major: not a candidate
        StableJdkPointer ptr = new StableJdkPointer(jdks);
        ptr.ensure("temurin-25", only);

        PathUtil.deleteRecursively(only);
        ptr.healAfterRemoval("temurin-25", hits(other));

        assertThat(Files.exists(jdks.resolve("temurin-25"), LinkOption.NOFOLLOW_LINKS))
                .as("a dangling link is worse than no link")
                .isFalse();
        assertThat(other.resolve("release")).as("the other major is untouched").exists();
    }

    @Test
    void healing_ignores_a_directory_emptied_by_an_interrupted_delete(@TempDir Path tmp) throws IOException {
        // The debris this bug left on the reporting machine: JDK-shaped names holding nothing.
        // Linking to one reads as configured and fails at exec, which is worse than no link.
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path gone = fakeJdk(jdks, "temurin-25.0.9", "25.0.9");
        PathUtil.deleteRecursively(gone);
        Files.createDirectories(gone); // name back, contents not
        Path live = fakeJdk(jdks, "temurin-25.0.4", "25.0.4");

        new StableJdkPointer(jdks).healAfterRemoval("temurin-25", hits(gone, live));

        assertThat(jdks.resolve("temurin-25").toRealPath()).isEqualTo(live.toRealPath());
    }

    @Test
    void ensure_never_deletes_an_install_to_take_the_pointer_name(@TempDir Path tmp) throws IOException {
        // JK-2627 tightens JK-2624: even a JDK jk owns is not deleted to free a name. Removing one
        // is minutes of download and belongs to an explicit `jk jdk` verb.
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path ours = fakeJdk(jdks, "temurin-25", "25");
        JdkOwnership.mark(ours);
        Path fresh = fakeJdk(jdks, "temurin-25.0.4", "25.0.4");

        assertThatIOException()
                .isThrownBy(() -> new StableJdkPointer(jdks).ensure("temurin-25", fresh))
                .withMessageContaining("never deletes a JDK");

        assertThat(JdkFingerprint.java(ours)).as("even our own install stays").exists();
    }

    @Test
    void pointer_name_is_derived_from_the_install_identifier() {
        assertThat(StableJdkPointer.pointerNameFor("temurin-25.0.4.1")).contains("temurin-25");
        assertThat(StableJdkPointer.pointerNameFor("graalvm-25")).contains("graalvm-25");
        assertThat(StableJdkPointer.pointerNameFor("nonsense")).isEmpty();
    }

    /** {@link JdkHit}s for fake installs, versioned from the directory name. */
    private static List<JdkHit> hits(Path... homes) {
        List<JdkHit> out = new ArrayList<>();
        for (Path h : homes) {
            String name = h.getFileName().toString();
            out.add(new JdkHit(h, name.substring(name.indexOf('-') + 1), JdkVendor.TEMURIN, "jk"));
        }
        return out;
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
                .withMessageContaining("never deletes a JDK");

        assertThat(theirs.resolve("release")).as("their JDK is untouched").exists();
        assertThat(JdkFingerprint.java(theirs)).exists();
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
