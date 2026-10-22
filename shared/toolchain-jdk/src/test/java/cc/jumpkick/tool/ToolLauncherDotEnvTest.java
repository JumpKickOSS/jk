// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** An installed or ephemeral tool sees the {@code .env} values its caller's environment lacks. */
@DisabledOnOs(OS.WINDOWS) // runs the POSIX launcher
class ToolLauncherDotEnvTest {

    /** A native "tool" that writes {@code $JDBC_URL|$HOME} into the file named by its first argument. */
    private static ToolEnv echoTool(Path tmp) throws Exception {
        Path tool = executable(tmp.resolve("tool/echo-env"), "#!/bin/sh\necho \"$JDBC_URL|$HOME\" > \"$1\"\n");
        return new ToolEnv(
                "echo-env", Coordinate.of("com.example", "echo-env", "1"), ToolEnv.NATIVE_BINARY, List.of(tool));
    }

    private static Path executable(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    @Test
    void the_launcher_evaluates_jk_env_export_beside_it(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("bin");
        Path launcher = ToolLauncher.install(tmp.resolve("envs"), bin, tmp.resolve("jdk"), echoTool(tmp));
        // The jk beside the launcher answers `env --export` as the real one does for a project's .env.
        executable(
                bin.resolve("jk"),
                "#!/bin/sh\n[ \"$1 $2\" = \"env --export\" ] && printf \"export JDBC_URL='jdbc:h2:file:./target/envdb'\\n\"\n");
        Path out = tmp.resolve("out.txt");

        Process p = new ProcessBuilder(launcher.toString(), out.toString())
                .directory(tmp.toFile())
                .inheritIO()
                .start();

        assertThat(p.waitFor()).isZero();
        assertThat(Files.readString(out).strip()).isEqualTo("jdbc:h2:file:./target/envdb|" + System.getenv("HOME"));
    }

    @Test
    void an_ephemeral_run_sets_dot_env_beneath_the_real_environment(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out.txt");
        Process p = ToolLauncher.startEphemeral(
                tmp.resolve("jdk"),
                echoTool(tmp),
                List.of(),
                List.of(out.toString()),
                Map.of("JDBC_URL", "jdbc:h2:mem:file", "HOME", "/from/dot/env"));

        assertThat(p.waitFor()).isZero();
        assertThat(Files.readString(out).strip()).isEqualTo("jdbc:h2:mem:file|" + System.getenv("HOME"));
    }
}
