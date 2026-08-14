// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replay re-redaction of pre-JK-1878 journal records (JK-1963). */
class HttpHistoryApiTest {

    @Test
    void records_persisted_before_write_time_redaction_replay_masked(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "demo"
                version = "1"
                """);
        Files.writeString(dir.resolve(".env"), "TOKEN=s3cret-from-file\n");
        String raw = """
                {
                  "id": "x",
                  "kind": "build",
                  "dir": "%s",
                  "projectId": "abc",
                  "diagnostics": [{"severity": "error", "message": "leak s3cret-from-file here"}]
                }
                """.formatted(dir);
        String out = HttpHistoryApi.redactRecordJson(raw, new HashMap<>());
        assertThat(out).doesNotContain("s3cret-from-file").contains("***");
    }

    @Test
    void records_with_no_env_pass_through_unchanged(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "demo"
                version = "1"
                """);
        String raw = "{\"id\": \"x\", \"kind\": \"build\", \"dir\": \"" + dir + "\", \"projectId\": \"abc\"}";
        assertThat(HttpHistoryApi.redactRecordJson(raw, new HashMap<>())).isEqualTo(raw);
    }
}
