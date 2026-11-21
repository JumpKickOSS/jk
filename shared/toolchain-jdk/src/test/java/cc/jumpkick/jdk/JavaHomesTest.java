// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JavaHomes#readBuildSoft} replaced the full parser with a bootstrap scan;
 * a workspace member must still auto-inherit the root's {@code jdk}/{@code java} pins the
 * way {@code WorkspaceResolve.applyWorkspace} did.
 */
class JavaHomesTest {

    @Test
    void member_inherits_root_jdk_and_java_pins(@TempDir Path ws) throws IOException {
        writeRoot(ws, """
                name = "ws"
                group = "g"
                version = "1"
                jdk = { vendor = "temurin", version = "21" }
                java = 21

                [workspace]
                modules = ["libs/a"]
                """);
        Path member = Files.createDirectories(ws.resolve("libs/a"));
        Files.writeString(member.resolve("jk.toml"), """
                name = "a"
                """);

        var build = requireNonNull(JavaHomes.readBuildSoft(member));
        assertThat(build.project().jdk()).isEqualTo("temurin-21");
        assertThat(build.project().javaRelease()).isEqualTo(21);
    }

    @Test
    void local_pin_wins_and_missing_key_still_inherits(@TempDir Path ws) throws IOException {
        writeRoot(ws, """
                name = "ws"
                group = "g"
                version = "1"
                jdk = { vendor = "temurin", version = "21" }
                java = 21

                [workspace]
                modules = ["libs/a"]
                """);
        Path member = Files.createDirectories(ws.resolve("libs/a"));
        Files.writeString(member.resolve("jk.toml"), """
                name = "a"
                java = 17
                """);

        var build = requireNonNull(JavaHomes.readBuildSoft(member));
        assertThat(build.project().javaRelease()).isEqualTo(17);
        assertThat(build.project().jdk()).isEqualTo("temurin-21");
    }

    @Test
    void standalone_project_stays_unpinned(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "solo"
                """);

        var build = requireNonNull(JavaHomes.readBuildSoft(dir));
        assertThat(build.project().jdk()).isNull();
        assertThat(build.project().javaRelease()).isZero();
    }

    /**
     * A launch, the IDE model and the engine's forked tools take their JDK here: a required patch
     * floor holds them as it holds the build, never settling on an installed 25.0.4 or on jk's own
     * runtime below it.
     */
    @Test
    void a_required_patch_floor_holds_the_launch_jdk(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve("jk.toml"), """
                name = "app"
                java = 21
                jdk = { version = ">=99.0.5", required = true }
                """);
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        fakeJdk(jdks.resolve("temurin-99.0.4"), "99.0.4");
        assertThatThrownBy(() -> JavaHomes.resolveJavaHome(dir, new JdkRegistry(jdks)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(">=99.0.5")
                .hasMessageContaining("jk jdk install 99");
        Path ok = fakeJdk(jdks.resolve("temurin-99.0.7"), "99.0.7");
        assertThat(JavaHomes.resolveJavaHome(dir, new JdkRegistry(jdks)).toRealPath())
                .isEqualTo(ok);
        Files.writeString(dir.resolve(".jdk-version"), "temurin-99");
        assertThat(new JdkResolver(new JdkRegistry(jdks)).resolve(dir).map(j -> j.home()))
                .as("jk jdk home: .jdk-version's JDK gives way to one that meets the floor")
                .hasValueSatisfying(h -> assertThat(h.getFileName().toString()).isEqualTo("temurin-99.0.7"));
    }

    private static Path fakeJdk(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(JdkFingerprint.java(home), "#!/fake");
        Files.writeString(JdkFingerprint.javac(home), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
        JdkOwnership.mark(home);
        return home.toRealPath();
    }

    private static void writeRoot(Path ws, String toml) throws IOException {
        Files.writeString(ws.resolve("jk.toml"), toml);
    }
}
