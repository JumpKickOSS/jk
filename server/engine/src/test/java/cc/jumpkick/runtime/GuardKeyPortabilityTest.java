// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.host.Os;
import cc.jumpkick.task.JdkToolchain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A guard lane's key names what the lane reads and the Java major, never the JDK build or the
 * operating system, so a verdict made on macOS with JDK 25.0.2 is one Linux on 25.0.5 takes; only a
 * lane that runs a guard suite, code that may skip itself on one OS, keys the OS.
 */
class GuardKeyPortabilityTest {

    @Test
    void a_module_lane_names_the_java_major_and_neither_the_jdk_build_nor_the_os(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njava = 25\n");
        LoadResult load = PlannerGuards.rules(PlannerGuards.detectAt(root));

        List<String> tokens = new ArrayList<>(GuardKeys.moduleLaneTokens(".", "facts", root, true, null, List.of()));
        GuardKeys.addRuleTokens(tokens, load);

        assertThat(tokens).contains("java:" + Runtime.version().feature());
        assertThat(tokens)
                .as("no token names the JDK build: a patch or another vendor of the major keys alike")
                .noneMatch(t -> t.contains(JdkToolchain.PREFIX));
        assertThat(tokens)
                .as("declarative rules read the same facts on every OS")
                .noneMatch(t -> t.startsWith("os:"));
    }

    @Test
    void a_root_lane_names_no_os(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\n");
        LoadResult load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        List<String> tokens = new ArrayList<>(GuardKeys.treeLaneTokens(root));
        GuardKeys.addRuleTokens(tokens, load);
        assertThat(tokens).noneMatch(t -> t.startsWith("os:") || t.contains(JdkToolchain.PREFIX));
    }

    @Test
    void a_lane_that_runs_a_guard_suite_names_the_os(@TempDir Path root) throws Exception {
        List<String> tokens = GuardKeys.suiteTokens("suite", List.of(), root, List.of());
        assertThat(tokens).contains("os:" + Os.name());
    }
}
