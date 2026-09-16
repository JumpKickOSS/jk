// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A module's key spelling is relative to its project root on disk and absolute again in memory. */
class ModuleKeysTest {

    @Test
    void every_checkout_of_a_project_spells_a_module_the_same_way() {
        assertThat(ModuleKeys.relative("/src/jk/server/io", "/src/jk")).isEqualTo("server/io");
        assertThat(ModuleKeys.relative("/src/jk-wt/argv/server/io", "/src/jk-wt/argv"))
                .isEqualTo("server/io");
        assertThat(ModuleKeys.relative("/src/jk", "/src/jk")).isEqualTo(ModuleKeys.ROOT);
        assertThat(ModuleKeys.relative("/src/jk/", "/src/jk")).isEqualTo(ModuleKeys.ROOT);
    }

    @Test
    void a_module_outside_the_root_and_a_rootless_write_keep_the_absolute_path() {
        assertThat(ModuleKeys.relative("/elsewhere/lib", "/src/jk")).isEqualTo("/elsewhere/lib");
        assertThat(ModuleKeys.relative("/src/jk-two/lib", "/src/jk"))
                .as("a sibling that merely shares a prefix is not under the root")
                .isEqualTo("/src/jk-two/lib");
        assertThat(ModuleKeys.relative("/src/jk/server/io", null)).isEqualTo("/src/jk/server/io");
    }

    @Test
    void reading_expands_relative_spellings_against_the_root_asked_for() {
        String root = "/src/jk-wt/argv";
        assertThat(ModuleKeys.absolute("module.server/io.task.compile-java.wall-ms", root))
                .isEqualTo("module./src/jk-wt/argv/server/io.task.compile-java.wall-ms");
        assertThat(ModuleKeys.absolute("module._.task.guard.wall-ms", root))
                .isEqualTo("module./src/jk-wt/argv.task.guard.wall-ms");
        assertThat(ModuleKeys.absolute("module.server/io.test-class.com.example.FooTest.wall-ms", root))
                .isEqualTo("module./src/jk-wt/argv/server/io.test-class.com.example.FooTest.wall-ms");
        assertThat(ModuleKeys.absolute("module.clients/cli.phase.compile.wall-ms", root))
                .isEqualTo("module./src/jk-wt/argv/clients/cli.phase.compile.wall-ms");
    }

    @Test
    void absolute_coord_host_and_rootless_keys_pass_through_unchanged() {
        String root = "/src/jk";
        for (String key : new String[] {
            "module./elsewhere/lib.task.compile-java.wall-ms",
            "module.C:/src/lib.task.compile-java.wall-ms",
            "module.cc.jumpkick:jk-host.wall-ms",
            "task.compile-java.wall-ms",
            "invocation.build.wall-ms",
            "workspace.wall-ms"
        }) {
            assertThat(ModuleKeys.absolute(key, root)).as(key).isEqualTo(key);
        }
        assertThat(ModuleKeys.absolute("module.server/io.task.compile-java.wall-ms", null))
                .isEqualTo("module.server/io.task.compile-java.wall-ms");
    }

    @Test
    void relative_then_absolute_is_the_module_path_the_reader_looks_up() {
        String moduleDir = "/src/jk-wt/argv/server/engine";
        String root = "/src/jk-wt/argv";
        String written = "module." + ModuleKeys.relative(moduleDir, root) + ".task.run-tests.wall-ms";
        assertThat(ModuleKeys.absolute(written, AggregatedMetrics.sanitize(root)))
                .isEqualTo("module." + AggregatedMetrics.sanitize(moduleDir) + ".task.run-tests.wall-ms");
    }
}
