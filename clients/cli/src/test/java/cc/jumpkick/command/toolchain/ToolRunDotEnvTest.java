// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.tool.ToolLauncher;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk tool run} hands an installed tool the {@code .env} of the directory it runs in, under the shell's values. */
@DisabledOnOs(OS.WINDOWS) // the tool is a shell script
class ToolRunDotEnvTest {

    @Test
    void an_installed_tool_sees_the_dot_env_the_shell_does_not_override(@TempDir Path tmp) throws Exception {
        Path tool = tmp.resolve("tool/echo-env");
        Files.createDirectories(tool.getParent());
        Files.writeString(tool, "#!/bin/sh\necho \"$FLYWAY_URL|$HOME\" > \"$1\"\n");
        Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path state = tmp.resolve("state");
        ToolLauncher.install(
                JkDirs.toolEnvsDir(state),
                tmp.resolve("bin"),
                tmp.resolve("jdk"),
                new ToolEnv(
                        "echo-env",
                        Coordinate.of("com.example", "echo-env", "1"),
                        ToolEnv.NATIVE_BINARY,
                        List.of(tool)));
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve(".env"), "FLYWAY_URL=jdbc:h2:file:./target/envdb\nHOME=/from/dot/env\n");
        Path out = tmp.resolve("out.txt");

        int exit = run(
                "tool",
                "run",
                "-C",
                project.toString(),
                "--state-dir",
                state.toString(),
                "echo-env",
                "--",
                out.toString());

        assertThat(exit).isZero();
        assertThat(Files.readString(out).strip()).isEqualTo("jdbc:h2:file:./target/envdb|" + System.getenv("HOME"));
    }
}
