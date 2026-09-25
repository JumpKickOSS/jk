// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code update}: a preview lists the pin moves and the proposed manifest without writing;
 * apply writes {@code jk.toml} and relocks. Candidates come from the project's own declared
 * repositories.
 */
@Tag("integration")
class McpUpdateTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path isolated;

    @BeforeEach
    void isolate() {
        System.setProperty("jk.env.JK_STORE_DIR", isolated.resolve("store").toString());
        System.setProperty("jk.env.JK_CACHE_DIR", isolated.resolve("cache").toString());
    }

    @AfterEach
    void release() {
        System.clearProperty("jk.env.JK_STORE_DIR");
        System.clearProperty("jk.env.JK_CACHE_DIR");
        LockfileReader.clearCache();
    }

    @Test
    void preview_reports_the_moves_without_writing_and_apply_writes_and_relocks(@TempDir Path dir) throws Exception {
        project(dir);

        Map<String, Object> preview = McpUpdate.run(dir.toString(), List.of(), false, false);
        assertThat(preview).doesNotContainKey("error");
        assertThat(preview.get("applied")).isEqualTo(false);
        assertThat(preview.get("changed")).isEqualTo(true);
        assertThat(rows(preview, "rewrites"))
                .extracting(r -> r.get("handle"), r -> r.get("from"), r -> r.get("to"))
                .containsExactlyInAnyOrder(tuple("jackson", "2.18.0", "2.18.2"), tuple("other", "1.0.0", "1.1.0"));
        assertThat(String.valueOf(preview.get("preview"))).contains("jackson = \"com.acme:jackson:2.18.2\"");
        assertThat(rows(preview, "files"))
                .extracting(r -> r.get("path"))
                .containsExactly(dir.resolve("jk.toml").toString());
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("com.acme:jackson:2.18.0");
        assertThat(dir.resolve("jk-lock.toml")).doesNotExist();

        Map<String, Object> applied = McpUpdate.run(dir.toString(), List.of("jackson"), false, true);
        assertThat(applied).doesNotContainKey("error");
        assertThat(applied.get("applied")).isEqualTo(true);
        assertThat(rows(applied, "rewrites")).extracting(r -> r.get("handle")).containsExactly("jackson");
        assertThat(Files.readString(dir.resolve("jk.toml")))
                .contains("jackson = \"com.acme:jackson:2.18.2\"")
                .contains("other = \"com.acme:other:1.0.0\"");
        @SuppressWarnings("unchecked")
        Map<String, Object> lock = (Map<String, Object>) requireNonNull(applied.get("lock"));
        assertThat(lock.get("success")).isEqualTo(true);
        assertThat(locked(dir, "com.acme:jackson")).isEqualTo("2.18.2");
        assertThat(locked(dir, "com.acme:other")).isEqualTo("1.0.0");
        assertThat(rows(lock, "changes"))
                .as("the first lock adds every package")
                .allSatisfy(r -> assertThat(r.get("from")).isNull());

        Map<String, Object> other = McpUpdate.run(dir.toString(), List.of("other"), false, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> relock = (Map<String, Object>) requireNonNull(other.get("lock"));
        assertThat(relock.get("updated")).isEqualTo(1);
        assertThat(rows(relock, "changes"))
                .extracting(r -> r.get("coordinate"), r -> r.get("from"), r -> r.get("to"))
                .containsExactly(tuple("com.acme:other", "1.0.0", "1.1.0"));

        Map<String, Object> major = McpUpdate.run(dir.toString(), List.of("jackson"), true, false);
        assertThat(rows(major, "rewrites")).extracting(r -> r.get("to")).containsExactly("3.0.0");
    }

    @Test
    void the_tool_is_registered_and_previews_by_default(@TempDir Path dir) throws Exception {
        project(dir);
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
            public int cancelDir(String d) {
                return 0;
            }
        };
        McpHandler mcp = new McpHandler(
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
                jobs,
                d -> Map.of(),
                List::of,
                "0.12.0");
        String reply = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"update\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"deps\":[\"other\"]}}}");
        assertThat(reply)
                .contains("\"type\":\"update\"")
                .contains("\"applied\":false")
                .contains("\"to\":\"1.1.0\"");
        assertThat(reply).doesNotContain("2.18.2");
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("com.acme:other:1.0.0");
    }

    // ---- fixture ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) requireNonNull(data.get(key));
    }

    private static String locked(Path project, String module) throws IOException {
        LockfileReader.clearCache();
        Lockfile lock = LockfileReader.read(project.resolve("jk-lock.toml"));
        return lock.artifacts().stream()
                .filter(a -> a.matchesModule(module))
                .map(Lockfile.Artifact::version)
                .findFirst()
                .orElseThrow(() -> new AssertionError(module + " not in the lock"));
    }

    private void project(Path dir) throws IOException {
        MavenStub upstream = new MavenStub(http);
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        publish(upstream, "jackson", "2.18.0", "2.18.2", "3.0.0");
        publish(upstream, "other", "1.0.0", "1.1.0");
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [repositories]
                central = "%s"

                [dependencies]
                jackson = "com.acme:jackson:2.18.0"
                other = "com.acme:other:1.0.0"
                """.formatted(http.baseUrl()));
    }

    private static void publish(MavenStub upstream, String artifact, String... versions) {
        for (String v : versions) {
            upstream.pom("com.acme", artifact, v, MavenStub.emptyPom("com.acme", artifact, v));
        }
        upstream.metadata("com.acme", artifact, versions);
    }
}
