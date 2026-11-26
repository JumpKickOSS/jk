// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The declared tails re-terminal the plan past package-jar, and a guard run declares none. */
class AppendDeclaredTailsTerminalTest {

    @TempDir
    Path tmp;

    @Test
    void assembly_tail_survives_terminal_prune() throws Exception {
        assertThat(plannedSteps())
                .as("package-assembly must survive terminal prune after appendDeclaredTails")
                .contains(TaskNames.PACKAGE_JAR, TaskNames.PACKAGE_ASSEMBLY);
    }

    @Test
    void a_guard_run_packages_the_jar_and_no_delivery_tail() throws Exception {
        Set<String> names = SessionContext.where(SessionContext.current().withLanesOnly(true), this::plannedSteps);
        assertThat(names).contains(TaskNames.PACKAGE_JAR).doesNotContain(TaskNames.PACKAGE_ASSEMBLY);
    }

    @Test
    void the_lib_dir_tail_survives_terminal_prune() throws Exception {
        assertThat(plannedSteps("lib-dir = true\n"))
                .as("package-lib-dir must survive terminal prune after appendDeclaredTails")
                .contains(TaskNames.PACKAGE_JAR, TaskNames.PACKAGE_LIB_DIR)
                .doesNotContain(TaskNames.PACKAGE_ASSEMBLY);
    }

    @Test
    void the_lib_dir_layout_is_archived_after_it_is_laid_out() throws Exception {
        assertThat(plannedSteps("lib-dir = true\n"))
                .contains(TaskNames.PACKAGE_LIB_DIR, TaskNames.PACKAGE_LIB_DIR_ARCHIVE)
                .doesNotContain(TaskNames.PACKAGE_JLINK_ARCHIVE);
        assertThat(PlannerArchive.libDirArchiveStep(tmp, true).requires()).containsExactly(TaskNames.PACKAGE_LIB_DIR);
    }

    @Test
    void the_runtime_image_is_archived_after_it_is_linked() throws Exception {
        assertThat(plannedSteps("\n[jlink]\n"))
                .contains(TaskNames.PACKAGE_JLINK, TaskNames.PACKAGE_JLINK_ARCHIVE)
                .doesNotContain(TaskNames.PACKAGE_LIB_DIR_ARCHIVE);
        assertThat(PlannerArchive.jlinkArchiveStep(tmp, true).requires()).containsExactly(TaskNames.PACKAGE_JLINK);
    }

    @Test
    void a_module_with_neither_layout_plans_no_archive() throws Exception {
        assertThat(plannedSteps()).doesNotContain(TaskNames.PACKAGE_LIB_DIR_ARCHIVE, TaskNames.PACKAGE_JLINK_ARCHIVE);
        Set<String> guard = SessionContext.where(
                SessionContext.current().withLanesOnly(true), () -> plannedSteps("lib-dir = true\n\n[jlink]\n"));
        assertThat(guard).doesNotContain(TaskNames.PACKAGE_LIB_DIR_ARCHIVE, TaskNames.PACKAGE_JLINK_ARCHIVE);
    }

    private Set<String> plannedSteps() throws Exception {
        return plannedSteps("assembly = true\n");
    }

    private Set<String> plannedSteps(String applicationKeys) throws Exception {
        Path dir = tmp.resolve("app");
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(
                dir.resolve("src/main/java/ex/Main.java"),
                "package ex; class Main { public static void main(String[] a) {} }\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "app"
                version = "1.0"
                java = 25

                [application]
                main = "ex.Main"
                """ + applicationKeys);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(dir.resolve("jk-lock.toml"), "schema = 1\n");
        BuildPlanner.Inputs inputs = TestInputs.of(dir, cache).withSkipTests(true);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(inputs, true);
        PlannerTails.appendDeclaredTails(b, inputs);
        return b.build().steps().stream().map(s -> s.name()).collect(Collectors.toSet());
    }
}
