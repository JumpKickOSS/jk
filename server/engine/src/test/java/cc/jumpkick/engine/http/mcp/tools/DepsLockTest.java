// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.runtime.RepoFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code deps} writes jk.toml and leaves a lock the next build can trust; a version no configured
 * repository serves is refused, preview or not.
 */
class DepsLockTest {

    @Test
    void remove_relocks_so_the_lock_matches_the_manifest(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        LockFlow.Result first = LockFlow.run(dir, dir.resolve("cache"), List.of(), false, null);
        assertThat(first.status()).isZero();
        assertThat(LockFreshness.needsRefresh(dir)).isFalse();

        Map<String, Object> added =
                McpManifest.deps(dir.toString(), "add", List.of("com.acme:thing:1.0.0"), "main", true);
        assertThat(added.get("applied")).isEqualTo(true);
        assertThat(LockFreshness.needsRefresh(dir)).isTrue();

        Map<String, Object> result = new DepsTool()
                .call(new McpCall(
                        context(),
                        Map.of("action", "remove", "coords", List.of("com.acme:thing"), "dir", dir.toString()),
                        null));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>)
                Objects.requireNonNull((List<?>) result.get("content")).getFirst();
        String text = String.valueOf(row.get("text"));
        assertThat(text).startsWith("remove thing\n").contains("lock ok");
        assertThat(LockFreshness.needsRefresh(dir)).isFalse();
        assertThat(Files.readString(dir.resolve("jk.toml"))).doesNotContain("com.acme:thing");
    }

    @Test
    void a_preview_of_a_version_no_repository_serves_says_so(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        String before = Files.readString(dir.resolve("jk.toml"));
        Map<String, Object> missing = new DepsTool()
                .call(new McpCall(
                        context(),
                        Map.of(
                                "action",
                                "add",
                                "coords",
                                List.of("com.acme:thing:4.0.2"),
                                "dir",
                                dir.toString(),
                                "preview",
                                true),
                        null));
        assertThat(missing.get("isError")).isEqualTo(true);
        assertThat(text(missing))
                .isEqualTo("no com.acme:thing:4.0.2 in the configured repositories; the newest release is 1.0.0\n");

        Map<String, Object> found = new DepsTool()
                .call(new McpCall(
                        context(),
                        Map.of(
                                "action",
                                "add",
                                "coords",
                                List.of("com.acme:thing:1.0.0"),
                                "dir",
                                dir.toString(),
                                "preview",
                                true),
                        null));
        assertThat(text(found)).isEqualTo("add com.acme:thing:1.0.0\npreview\n");
        assertThat(Files.readString(dir.resolve("jk.toml"))).isEqualTo(before);
    }

    /** A project whose only repository is a local one serving {@code com.acme:thing:1.0.0}. */
    private static Path project(Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        RepoFixtures.module(repo, "com.acme", "thing", "1.0.0");
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "0.1.0"

                [repositories.local]
                url = "%s"
                groups = ["com.acme"]
                """.formatted(repo.toUri()));
        return dir;
    }

    private static String text(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>)
                Objects.requireNonNull((List<?>) result.get("content")).getFirst();
        return String.valueOf(row.get("text"));
    }

    private static McpContext context() {
        EngineHttpJobs jobs = new EngineHttpJobs() {
            @Override
            public long trigger(JobSpec spec) {
                return 1L;
            }

            @Override
            public boolean cancel(long jid) {
                return false;
            }

            @Override
            public int cancelDir(String dir) {
                return 0;
            }
        };
        return new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                jobs,
                d -> Map.of(),
                List::of,
                "0",
                null,
                null,
                null,
                null);
    }
}
