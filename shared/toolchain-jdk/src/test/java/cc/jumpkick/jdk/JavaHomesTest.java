// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

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
                jdk = "temurin-21"
                java = 21

                [workspace]
                modules = ["libs/a"]
                """);
        Path member = Files.createDirectories(ws.resolve("libs/a"));
        Files.writeString(member.resolve("jk.toml"), """
                name = "a"
                """);

        var build = JavaHomes.readBuildSoft(member);
        assertThat(build.project().jdk()).isEqualTo("temurin-21");
        assertThat(build.project().javaRelease()).isEqualTo(21);
    }

    @Test
    void local_pin_wins_and_missing_key_still_inherits(@TempDir Path ws) throws IOException {
        writeRoot(ws, """
                name = "ws"
                group = "g"
                version = "1"
                jdk = "temurin-21"
                java = 21

                [workspace]
                modules = ["libs/a"]
                """);
        Path member = Files.createDirectories(ws.resolve("libs/a"));
        Files.writeString(member.resolve("jk.toml"), """
                name = "a"
                java = 17
                """);

        var build = JavaHomes.readBuildSoft(member);
        assertThat(build.project().javaRelease()).isEqualTo(17);
        assertThat(build.project().jdk()).isEqualTo("temurin-21");
    }

    @Test
    void standalone_project_stays_unpinned(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "solo"
                """);

        var build = JavaHomes.readBuildSoft(dir);
        assertThat(build.project().jdk()).isNull();
        assertThat(build.project().javaRelease()).isZero();
    }

    private static void writeRoot(Path ws, String toml) throws IOException {
        Files.writeString(ws.resolve("jk.toml"), toml);
    }
}
