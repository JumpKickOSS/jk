// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.verbs.ManifestEdit;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.RepoFixtures;
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
        Map<String, Object> out = setJava(dir.toString(), 21, true);
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
                setJava(dir.toString(), 21, true),
                deps(dir.toString(), "add", List.of("com.acme:thing:1.0.0"), "main", true),
                workspace(dir.toString(), "add_member", "api", true),
                McpUpdate.run(dir.toString(), List.of(), false, false))) {
            assertThat(out.get("applied")).isEqualTo(false);
            assertThat(String.valueOf(out.get("error")))
                    .contains("no jk.toml in")
                    .contains("edit pom.xml or run jk import pom.xml");
        }
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("pom.xml");
        }
    }

    @Test
    void applied_writes_are_atomic_and_leave_no_temp_sibling(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Path repo = tmp.resolve("repo");
        RepoFixtures.module(repo, "com.acme", "thing", "1.0.0");
        Files.writeString(
                dir.resolve("jk.toml"),
                TABLE_TERMINATED + "\n[repositories.local]\nurl = \"" + repo.toUri() + "\"\ngroups = [\"com.acme\"]\n",
                StandardCharsets.UTF_8);
        setJava(dir.toString(), 21, true);
        deps(dir.toString(), "add", List.of("com.acme:thing:1.0.0"), "main", true);
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
                + "\"params\":{\"name\":\"manifest\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"java\":21,\"apply\":true}}}");
        assertThat(applied).contains("run kind=lock");
        String preview = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"manifest\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"java\":25,\"apply\":false}}}");
        assertThat(preview).doesNotContain("run kind=lock");
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
        Map<String, Object> out = setJava(dir.toString(), 25, true);
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
        setJava(dir.toString(), 25, true);
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
            assertThat(ManifestEdit.parseScope("MAIN")).isEqualTo(Scope.MAIN);
            assertThat(ManifestEdit.parseScope("runtime")).isEqualTo(Scope.RUNTIME);
            assertThat(ManifestEdit.parseScope("test-dev")).isEqualTo(Scope.TEST_DEV);
            assertThatThrownBy(() -> ManifestEdit.parseScope("runtimes"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("runtimes")
                    .hasMessageContaining("runtime");
        } finally {
            Locale.setDefault(prev);
        }
    }

    private static Map<String, Object> edit(String dir, List<ManifestEdit.Op> ops, boolean apply) {
        return ManifestEdit.structured(
                ManifestEdit.run(new ManifestEdit.Request(Path.of(dir).resolve("jk.toml"), ops, !apply, false)));
    }

    private static Map<String, Object> setJava(String dir, int java, boolean apply) {
        return edit(dir, List.of(new ManifestEdit.Op.SetJava(java)), apply);
    }

    private static Map<String, Object> deps(
            String dir, String action, List<String> coords, String scope, boolean apply) {
        return edit(dir, ManifestEdit.coordOps(action, coords, scope), apply);
    }

    private static Map<String, Object> workspace(String dir, String action, String path, boolean apply) {
        return edit(
                dir,
                List.of(
                        "remove_member".equals(action)
                                ? new ManifestEdit.Op.RemoveWorkspaceModule(path)
                                : new ManifestEdit.Op.RegisterWorkspaceModule(path)),
                apply);
    }
}
