// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.CacheInventoryAck;
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

    @Test
    void store_usage_counts_the_requested_store_not_the_ambient_one(@TempDir Path tmp) throws Exception {
        // JK-2161: usage must count the same tree wipe-store would remove — the client
        // resolves JK_STORE_DIR from ITS environment and sends it in the request.
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path store = Files.createDirectories(tmp.resolve("client-store"));
        Path blob = Files.createDirectories(store.resolve("sha256/ab")).resolve("cd");
        Files.write(blob, new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0});

        CacheInventoryAck ack = CacheInventoryOps.run(
                new CacheInventoryOps.Request("store-usage", cache, store, List.of(), List.of(), false));

        assertThat(ack.error()).isNull();
        assertThat(ack.totalFiles()).isEqualTo(1);
        assertThat(ack.stats()).anyMatch(s -> s.startsWith("jars|1|"));
    }

    @Test
    void blob_shared_with_an_unbucketed_key_still_counts(@TempDir Path cache) throws Exception {
        // JK-2161: an unbucketed key must not consume the shared-sha dedup set, or the
        // count would depend on directory-stream order.
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
                "repo-refresh", cache, store, List.of(), List.of("org.example:foo:1.0", "org.example:gone:9.9"), false));

        assertThat(ack.error()).isNull();
        assertThat(ack.evicted()).isEqualTo(1);
        assertThat(ack.missed()).isEqualTo(1);
        assertThat(ack.lines())
                .containsExactly("org.example|foo|1.0|central,mirror", "org.example|gone|9.9|");
        assertThat(a).doesNotExist();
        assertThat(b).doesNotExist();
    }

    @Test
    void store_usage_counts_hardlinked_blobs_once(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path store = tmp.resolve("store");
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
        // repos/ lives under the STORE root (JK-2176) — the same tree production reaches
        // via RepoArtifactStore.forRepoName(cas.root(), name).
        Path f = storeRoot.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "jar-bytes");
        Files.writeString(f.resolveSibling(f.getFileName() + ".sha256"), "abc");
        return f;
    }

    @Test
    void unknown_query_is_an_error() throws Exception {
        CacheInventoryAck ack =
                CacheInventoryOps.run(new CacheInventoryOps.Request("nope", null, null, List.of(), List.of(), false));
        assertThat(ack.error()).contains("unknown");
    }
}
