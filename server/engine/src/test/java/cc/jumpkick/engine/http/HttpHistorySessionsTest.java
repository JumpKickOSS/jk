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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dashboard groups runs by who asked, and it reads that from {@code GET /api/history} — so two
 * MCP sessions and one CLI run on the same project must come back as three distinguishable
 * {@code trigger}/{@code session} pairs, the same fields the results header prints.
 */
class HttpHistorySessionsTest extends HttpEngineServerHarness {

    @TempDir
    Path project;

    @Test
    void two_mcp_sessions_and_the_cli_are_three_groups_in_the_history_payload() throws Exception {
        Files.writeString(project.resolve("jk.toml"), "group = \"g\"\nname = \"a\"\nversion = \"1\"\n");
        BuildJournal journal = testJournal();
        long t = 1_700_000_000_000L;
        finished(journal, 11, t + 1_000, "mcp", "claude-code 3f9a");
        finished(journal, 12, t + 2_000, "cli", null);
        finished(journal, 13, t + 3_000, "mcp", "codex 9c01");
        finished(journal, 14, t + 4_000, "mcp", "claude-code 3f9a");

        HttpResponse<String> res = get("/api/history");
        assertThat(res.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) requireNonNull(MiniJson.parse(res.body()));
        assertThat(rows).hasSize(4);

        Set<String> groups = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            groups.add(row.get("trigger") + "|" + row.get("session"));
        }
        assertThat(groups).containsExactlyInAnyOrder("mcp|claude-code 3f9a", "cli|null", "mcp|codex 9c01");
        Map<String, Object> newest = rows.getFirst();
        assertThat(newest.get("buildNumber")).isEqualTo(14.0);
        assertThat(newest.get("trigger")).isEqualTo("mcp");
        assertThat(newest.get("session")).isEqualTo("claude-code 3f9a");
        // The CLI row carries no session key at all — absent, not an empty string.
        Map<String, Object> cli = rows.stream()
                .filter(r -> "cli".equals(r.get("trigger")))
                .findFirst()
                .orElseThrow();
        assertThat(cli).doesNotContainKey("session");
    }

    private void finished(
            BuildJournal journal, long buildNumber, long startedAt, String trigger, @Nullable String session) {
        String dir = project.toString();
        String locator = requireNonNull(journal.begin(BuildRecord.running(
                buildNumber, "build", dir, "g:a", "p1", startedAt, "9.9", trigger, session, buildNumber)));
        BuildRecord done = new BuildRecord(
                null,
                buildNumber,
                BuildRecord.SCHEMA,
                "build",
                dir,
                "g:a",
                "p1",
                startedAt,
                startedAt + 500,
                500,
                true,
                false,
                0,
                "9.9",
                null,
                List.of(),
                List.of(),
                List.of(),
                trigger,
                session,
                null,
                null,
                false,
                null,
                buildNumber,
                null,
                List.of());
        assertThat(journal.complete(locator, done, BuildJournal.Snapshot.NONE)).isTrue();
    }
}
