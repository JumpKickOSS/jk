// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
    void a_pom_only_directory_is_refused_with_both_remedies(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        for (Map<String, Object> out : List.of(
                McpManifest.setJava(dir.toString(), 21, true),
                McpManifest.deps(dir.toString(), "add", List.of("com.acme:thing:1.0.0"), "main", true),
                McpManifest.workspace(dir.toString(), "add_member", "api", true),
                McpUpdate.run(dir.toString(), List.of(), false, false))) {
            assertThat(out.get("applied")).isEqualTo(false);
            assertThat(String.valueOf(out.get("error")))
                    .contains("built in place from pom.xml")
                    .contains("jk import pom.xml")
                    .contains("edit the POM");
        }
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("pom.xml");
        }
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
    void applied_manifest_edits_carry_the_relock_hint(@TempDir Path dir) throws Exception {
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
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
                jobs,
                d -> Map.of(),
                List::of,
                "0.12.0");
        Files.writeString(dir.resolve("jk.toml"), TABLE_TERMINATED, StandardCharsets.UTF_8);
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

    /**
     * The edit goes through {@link cc.jumpkick.config.JkBuildEditor}, not a regex. The regex
     * matched only a bare integer to end-of-line, so a commented value missed and the miss fell
     * through to the "insert a root key" branch — writing a second {@code java =} line, which is
     * invalid TOML, and writing it to disk unvalidated with {@code applied: true}.
     */
    @Test
    void set_java_replaces_an_annotated_value_instead_of_duplicating_the_key(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                "name = \"a\"\ngroup = \"g\"\nversion = \"1\"\njava = 17  # from the CI image\n[dependencies]\n",
                StandardCharsets.UTF_8);
        Map<String, Object> out = McpManifest.setJava(dir.toString(), 25, true);
        assertThat(out).doesNotContainKey("error");
        String after = Files.readString(dir.resolve("jk.toml"), StandardCharsets.UTF_8);
        assertThat(after).containsOnlyOnce("java =");
        assertThat(after).contains("# from the CI image");
        assertThat(JkBuildParser.parse(after).project().javaRelease()).isEqualTo(25);
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

    @Test
    void scope_parse_survives_a_turkish_default_locale_and_never_defaults_an_unknown() {
        // Under tr, 'i' ⇄ 'I' do not round-trip ("MAIN".toLowerCase() is "maın"), and this is the
        // agent-facing surface: swallowing an unknown scope into MAIN silently rewrote a runtime
        // request into a main dependency.
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(McpManifest.parseScope("MAIN")).isEqualTo(Scope.MAIN);
            assertThat(McpManifest.parseScope("runtime")).isEqualTo(Scope.RUNTIME);
            assertThat(McpManifest.parseScope("test-dev")).isEqualTo(Scope.TEST_DEV);
            assertThatThrownBy(() -> McpManifest.parseScope("runtimes"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runtimes")
                    .hasMessageContaining("runtime");
        } finally {
            Locale.setDefault(prev);
        }
    }
}
