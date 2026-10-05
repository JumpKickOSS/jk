// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The Deliverables table: every step that leaves an artifact, with what its last label said. */
class JkResultsDeliverablesSectionTest {

    @Test
    void deliverables_table_covers_native_image_install_publish() {
        var tasks = List.of(
                task("package-jar", "package", "SUCCESS", 50),
                task("native-image", "native", "SUCCESS", 8_000),
                task("write-image", "image", "FAIL", 120),
                task("install", "other", "SUCCESS", 30),
                task("publish", "other", "SKIPPED", 0));
        BuildRecord r = record(false, tasks);
        String md = JkResultsMarkdown.render(r);
        assertThat(md).contains("## Deliverables");
        assertThat(md).contains("`native-image`");
        assertThat(md).contains("`write-image`");
        assertThat(md).contains("`install`");
        assertThat(md).contains("`publish`");
        assertThat(md).contains("| FAIL |");
        assertThat(md).contains("| SKIPPED |");
        assertThat(md).doesNotContain("## Failed steps");
    }

    @Test
    void a_node_module_s_jar_and_start_command_are_listed_with_its_deliverables() {
        var tasks = List.of(
                new BuildRecord.Task(
                        "node-package", "package", "SUCCESS", 40, 0, "package web-0.1.0.jar · dist/ under static/"),
                new BuildRecord.Task(
                        "package-jar", "package", "SUCCESS", 50, 0, "server · start: node build/index.js"));
        String md = JkResultsMarkdown.render(record(true, tasks));
        assertThat(md)
                .contains("| Module | Task | Status | Time | Detail |")
                .contains("`node-package` | SUCCESS")
                .contains("package web-0.1.0.jar · dist/ under static/")
                .contains("server · start: node build/index.js");
    }

    private static BuildRecord.Task task(String name, String stage, String status, long ms) {
        return new BuildRecord.Task(name, stage, status, ms, 0L);
    }

    private static BuildRecord record(boolean success, List<BuildRecord.Task> steps) {
        return new BuildRecord(
                "id",
                3,
                BuildRecord.SCHEMA,
                "build",
                "/ws",
                "g:a",
                "pid",
                1_000,
                1_100,
                100,
                success,
                false,
                success ? 0 : 1,
                "9.9",
                null,
                List.of(),
                steps,
                List.of(),
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L,
                null,
                List.of());
    }
}
