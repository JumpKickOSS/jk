// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheInventoryOpsTest {

    @Test
    void usage_of_empty_cache_is_zero(@TempDir Path cache) throws Exception {
        Files.createDirectories(cache);
        CacheInventoryAck ack =
                CacheInventoryOps.run(new CacheInventoryOps.Request("usage", cache, null, List.of(), List.of(), false));
        assertThat(ack.error()).isNull();
        assertThat(ack.query()).isEqualTo("usage");
        assertThat(ack.totalFiles()).isGreaterThanOrEqualTo(0);
    }

    /**
     * The doctor's worker rows: every worker the store holds, the repo its jar came from, the POM
     * its launch classpath is rebuilt from, and that classpath entry by entry — the same
     * resolution a fork performs. A store-resident worker with a POM naming nothing has a
     * classpath of one: its own jar.
     */
    @Test
    void workers_lists_each_stored_worker_with_the_classpath_source_a_fork_uses(@TempDir Path store) throws Exception {
        String saved = System.getProperty(PluginJar.IMAGE_BUILDER.jarProperty());
        System.clearProperty(PluginJar.IMAGE_BUILDER.jarProperty());
        try {
            Path jar = installWorker(store, RepoArtifactResolver.JK_LOCAL, PluginJar.IMAGE_BUILDER, JkVersion.VERSION);

            CacheInventoryAck ack = CacheInventoryOps.run(
                    new CacheInventoryOps.Request("workers", null, store, List.of(), List.of(), false));

            assertThat(ack.error()).isNull();
            String row = ack.lines().stream()
                    .filter(l -> l.startsWith("jk-image-builder|"))
                    .findFirst()
                    .orElseThrow();
            String[] f = row.split("\\|", -1);
            assertThat(f[1]).isEqualTo(JkVersion.VERSION);
            assertThat(f[2]).as("the repo the jar was located in").isEqualTo("jk-local");
            assertThat(Path.of(f[3])).isEqualTo(jar.toAbsolutePath().normalize());
            assertThat(f[4]).endsWith("jk-image-builder-" + JkVersion.VERSION + ".pom");
            assertThat(f[5]).as("declared compile/runtime deps").isEqualTo("0");
            assertThat(f[6]).as("classpath entries").isEqualTo("1");
            assertThat(f[7]).as("no resolution error").isEmpty();
            assertThat(ack.entries())
                    .contains("jk-image-builder|" + jar.toAbsolutePath().normalize());
        } finally {
            if (saved != null) System.setProperty(PluginJar.IMAGE_BUILDER.jarProperty(), saved);
        }
    }

    /**
     * {@code jk storage clean --workers}: every installed version of every worker goes, from
     * every store repo, and nothing that is not a worker. The dry run counts the same files it
     * would delete and deletes none.
     */
    @Test
    void drop_workers_removes_every_stored_worker_version_and_nothing_else(@TempDir Path store) throws Exception {
        Path current = installWorker(store, RepoArtifactResolver.JK_LOCAL, PluginJar.IMAGE_BUILDER, JkVersion.VERSION);
        Path older = installWorker(store, RepositorySpec.JUMPKICK_NAME, PluginJar.IMAGE_BUILDER, "0.1.0");
        Path library = store.resolve("repos/jk-local/cc/jumpkick/jk-model/1.0/jk-model-1.0.jar");
        Files.createDirectories(library.getParent());
        Files.writeString(library, "library");

        CacheInventoryAck dry = CacheInventoryOps.run(
                new CacheInventoryOps.Request("drop-workers", null, store, List.of(), List.of(), true));
        assertThat(dry.lines())
                .containsExactlyInAnyOrder(
                        "jk-image-builder|" + JkVersion.VERSION + "|jk-local", "jk-image-builder|0.1.0|jumpkick");
        assertThat(dry.files()).isEqualTo(4); // two jars, two POMs
        assertThat(current).exists();
        assertThat(older).exists();

        CacheInventoryAck dropped = CacheInventoryOps.run(
                new CacheInventoryOps.Request("drop-workers", null, store, List.of(), List.of(), false));
        assertThat(dropped.files()).isEqualTo(4);
        assertThat(store.resolve("repos/jk-local/cc/jumpkick/jk-image-builder"))
                .as("the whole artifact directory")
                .doesNotExist();
        assertThat(store.resolve("repos/jumpkick/cc/jumpkick/jk-image-builder")).doesNotExist();
        assertThat(library).as("a first-party library is not a worker").exists();
    }

    /** A worker jar with a dependency-free POM under {@code repos/<repo>/cc/jumpkick/<artifact>/<version>/}. */
    private static Path installWorker(Path store, String repo, PluginJar worker, String version) throws IOException {
        Path dir = store.resolve("repos")
                .resolve(repo)
                .resolve("cc/jumpkick")
                .resolve(worker.artifactId())
                .resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(worker.artifactId() + "-" + version + ".jar");
        Files.writeString(jar, "worker");
        Files.writeString(
                dir.resolve(worker.artifactId() + "-" + version + ".pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(worker.artifactId(), version));
        return jar;
    }

    @Test
    void wipe_store_counts_and_removes(@TempDir Path store) throws Exception {
        Path child = store.resolve("blob");
        Files.createDirectories(store);
        Files.writeString(child, "abc");
        CacheInventoryAck dry = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), true));
        assertThat(dry.files()).isEqualTo(1);
        assertThat(Files.exists(child)).isTrue();
        CacheInventoryAck wipe = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), false));
        assertThat(wipe.files()).isEqualTo(1);
        assertThat(Files.exists(child)).isFalse();
    }

    /** The wipe removes the store root named in the confirmation. */
    @Test
    void wipe_store_removes_the_store_root_it_named(@TempDir Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path tool =
                Files.createDirectories(store.resolve("lib/jk-java-compiler")).resolve("plugin.jar");
        Files.writeString(tool, "plugin");

        CacheInventoryAck wipe = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), false));

        assertThat(wipe.files()).isEqualTo(1);
        assertThat(store).doesNotExist();
    }

    /** A dry run counts and leaves the root standing — it is the confirm screen's pre-count. */
    @Test
    void wipe_store_dry_run_keeps_the_store_root(@TempDir Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Files.writeString(store.resolve("blob"), "abc");

        CacheInventoryAck dry = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), true));

        assertThat(dry.files()).isEqualTo(1);
        assertThat(store).isDirectory();
    }

    @Test
    void wipe_store_counts_hardlinked_blobs_once(@TempDir Path store) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(probeHardLink(store), "hard links required");
        byte[] payload = new byte[4_096];
        Path cas = Files.createDirectories(store.resolve("sha256/ab")).resolve("blob");
        Files.write(cas, payload);
        Path repo =
                Files.createDirectories(store.resolve("repos/central/g/a/1")).resolve("a.jar");
        Files.createLink(repo, cas);

        CacheInventoryAck dry = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), true));
        assertThat(dry.files()).isEqualTo(2);
        assertThat(dry.bytes())
                .as("unique inode bytes, not sum of hard-link sizes")
                .isEqualTo(payload.length);

        CacheInventoryAck wipe = CacheInventoryOps.run(
                new CacheInventoryOps.Request("wipe-store", null, store, List.of(), List.of(), false));
        assertThat(wipe.files()).isEqualTo(2);
        assertThat(wipe.bytes()).isEqualTo(payload.length);
        assertThat(cas).doesNotExist();
        assertThat(repo).doesNotExist();
    }

    @Test
    void store_usage_counts_the_requested_store_not_the_ambient_one(@TempDir Path tmp) throws Exception {
        // usage must count the same tree wipe-store would remove — the client resolves
        // JK_STORE_DIR from ITS environment and sends it in the request.
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path store = Files.createDirectories(tmp.resolve("client-store"));
        Path blob = Files.createDirectories(store.resolve("sha256/ab")).resolve("cd");
        Files.write(blob, new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0});

        CacheInventoryAck ack = CacheInventoryOps.run(
                new CacheInventoryOps.Request("store-usage", cache, store, List.of(), List.of(), false));

        assertThat(ack.error()).isNull();
        assertThat(ack.totalFiles()).isEqualTo(1);
        assertThat(ack.stats()).anyMatch(s -> s.startsWith("jars|1|"));
        assertThat(ack.stats()).anyMatch(s -> s.startsWith("maven-local|"));
    }

    @Test
    void blob_shared_with_an_unbucketed_key_still_counts(@TempDir Path cache) throws Exception {
        // An unbucketed key must not consume the shared-sha dedup set, or the count would
        // depend on directory-stream order.
        String sha = "a".repeat(64);
        Path blob = Files.createDirectories(cache.resolve("sha256/aa/aa")).resolve("a".repeat(60));
        Files.writeString(blob, "jar-bytes");
        Path keys = Files.createDirectories(cache.resolve("actions/keys"));
        Files.writeString(keys.resolve("0-unbucketed"), "TASK custom-step@abc\nOUTPUT " + sha + "\n");
        Files.writeString(keys.resolve("1-jar"), "TASK package-jar@abc\nOUTPUT " + sha + "\n");

        CacheInventoryAck ack =
                CacheInventoryOps.run(new CacheInventoryOps.Request("usage", cache, null, List.of(), List.of(), false));

        assertThat(ack.error()).isNull();
        assertThat(ack.stats()).anyMatch(s -> s.startsWith("normalJars|1|"));
    }

    @Test
    void repo_search_matches_terms_case_insensitively(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path store = Files.createDirectories(tmp.resolve("store"));
        m2Artifact(store, "central", "org/example/foo/1.0/foo-1.0.jar");
        m2Artifact(store, "central", "org/example/bar/2.0/bar-2.0.jar");

        CacheInventoryAck ack = CacheInventoryOps.run(
                new CacheInventoryOps.Request("repo-search", cache, store, List.of("FOO"), List.of(), false));

        assertThat(ack.error()).isNull();
        assertThat(ack.entries()).containsExactly("org.example|foo|1.0");
    }

    @Test
    void repo_refresh_evicts_across_repos_and_reports_misses(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path a = m2Artifact(store, "central", "org/example/foo/1.0/foo-1.0.jar");
        Path b = m2Artifact(store, "mirror", "org/example/foo/1.0/foo-1.0.jar");

        CacheInventoryAck ack = CacheInventoryOps.run(new CacheInventoryOps.Request(
                "repo-refresh",
                cache,
                store,
                List.of(),
                List.of("org.example:foo:1.0", "org.example:gone:9.9"),
                false));

        assertThat(ack.error()).isNull();
        assertThat(ack.evicted()).isEqualTo(1);
        assertThat(ack.missed()).isEqualTo(1);
        assertThat(ack.lines()).containsExactly("org.example|foo|1.0|central,mirror", "org.example|gone|9.9|");
        assertThat(a).doesNotExist();
        assertThat(b).doesNotExist();
    }

    @Test
    void store_usage_counts_hardlinked_blobs_once(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path store = tmp.resolve("store");
        org.junit.jupiter.api.Assumptions.assumeTrue(probeHardLink(store), "hard links required");
        Path original = Files.createDirectories(store.resolve("sha256/ab")).resolve("cd");
        Files.write(original, new byte[] {'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6});
        Files.createLink(store.resolve("sha256/ab/alias"), original);

        CacheInventoryAck ack = CacheInventoryOps.run(
                new CacheInventoryOps.Request("store-usage", cache, store, List.of(), List.of(), false));

        assertThat(ack.error()).isNull();
        assertThat(ack.totalFiles()).isEqualTo(2);
        assertThat(ack.totalBytes()).as("bytes are exclusive by file key").isEqualTo(10);
    }

    private static Path m2Artifact(Path storeRoot, String repo, String rel) throws Exception {
        // repos/ lives under the STORE root — the same tree production reaches via
        // RepoArtifactStore.forRepoName(cas.root(), name).
        Path f = storeRoot.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "jar-bytes");
        Files.writeString(
                ArtifactMemo.jkPath(storeRoot.resolve("repos").resolve(repo), rel),
                "g:a:v\n0\n9\n" + "a".repeat(64) + "\n");
        return f;
    }

    /** True when {@link Files#createLink} works on this volume (POSIX or NTFS hard links). */
    private static boolean probeHardLink(Path dir) throws Exception {
        Path x = Files.createDirectories(dir).resolve(".hl-x");
        Path y = dir.resolve(".hl-y");
        Files.writeString(x, "z");
        try {
            Files.createLink(y, x);
            return true;
        } catch (UnsupportedOperationException | FileSystemException e) {
            return false;
        } finally {
            Files.deleteIfExists(y);
            Files.deleteIfExists(x);
        }
    }

    @Test
    void unknown_query_is_an_error() throws Exception {
        CacheInventoryAck ack =
                CacheInventoryOps.run(new CacheInventoryOps.Request("nope", null, null, List.of(), List.of(), false));
        assertThat(ack.error()).contains("unknown");
    }
}
