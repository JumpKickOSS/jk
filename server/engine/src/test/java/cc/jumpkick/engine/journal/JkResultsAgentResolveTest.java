// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent report of a failed resolve: its manifest location and the conflict it ended on. */
class JkResultsAgentResolveTest {

    @Test
    void a_located_resolve_failure_names_the_failure_and_what_to_do_not_the_banner(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(
                dir.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\n\n[dependencies]\nlib = { group = \"g.x\" }\n");
        BuildRecord.Diag refused = new BuildRecord.Diag(
                "error",
                dir.toString(),
                "resolve-deps",
                "verbatim",
                "‼ Cannot resolve dependencies:\n  │ Package g.x:lib was not found in any repository\n"
                        + "  │ repository `private` skipped (HTTP 401: it asks for credentials)\n",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                manifest.toString(),
                5,
                0,
                0,
                List.of(),
                0);
        BuildRecord r =
                JkResultsAgentTest.record(dir.toString(), "lock", false, false, 90, null, List.of(refused), List.of());
        assertThat(JkResultsAgent.render(r))
                .contains("E jk.toml:5 │ Package g.x:lib was not found in any repository\n")
                .contains("  5|\tlib = { group = \"g.x\" }\n")
                .contains("HTTP 401")
                .doesNotContain("Cannot resolve dependencies:");
    }

    private static final String BOM_CONFLICT = "{\"conclusion\":\"the project's requirements cannot be resolved\","
            + "\"packages\":[],\"facts\":["
            + "{\"kind\":\"dependency\",\"package\":\"org.slf4j:slf4j-api\",\"versions\":\"2.0.13\","
            + "\"from\":\"ch.qos.logback:logback-classic\",\"fromVersions\":\"1.5.6\"},"
            + "{\"kind\":\"dependency\",\"package\":\"org.slf4j:slf4j-api\",\"versions\":\"1.7.36\","
            + "\"from\":\"org.apache.zookeeper:zookeeper\",\"fromVersions\":\"3.9.2\","
            + "\"pinnedBy\":\"org.apache.zookeeper:zookeeper-bom:3.9.2\"},"
            + "{\"kind\":\"dependency\",\"package\":\"org.apache.zookeeper:zookeeper\",\"versions\":\"3.9.2\"},"
            + "{\"kind\":\"dependency\",\"package\":\"ch.qos.logback:logback-classic\",\"versions\":\"1.5.6\"}],"
            + "\"declared\":[\"org.apache.zookeeper:zookeeper\",\"ch.qos.logback:logback-classic\"]}";

    private static final String BOM_PROSE = "‼ Cannot resolve dependencies:\n"
            + "  │ ch.qos.logback:logback-classic 1.5.6 depends on org.slf4j:slf4j-api 2.0.13\n"
            + "  │ org.apache.zookeeper:zookeeper 3.9.2 depends on org.slf4j:slf4j-api 1.7.36"
            + " (pinned by org.apache.zookeeper:zookeeper-bom:3.9.2)\n"
            + "  │ The project depends on org.apache.zookeeper:zookeeper 3.9.2\n"
            + "  │ The project depends on ch.qos.logback:logback-classic 1.5.6\n"
            + "  │ Therefore, the project's requirements cannot be resolved\n";

    private static final String BOM_BLOCK = "  conflict org.slf4j:slf4j-api\n"
            + "    2.0.13 wanted by ch.qos.logback:logback-classic 1.5.6\n"
            + "    1.7.36 wanted by org.apache.zookeeper:zookeeper 3.9.2,"
            + " pinned by org.apache.zookeeper:zookeeper-bom:3.9.2\n"
            + "    picked none: no version satisfies every want\n"
            + "    why(coord=org.slf4j:slf4j-api)\n";

    /** The verdict a build answers names the contested coordinate, its wants, the pick and the why call. */
    @Test
    void a_resolve_conflict_names_the_coordinate_each_want_and_the_why_call() {
        BuildRecord r = JkResultsAgentTest.record(
                "/ws/app",
                "build",
                false,
                false,
                90,
                null,
                List.of(resolveFailure(BOM_PROSE, BOM_CONFLICT, "", 0)),
                List.of());

        String verdict = JkResultsAgent.render(r);

        assertThat(verdict).contains(BOM_BLOCK);
        // The project's own single-version edges are not contested.
        assertThat(verdict).doesNotContain("conflict org.apache.zookeeper:zookeeper");
    }

    /** {@code diagnostics} reads the journal row back: the conflict survives it and renders the same. */
    @Test
    void the_conflict_survives_the_journal_row_the_diagnostics_tool_reads() {
        BuildRecord r = JkResultsAgentTest.record(
                "/ws/app",
                "build",
                false,
                false,
                90,
                null,
                List.of(resolveFailure(BOM_PROSE, BOM_CONFLICT, "", 0)),
                List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) MiniJson.parse(Json.write(r));

        BuildRecord read = requireNonNull(JkResultsAgent.recordOf(row));

        assertThat(JkResultsAgent.renderAll(read, null)).contains(BOM_BLOCK);
    }

    @Test
    void a_version_no_repository_has_names_what_is_available(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(
                dir.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\n\n[dependencies]\nwidget = \"9.9.9\"\n");
        String conflict = "{\"conclusion\":\"the project's requirements cannot be resolved\",\"packages\":[],"
                + "\"facts\":[{\"kind\":\"no-versions\",\"package\":\"com.foo:widget\",\"versions\":\"9.9.9\","
                + "\"available\":[\"1.0\"]},{\"kind\":\"dependency\",\"package\":\"com.foo:widget\","
                + "\"versions\":\"9.9.9\"}],\"declared\":[\"com.foo:widget\"]}";
        BuildRecord.Diag refused = resolveFailure(
                "‼ Cannot resolve dependencies:\n  │ No versions of com.foo:widget match 9.9.9\n",
                conflict,
                manifest.toString(),
                5);
        BuildRecord r =
                JkResultsAgentTest.record(dir.toString(), "lock", false, false, 90, null, List.of(refused), List.of());

        assertThat(JkResultsAgent.render(r))
                .contains("  5|\twidget = \"9.9.9\"\n")
                .contains("  conflict com.foo:widget\n"
                        + "    9.9.9 wanted by jk.toml\n"
                        + "    picked none: no version matches 9.9.9 · available 1.0\n"
                        + "    why(coord=com.foo:widget)\n");
    }

    @Test
    void a_resolve_failure_without_a_conflict_adds_no_conflict_lines() {
        BuildRecord r = JkResultsAgentTest.record(
                "/ws/app", "build", false, false, 90, null, List.of(resolveFailure(BOM_PROSE, "", "", 0)), List.of());

        assertThat(JkResultsAgent.render(r)).doesNotContain("conflict ").doesNotContain("why(");
    }

    private static BuildRecord.Diag resolveFailure(String message, String conflict, String file, int line) {
        return new BuildRecord.Diag(
                "error",
                "",
                "resolve-deps",
                "verbatim",
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                file,
                line,
                0,
                0,
                List.of(),
                0,
                "",
                "",
                conflict);
    }
}
