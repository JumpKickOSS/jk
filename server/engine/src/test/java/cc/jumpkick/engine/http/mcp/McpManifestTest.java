// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpManifestTest {

    private static final String TABLE_TERMINATED = """
            group   = "com.example"
            name    = "widget"
            version = "0.1.0"
            jdk     = 25

            [dependencies]

            [test-dependencies]
            """;

    @Test
    void set_java_on_a_table_terminated_manifest_lands_at_top_level(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), TABLE_TERMINATED, StandardCharsets.UTF_8);
        Map<String, Object> out = McpManifest.setJava(dir.toString(), 21, true);
        assertThat(out.get("applied")).isEqualTo(true);
        assertThat(out).doesNotContainKey("error");
        String after = Files.readString(dir.resolve("jk.toml"), StandardCharsets.UTF_8);
        assertThat(after.indexOf("java = 21")).isLessThan(after.indexOf("[dependencies]"));
        assertThat(JkBuildParser.parse(after).project().javaRelease()).isEqualTo(21);
    }

    @Test
    void applied_writes_are_atomic_and_leave_no_temp_sibling(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), TABLE_TERMINATED, StandardCharsets.UTF_8);
        McpManifest.setJava(dir.toString(), 21, true);
        McpManifest.deps(dir.toString(), "add", List.of("com.acme:thing:1.0.0"), "main", true);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("jk.toml");
        }
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("thing");
    }

    @Test
    void applied_manifest_edits_carry_the_relock_hint() {
        EngineHttpJobs jobs = new EngineHttpJobs() {
            @Override
            public long trigger(JobSpec spec) {
                return 1L;
            }

            @Override
            public boolean cancel(long requestId) {
                return false;
            }

            @Override
            public int cancelDir(String dir) {
                return 0;
            }
        };
        McpHandler mcp = new McpHandler(
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 0, 8, 16L << 30),
                jobs,
                d -> Map.of(),
                List::of,
                "0.12.0");
        Path dir = tempProject();
        String applied = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_manifest\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"java\":21,\"apply\":true}}}");
        assertThat(applied).contains("jk_run kind=lock");
        String preview = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_manifest\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"java\":25,\"apply\":false}}}");
        assertThat(preview).doesNotContain("jk_run kind=lock");
    }

    private static Path tempProject() {
        try {
            Path dir = Files.createTempDirectory("mcp-manifest-hint");
            dir.toFile().deleteOnExit();
            Files.writeString(dir.resolve("jk.toml"), TABLE_TERMINATED, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void set_java_replaces_an_existing_root_line(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"), "name = \"a\"\ngroup = \"g\"\nversion = \"1\"\njava = 17\n[dependencies]\n");
        McpManifest.setJava(dir.toString(), 25, true);
        String after = Files.readString(dir.resolve("jk.toml"), StandardCharsets.UTF_8);
        assertThat(after).contains("java = 25");
        assertThat(after).doesNotContain("java = 17");
        assertThat(JkBuildParser.parse(after).project().javaRelease()).isEqualTo(25);
    }
}
