// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.jsonl.MiniJson;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code GET /api/history} serves a noisy run's whole diagnostics list, the spilled part included. */
class HttpHistoryDiagnosticsSpillTest extends HttpEngineServerHarness {

    @TempDir
    Path project;

    @Test
    void the_list_and_the_single_build_endpoint_carry_every_spilled_diagnostic() throws Exception {
        Files.writeString(project.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\nversion = \"1\"\n");
        int count = 4_000;
        List<BuildRecord.Diag> diags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            diags.add(new BuildRecord.Diag(
                    "error", project.toString(), "compile", "javac", i + " " + "x".repeat(1_000), null, null));
        }
        BuildJournal journal = testJournal();
        String locator = requireNonNull(journal.append(record(diags), BuildJournal.Snapshot.NONE));
        assertThat(requireNonNull(journal.runDir(locator).orElseThrow()).resolve(BuildJournal.DIAGNOSTICS_SPILL))
                .isRegularFile();
        String id = requireNonNull(journal.get(locator).orElseThrow().id());

        HttpResponse<String> list = get("/api/history");
        assertThat(list.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) requireNonNull(MiniJson.parse(list.body()));
        assertThat(rows).singleElement().satisfies(row -> assertWhole(row, count));

        HttpResponse<String> one = get("/api/history?id=" + id);
        assertThat(one.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) requireNonNull(MiniJson.parse(one.body()));
        assertWhole(row, count);
    }

    private static void assertWhole(Map<String, Object> row, int count) {
        assertThat(row).doesNotContainKey("diagnosticsSpill");
        List<?> diagnostics = (List<?>) requireNonNull(row.get("diagnostics"));
        assertThat(diagnostics).hasSize(count);
        assertThat(String.valueOf(((Map<?, ?>) diagnostics.getLast()).get("message")))
                .startsWith((count - 1) + " ");
    }

    private BuildRecord record(List<BuildRecord.Diag> diags) {
        return new BuildRecord(
                null,
                1L,
                BuildRecord.SCHEMA,
                "build",
                project.toString(),
                "g:a",
                "p-1",
                1_700_000_000_000L,
                1_700_000_001_000L,
                1_000,
                false,
                false,
                1,
                "9.9-test",
                null,
                List.of(),
                List.of(),
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                3L,
                null,
                List.of());
    }
}
