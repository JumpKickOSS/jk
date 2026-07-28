// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityCommandTest {

    /** Pretty-printed journal record (same shape as on-disk record.json). */
    private static final String RECORD =
            """
            {
              "id": "20260101T000000000-aaaa",
              "buildNumber": 31,
              "schema": 2,
              "kind": "build",
              "dir": "/proj",
              "coord": "com.example:app",
              "startedAt": 1000,
              "finishedAt": 3300,
              "millis": 2300,
              "success": true,
              "cancelled": false,
              "running": false,
              "modules": [{}, {}, {}, {}, {}, {}, {}, {}]
            }
            """;

    private static final String FAIL =
            """
            {
              "buildNumber": 12,
              "kind": "test",
              "dir": "/p",
              "coord": "g:n",
              "startedAt": 1,
              "finishedAt": 2,
              "millis": 500,
              "success": false,
              "cancelled": false,
              "modules": [{}]
            }
            """;

    @Test
    void formats_success_line_with_build_number_and_coord() {
        String plain = strip(ActivityCommand.formatRecord(RECORD, 1000 + 3 * 3600_000L, false, Theme.active()));
        assertThat(plain).contains("#31");
        assertThat(plain).contains("Success");
        assertThat(plain).contains("com.example");
        assertThat(plain).contains("app");
        assertThat(plain).contains("build");
        assertThat(plain).contains("8 modules");
        assertThat(plain).contains("2.3s");
        assertThat(plain).containsPattern("\\d+h ago");
    }

    @Test
    void formats_failure() {
        String plain = strip(ActivityCommand.formatRecord(FAIL, 10_000L, false, Theme.active()));
        assertThat(plain).contains("#12");
        assertThat(plain).contains("Failure");
        assertThat(plain).contains("test");
        assertThat(plain).contains("1 module");
    }

    @Test
    void loads_newest_first_from_journal_dir(@TempDir Path dir) throws Exception {
        write(dir, "20260101T000000000-aaaa", "{\"buildNumber\":1,\"coord\":\"a:a\",\"success\":true,\"millis\":1}");
        write(dir, "20260102T000000000-bbbb", "{\"buildNumber\":2,\"coord\":\"b:b\",\"success\":true,\"millis\":1}");
        List<String> recs = ActivityCommand.loadJournalRecords(dir, 10);
        assertThat(recs).hasSize(2);
        // newest id first (20260102… before 20260101…)
        assertThat(strip(ActivityCommand.formatRecord(recs.get(0), 1, false, Theme.active()))).contains("#2");
        assertThat(strip(ActivityCommand.formatRecord(recs.get(1), 1, false, Theme.active()))).contains("#1");
    }

    private static void write(Path journal, String id, String body) throws Exception {
        Path d = journal.resolve(id);
        Files.createDirectories(d);
        Files.writeString(d.resolve("record.json"), body, StandardCharsets.UTF_8);
    }

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
