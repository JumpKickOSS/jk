// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replay re-redaction of journal records written before write-time redaction. */
class HttpHistoryApiTest {

    /**
     * Lexical {@code dir} scan does not unescape JSON, so fixtures use forward slashes — valid on
     * Windows for {@link Path#of} and free of {@code \U} escapes inside JSON string literals.
     */
    private static String jsonDir(Path dir) {
        return dir.toString().replace('\\', '/');
    }

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
                """.formatted(jsonDir(dir));
        String out = HttpHistoryApi.redactRecordJson(raw, new HashMap<>());
        assertThat(out).doesNotContain("s3cret-from-file").contains("***");
    }

    @Test
    void mcp_journal_suppliers_serve_redacted_records(@TempDir Path dir) throws Exception {
        // history view=full and diagnostics read raw journal JSON through the suppliers the
        // engine wires into McpHandler — those ride redactRecords, so an agent on the MCP surface
        // sees the same masking as the REST history stream.
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "demo"
                version = "1"
                """);
        Files.writeString(dir.resolve(".env"), "TOKEN=mcp-s3cret\n");
        String raw = """
                {"id":"x","kind":"build","dir":"%s","diagnostics":[{"severity":"error","message":"leak mcp-s3cret"}]}""".formatted(jsonDir(dir));
        var out = HttpHistoryApi.redactRecords(List.of(raw, raw));
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).doesNotContain("mcp-s3cret").contains("***");
        assertThat(out.get(1)).doesNotContain("mcp-s3cret");
    }

    @Test
    void secrets_with_json_escaped_characters_are_masked_in_the_escaped_document(@TempDir Path dir) throws Exception {
        // the document is escaped JSON — a secret containing a backslash and a quote was
        // persisted as pa\\ss"word → pa\\\\ss\\"word, which the raw-substring pass cannot match.
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "demo"
                version = "1"
                """);
        String secret = "pa\\ss\"word-9";
        // Single quotes: the .env dialect keeps the value literal (no escape processing).
        Files.writeString(dir.resolve(".env"), "TOKEN='" + secret + "'\n");
        String escaped = secret.replace("\\", "\\\\").replace("\"", "\\\"");
        String raw = """
                {
                  "id": "x",
                  "kind": "build",
                  "dir": "%s",
                  "projectId": "abc",
                  "diagnostics": [{"severity": "error", "message": "leak %s here"}]
                }
                """.formatted(jsonDir(dir), escaped);
        String out = HttpHistoryApi.redactRecordJson(raw, new HashMap<>());
        assertThat(out).doesNotContain(escaped).contains("***");
    }

    @Test
    void records_with_no_env_pass_through_unchanged(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "demo"
                version = "1"
                """);
        // Forward slashes keep the fixture parseable and match the lexical scan's returned value.
        String raw = "{\"id\": \"x\", \"kind\": \"build\", \"dir\": \"" + jsonDir(dir) + "\", \"projectId\": \"abc\"}";
        assertThat(HttpHistoryApi.redactRecordJson(raw, new HashMap<>())).isEqualTo(raw);
    }
}
