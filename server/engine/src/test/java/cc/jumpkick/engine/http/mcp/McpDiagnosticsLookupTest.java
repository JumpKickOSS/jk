// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpDiagnosticsLookupTest {

    private static final String FAIL =
            "{\"id\":\"old\",\"kind\":\"test\",\"dir\":\"/ws\",\"success\":false,\"running\":false,"
                    + "\"requestId\":1,\"exitCode\":1}";
    private static final String RUNNING =
            "{\"id\":\"now\",\"kind\":\"format\",\"dir\":\"/ws\",\"success\":false,\"running\":true,"
                    + "\"requestId\":9,\"exitCode\":0}";
    private static final String FORMAT =
            "{\"id\":\"fmt\",\"kind\":\"format\",\"dir\":\"/ws\",\"success\":true,\"running\":false,"
                    + "\"requestId\":9,\"exitCode\":0}";
    private static final String OTHER =
            "{\"id\":\"x\",\"kind\":\"build\",\"dir\":\"/other\",\"success\":false,\"running\":false,"
                    + "\"requestId\":3,\"exitCode\":1}";

    @Test
    void last_fail_skips_running_stubs() {
        Map<String, Object> rec = requireNonNull(McpDiagnostics.findRun(List.of(RUNNING, FAIL), "last-fail", "/ws"));
        assertThat(rec.get("id")).isEqualTo("old");
    }

    @Test
    void find_by_request_id_prefers_finished_row() {
        assertThat(requireNonNull(McpDiagnostics.findByRequestId(List.of(RUNNING, FORMAT), 9))
                        .get("id"))
                .isEqualTo("fmt");
        assertThat(McpDiagnostics.findByRequestId(List.of(RUNNING), 9)).isNull();
    }

    @Test
    void explicit_run_id_bypasses_the_dir_filter() {
        Map<String, Object> rec = requireNonNull(McpDiagnostics.findRun(List.of(FAIL, OTHER), "x", "/ws"));
        assertThat(rec.get("id")).isEqualTo("x");
        assertThat(rec.get("dir")).isEqualTo("/other");
    }

    @Test
    void newest_finished_is_not_last_fail() {
        Map<String, Object> rec = requireNonNull(McpDiagnostics.findNewest(List.of(FORMAT, FAIL, OTHER), "/ws"));
        assertThat(rec.get("id")).isEqualTo("fmt");
        assertThat(rec.get("success")).isEqualTo(true);
    }

    /**
     * A worktree nested under the bound checkout is a sibling in the project's checkout set, not a
     * subdirectory of the bound one: its newest run is not the parent's.
     */
    @Test
    void a_nested_worktree_recorded_as_a_checkout_is_not_the_parents_run(@TempDir Path tmp, @TempDir Path stateDir)
            throws Exception {
        System.setProperty("jk.env.JK_STATE_DIR", stateDir.toString());
        String id = "aabbccddeeff00112233445566778899";
        Path parent = Files.createDirectories(tmp.resolve("ws"));
        Path nested = Files.createDirectories(parent.resolve(".worktrees/feature"));
        Path module = Files.createDirectories(parent.resolve("app"));
        Path home = ProjectBuilds.projectHome(id);
        ProjectIdentity.IdentityFile.write(
                home, new ProjectIdentity(id, "g:a", parent, ProjectIdentity.Source.LOCK, null, null));
        ProjectIdentity.IdentityFile.write(
                home, new ProjectIdentity(id, "g:a", nested, ProjectIdentity.Source.LOCK, null, null));
        String nestedRun = "{\"id\":\"nested\",\"kind\":\"build\",\"dir\":" + MiniJson.write(nested.toString())
                + ",\"projectId\":\"" + id + "\",\"success\":false,\"running\":false,\"requestId\":5}";
        String moduleRun = "{\"id\":\"module\",\"kind\":\"build\",\"dir\":" + MiniJson.write(module.toString())
                + ",\"projectId\":\"" + id + "\",\"success\":true,\"running\":false,\"requestId\":6}";
        Map<String, Object> newest =
                requireNonNull(McpDiagnostics.findNewest(List.of(nestedRun, moduleRun), parent.toString()));
        assertThat(newest.get("id"))
                .as("a module dir under the checkout still answers")
                .isEqualTo("module");
        assertThat(McpDiagnostics.findNewest(List.of(nestedRun), parent.toString()))
                .isNull();
        assertThat(requireNonNull(McpDiagnostics.findNewest(List.of(nestedRun), nested.toString()))
                        .get("id"))
                .isEqualTo("nested");
    }
}
