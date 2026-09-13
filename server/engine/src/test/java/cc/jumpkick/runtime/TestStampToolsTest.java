// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ToolIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [test] tools} in the run-tests stamp: a declared tool's PATH location and version line
 * are inputs, so an upgrade or a disappearance retests and an unchanged tool replays. The PATH is
 * the one the test JVM gets — the caller's shell rides the session, as {@code [test] env} does.
 */
@DisabledOnOs(OS.WINDOWS)
class TestStampToolsTest {

    @Test
    void a_declared_tool_s_path_and_version_are_stamp_inputs_and_an_upgrade_moves_them(@TempDir Path tmp)
            throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path node = fakeTool(bin, "node", "v22.1.0");
        JkBuild project = project(tmp, "[test]\ntools = [\"node\"]\n");

        List<String> before = extras(project, tmp, bin);
        assertThat(before).contains("tool:node=" + node + "=v22.1.0");
        assertThat(extras(project, tmp, bin))
                .as("an unchanged tool costs nothing new")
                .isEqualTo(before);

        Thread.sleep(20); // a rewrite within the same millisecond would keep the memo's key
        fakeTool(bin, "node", "v24.0.0");
        List<String> upgraded = extras(project, tmp, bin);
        assertThat(upgraded).contains("tool:node=" + node + "=v24.0.0").isNotEqualTo(before);
    }

    @Test
    void a_tool_the_path_lacks_is_stamped_missing_and_an_undeclared_one_is_not_stamped(@TempDir Path tmp)
            throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        JkBuild declared = project(tmp, "[test]\ntools = [\"protoc\"]\n");
        assertThat(extras(declared, tmp, bin)).contains("tool:protoc=" + ToolIdentity.MISSING);

        fakeTool(bin, "protoc", "libprotoc 29.0");
        JkBuild undeclared = project(tmp, "");
        assertThat(extras(undeclared, tmp, bin)).noneMatch(s -> s.startsWith("tool:"));
    }

    @Test
    void the_identity_reads_the_first_version_line_and_resolves_the_first_path_entry(@TempDir Path tmp)
            throws Exception {
        Path first = Files.createDirectories(tmp.resolve("first"));
        Path second = Files.createDirectories(tmp.resolve("second"));
        Path git = fakeTool(first, "git", "git version 2.47.1\nbuilt from source");
        fakeTool(second, "git", "git version 2.30.0");
        String path = first + SearchPath.SEPARATOR + second;
        assertThat(ToolIdentity.of("git", path)).isEqualTo(git + "=git version 2.47.1");
        assertThat(ToolIdentity.of("git", null)).isEqualTo(ToolIdentity.MISSING);
        assertThat(ToolIdentity.of("nothing-here", path)).isEqualTo(ToolIdentity.MISSING);
    }

    /** The session carrying {@code bin} as the caller's PATH, the way the shell that ran jk does. */
    private static List<String> extras(JkBuild project, Path dir, Path bin) throws Exception {
        return SessionContext.where(
                Session.defaults().withVariant(null, Map.of("PATH", bin.toString())),
                () -> PlannerSupport.testStampExtras(dir, project));
    }

    private static Path fakeTool(Path bin, String name, String versionOutput) throws Exception {
        Path exe = bin.resolve(name);
        Files.writeString(exe, "#!/bin/sh\nprintf '%s\\n' '" + versionOutput.replace("\n", "' '") + "'\n");
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        return exe;
    }

    private static JkBuild project(Path dir, String extra) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + extra);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
