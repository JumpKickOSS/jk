// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A JVM module that declares {@code node} and builds none runs its tests on the locked Node.js. */
class TestNodePlanTest {

    @Test
    void a_jvm_module_declaring_node_plans_ensure_node_ahead_of_its_tests(@TempDir Path dir) throws Exception {
        Path module = scaffold(dir, "node = 24\n");
        Map<String, Task> byName = byName(plan(module, dir.resolve("cache")));

        assertThat(byName).containsKey(TaskNames.ENSURE_NODE);
        assertThat(Objects.requireNonNull(byName.get(TaskNames.RUN_TESTS)).requires())
                .contains(TaskNames.ENSURE_NODE);
        assertThat(Objects.requireNonNull(byName.get(TaskNames.COMPILE_JAVA)).requires())
                .doesNotContain(TaskNames.ENSURE_NODE);
    }

    @Test
    void a_jvm_module_without_node_plans_none(@TempDir Path dir) throws Exception {
        Path module = scaffold(dir, "");
        assertThat(byName(plan(module, dir.resolve("cache")))).doesNotContainKey(TaskNames.ENSURE_NODE);
    }

    @Test
    void the_locked_node_is_part_of_the_test_stamp(@TempDir Path dir) throws Exception {
        Path module = scaffold(dir, "node = 24\n");
        LockfileWriter.write(
                Lockfile.empty("1.0.0").withNode(new NodePin("24.21.0", null, null, Map.of())),
                module.resolve("jk-lock.toml"));
        String declared = NodeTestStamp.nodeTestToken(JkBuildParser.parse(module.resolve("jk.toml")), module);
        assertThat(declared).isEqualTo("node:24.21.0");

        Path plain = scaffold(dir.resolve("plain"), "");
        assertThat(NodeTestStamp.nodeTestToken(JkBuildParser.parse(plain.resolve("jk.toml")), plain))
                .isNull();
    }

    private static Path scaffold(Path dir, String extra) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/a"));
        Files.createDirectories(dir.resolve("src/test/java/a"));
        Files.writeString(dir.resolve("src/main/java/a/A.java"), "package a; public class A {}");
        Files.writeString(dir.resolve("src/test/java/a/ATest.java"), "package a; class ATest {}");
        Files.writeString(dir.resolve("jk.toml"), "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + extra);
        return dir;
    }

    private static BuildPlan plan(Path project, Path cache) throws Exception {
        Files.createDirectories(cache);
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlan.Builder b = BuildPlanner.coreBuilder(in);
        PlannerTails.appendDeclaredTails(b, in);
        return b.build();
    }

    private static Map<String, Task> byName(BuildPlan p) {
        Map<String, Task> out = new LinkedHashMap<>();
        for (Task t : p.steps()) out.putIfAbsent(t.name(), t);
        return out;
    }
}
