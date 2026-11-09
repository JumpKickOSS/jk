// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS) // asserts the POSIX launcher shape
class ToolLauncherKtsTest {

    @Test
    void kts_launcher_execs_kotlinc_script_with_the_dep_classpath(@TempDir Path tmp) throws Exception {
        Path envs = tmp.resolve("envs");
        Path bin = tmp.resolve("bin");
        Path kotlinc = tmp.resolve("kotlinc/bin/kotlinc");
        Path script = tmp.resolve("envs/hello/hello.kts");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "println(1)\n");
        Path dep = tmp.resolve("cas/aa/gson.jar");

        ToolEnv env = new ToolEnv("hello", Coordinate.of("script", "hello", "local"), "kotlin-script", List.of(dep));
        Path launcher = ToolLauncher.installKotlinScript(
                envs,
                bin,
                tmp.resolve("jdk"),
                kotlinc,
                script,
                env,
                new ToolProvenance("file", "hello.kts", script.toString()));

        assertThat(launcher).isEqualTo(bin.resolve("hello"));
        assertThat(Files.isExecutable(launcher)).isTrue();
        String body = Files.readString(launcher);
        assertThat(body).contains("-script").contains(script.toString());
        assertThat(body).contains("-classpath").contains(dep.toString());
        assertThat(body).contains("\"$@\"");

        String json = Files.readString(envs.resolve("hello/env.json"));
        assertThat(json).contains("\"mainClass\": \"kotlin-script\"");
        assertThat(json).contains(dep.toString()); // deps stay GC-reachable via env.json
    }

    @Test
    void kts_launcher_without_deps_omits_classpath(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("s.kts");
        Files.writeString(script, "println(1)\n");
        ToolEnv env = new ToolEnv("s", Coordinate.of("script", "s", "local"), "kotlin-script", List.of());
        Path launcher = ToolLauncher.installKotlinScript(
                tmp.resolve("envs"), tmp.resolve("bin"), tmp.resolve("jdk"), tmp.resolve("kotlinc"), script, env, null);
        assertThat(Files.readString(launcher)).doesNotContain("-classpath");
    }

    @Test
    void an_installed_tool_is_journaled_with_its_launcher(@TempDir Path tmp) throws Exception {
        Path journal = tmp.resolve("changes");
        ToolEnv env = new ToolEnv("hello", Coordinate.of("com.example", "hello", "1.0"), "com.example.Main", List.of());

        Path launcher = ChangeJournal.into(
                journal,
                () -> ChangeActor.as(
                        ChangeActor.shell(List.of("tool", "install", "com.example:hello:1.0")),
                        () -> ToolLauncher.install(tmp.resolve("envs"), tmp.resolve("bin"), tmp.resolve("jdk"), env)));

        Change entry = new ChangeJournal(journal).page(null, 10).entries().getFirst();
        assertThat(entry.what()).isEqualTo("tool hello");
        assertThat(entry.action()).isEqualTo(Change.INSTALL);
        assertThat(entry.diff()).isEqualTo("launcher " + launcher);
        assertThat(entry.actor().label()).isEqualTo("jk tool install com.example:hello:1.0");
    }
}
