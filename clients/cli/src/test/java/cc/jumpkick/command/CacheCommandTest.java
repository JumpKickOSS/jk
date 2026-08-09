// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class CacheCommandTest {

    @Test
    void dir_prints_cache_root(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "dir", "--cache-dir", cache.toString()));
        assertThat(stdout.trim()).isEqualTo(cache.toString());
    }

    @Test
    void storage_summarizes_an_empty_action_cache_without_creating_it(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "storage", "--cache-dir", cache.toString()));
        assertThat(stdout).contains("not yet created");
        assertThat(Files.exists(cache)).isFalse();
    }

    @Test
    void storage_reports_cache_tier_including_cache_cas(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        // Cache CAS under the same root is part of the cache tier (not the artifact store report).
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), "hello".getBytes(StandardCharsets.UTF_8));
        writeBlob(cache.resolve("actions/keys/some-task"), new byte[2048]);

        String plain = TestAnsi.strip(capture(() -> run("cache", "storage", "--cache-dir", cache.toString())));
        assertThat(plain).contains("Cache Storage");
        // action key + cache CAS blob (+ intermediate dirs may vary by DiskUsage walk)
        assertThat(plain).contains("Utilization");
        assertThat(plain).doesNotContain("CAS Blobs");
        assertThat(plain).doesNotContain("Worker JARs");
        // Size includes the 2 KiB action record (cache CAS "hello" is small).
        assertThat(plain).containsPattern("File Count:\\s*[1-9]");
    }

    @Test
    void prune_removes_stale_action_entries_and_cache_tier_tmp_files(@TempDir Path tempDir) throws Exception {
        // JK-1531: post-split, plain `jk cache clean` is CACHE-tier only. Its temp janitor runs
        // on the cache root's sha256/ (the cache CAS); the artifact store's temps belong to the
        // store sweep (`jk storage clean` / scheduled --sweep) and must survive a plain prune.
        Path cache = tempDir.resolve("cache");
        Path stale = writeBlob(cache.resolve("actions/keys/old"), new byte[256]);
        Path fresh = writeBlob(cache.resolve("actions/keys/new"), new byte[256]);
        Path cacheTmp = writeBlob(cache.resolve("sha256/ab/cd/.put-abc.tmp"), new byte[128]);
        Path storeCas = cc.jumpkick.cache.JkStores.resolve(cache, "sha256");
        Path storeTmp = writeBlob(storeCas.resolve("ab/cd/.put-jk1531.tmp"), new byte[128]);
        try {
            // Backdate the stale entry by 60 days.
            Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

            String stdout = capture(() -> run("cache", "clean", "--cache-dir", cache.toString(), "--older-than", "30"));

            assertThat(Files.exists(stale)).isFalse();
            assertThat(Files.exists(fresh)).isTrue();
            assertThat(Files.exists(cacheTmp)).isFalse(); // cache-tier temp: cleaned
            assertThat(Files.exists(storeTmp)).isTrue(); // store-tier temp: not this command's job
            // New summary format breaks the count out by step.
            assertThat(stdout).contains("Finished cleaning cache").contains("removed");
        } finally {
            Files.deleteIfExists(storeTmp); // do not pollute the module-shared store
        }
    }

    @Test
    void prune_dry_run_does_not_delete(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path stale = writeBlob(cache.resolve("actions/keys/old"), new byte[1024]);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        String stdout = capture(() -> run("cache", "clean", "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(Files.exists(stale)).isTrue();
        assertThat(stdout).contains("Dry run: would remove");
    }

    @Test
    void purge_wipes_cache_tier_but_keeps_repos_and_runs(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        // Cache CAS (sha256/) is cache-tier; repos/ and runs/ stay when collocated under --cache-dir.
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);
        writeBlob(cache.resolve("repos/central/com/example/lib/1.0/lib-1.0.jar"), new byte[512]);
        writeBlob(cache.resolve("runs/build-1.jsonl"), new byte[256]);
        writeBlob(cache.resolve("actions/keys/task1"), new byte[1024]);
        writeBlob(cache.resolve("format-stamps/ab/stamp1"), new byte[128]);

        String stdout = capture(() -> run("cache", "nuke", "--cache-dir", cache.toString(), "--yes"));

        assertThat(stdout).contains("Nuked");
        assertThat(Files.exists(cache.resolve("actions/keys/task1"))).isFalse();
        assertThat(Files.exists(cache.resolve("format-stamps/ab/stamp1"))).isFalse();
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isFalse();
        assertThat(Files.exists(cache.resolve("repos/central/com/example/lib/1.0/lib-1.0.jar")))
                .isTrue();
        assertThat(Files.exists(cache.resolve("runs/build-1.jsonl"))).isTrue();
        assertThat(Files.exists(cache)).isTrue();
    }

    @Test
    void purge_aborts_when_not_confirmed(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("actions/keys/task1"), new byte[4096]);

        String stdout = withStdin("n\n", () -> capture(() -> run("cache", "nuke", "--cache-dir", cache.toString())));

        assertThat(stdout).contains("aborted");
        assertThat(Files.exists(cache.resolve("actions/keys/task1"))).isTrue();
    }

    @Test
    void purge_proceeds_on_yes_at_the_prompt(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("actions/keys/task1"), new byte[4096]);

        String stdout = withStdin("y\n", () -> capture(() -> run("cache", "nuke", "--cache-dir", cache.toString())));

        assertThat(stdout).contains("Nuked 1 files");
        assertThat(Files.exists(cache.resolve("actions/keys/task1"))).isFalse();
    }

    @Test
    void purge_dry_run_reports_without_deleting_or_prompting(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("actions/keys/task1"), new byte[4096]);

        String stdout = capture(() -> run("cache", "nuke", "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(stdout).contains("Dry run: would remove");
        assertThat(Files.exists(cache.resolve("actions/keys/task1"))).isTrue();
    }

    @Test
    void purge_missing_cache_dir_is_a_noop(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "nuke", "--cache-dir", cache.toString(), "--yes"));
        assertThat(stdout).contains("Nothing to nuke");
    }

    @Test
    void purge_with_only_cache_cas_removes_blobs(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        // Cache CAS alone is still cache-tier content — purge removes it.
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = capture(() -> run("cache", "nuke", "--cache-dir", cache.toString(), "--yes"));

        assertThat(stdout).contains("Nuked");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isFalse();
    }

    @Test
    void repo_prune_dry_run_sweeps_the_store_side(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("actions/keys/task1"), new byte[1024]);

        String stdout = capture(() -> run("storage", "clean", "--cache-dir", cache.toString(), "--dry-run"));

        // op "sweep" round-trips the engine; dry run must not touch the action cache.
        assertThat(stdout).contains("Dry run");
        assertThat(Files.exists(cache.resolve("actions/keys/task1"))).isTrue();
    }

    @Test
    void repo_search_lists_cached_coordinates_with_versions(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        seedRepo(cache, "com.fasterxml.jackson.core", "jackson-databind", "2.18.2");
        seedRepo(cache, "com.fasterxml.jackson.core", "jackson-databind", "2.17.1");
        seedRepo(cache, "com.google.guava", "guava", "33.0.0-jre");

        // Coordinates print in color; strip ANSI to assert on the visible text.
        String stdout =
                TestAnsi.strip(capture(() -> run("repo", "search", "jackson", "--cache-dir", cache.toString())));

        assertThat(stdout).contains("com.fasterxml.jackson.core:jackson-databind");
        // newest-first version ordering
        assertThat(stdout).contains("2.18.2, 2.17.1");
        assertThat(stdout).doesNotContain("guava");
        assertThat(stdout).contains("1 coordinate, 2 versions cached");
    }

    @Test
    void repo_search_with_no_matches_returns_nonzero(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        int exit = run("repo", "search", "nonexistent", "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void repo_storage_reports_cas_and_repos_without_action_cache(@TempDir Path tempDir) throws Exception {
        // JK-1531: since the CAS split, `jk storage` measures the AMBIENT artifact store —
        // `--cache-dir` moves only the cache tier — so exact byte totals depend on whatever the
        // module-shared store holds and cannot be asserted here. Structural shape only; the
        // hard-link no-double-count arithmetic is covered hermetically by
        // DiskUsageTest.exclusive_does_not_double_count_hardlinked_cas_and_repos.
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("actions/keys/task"), new byte[4096]);

        String plain = TestAnsi.strip(capture(() -> run("repo", "storage", "--cache-dir", cache.toString())));
        assertThat(plain).contains("Repo Storage");
        assertThat(plain).contains("CAS Blobs");
        assertThat(plain).contains("Worker JARs");
        assertThat(plain).contains("Run Logs");
        assertThat(plain).contains("Total");
        assertThat(plain).contains("Utilization");
        assertThat(plain).doesNotContain("Action Cache");
    }

    // --- helpers -----------------------------------------------------------

    /** Minimal buildable project manifest. */
    private static void writeProject(Path dir, String group, String name, String version) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "%s"
                name  = "%s"
                version = "%s"
                java = 25
                """.formatted(group, name, version), StandardCharsets.UTF_8);
    }

    /** The qualified-task tag the build would use for {@code projectDir}'s main classes dir. */
    private static String classesTag(Path projectDir) throws Exception {
        // BuildCommand realpaths the module root before hashing tags — match that form.
        Path norm = projectDir.toRealPath();
        cc.jumpkick.model.JkBuild jb = cc.jumpkick.config.JkBuildParser.parse(norm.resolve("jk.toml"));
        return cc.jumpkick.task.ActionKey.taskTag(
                cc.jumpkick.layout.BuildLayout.of(norm, jb).classesDir());
    }

    /** Write an action record ({@code keys/<key>}) plus its {@code tasks/<taskId>} pointer. */
    private static void seedRecord(Path cache, String key, String taskId, String extraInputLine) throws Exception {
        Path keyFile = cache.resolve("actions/keys").resolve(key);
        Files.createDirectories(keyFile.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(taskId).append('\n');
        sb.append("KEY ").append(key).append('\n');
        if (extraInputLine != null) sb.append(extraInputLine).append('\n');
        sb.append("OUTPUT deadbeef foo.class\n");
        Files.writeString(keyFile, sb.toString());
        Path ptr = cache.resolve("actions/tasks").resolve(taskId);
        Files.createDirectories(ptr.getParent());
        Files.writeString(ptr, key);
    }

    /** Materialise a jar for {@code group:artifact:version} into the "central" named-repo store. */
    private static void seedRepo(Path cache, String group, String artifact, String version) {
        try {
            byte[] bytes = (group + ":" + artifact + ":" + version).getBytes(StandardCharsets.UTF_8);
            cc.jumpkick.cache.Cas cas = new cc.jumpkick.cache.Cas(cache);
            Path blob = cas.put(bytes);
            var coord = cc.jumpkick.model.Coordinate.of(group, artifact, version);
            cc.jumpkick.repo.RepoArtifactStore.forRepoName(cache, "central")
                    .materialize(
                            cc.jumpkick.repo.MavenLayout.artifactPath(coord),
                            blob,
                            cc.jumpkick.util.Hashing.sha256Hex(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path writeBlob(Path file, byte[] body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, body);
        return file;
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    /** Run {@code body} with {@code System.in} fed from {@code input}. */
    private static String withStdin(String input, Supplier<String> body) {
        var original = System.in;
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        try {
            return body.get();
        } finally {
            System.setIn(original);
        }
    }

    private static String capture(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
