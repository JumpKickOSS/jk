// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Host calibration must not invent a fourth Maven tree.
 *
 * <p>The optional JUnit Platform micro-benchmark needs seven pinned Jupiter jars.
 * {@code ArtifactLocator} resolves the Maven local repository and then {@code <store>/repos/};
 * {@code JkStores.cas} ignores the cache root. These tests drive that route with ambient roots
 * redirected at a {@link TempDir}, pinning which root the probe chose. The Central transport is
 * injected so the fetch-and-publish path runs.
 */
class HardwareProbeStoreTest {

    /** The pinned set, spelled out: a golden of the coordinates calibration compiles against. */
    private static final List<String> PINNED = List.of(
            "org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar",
            "org/junit/jupiter/junit-jupiter-engine/5.11.4/junit-jupiter-engine-5.11.4.jar",
            "org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar",
            "org/junit/platform/junit-platform-engine/1.11.4/junit-platform-engine-1.11.4.jar",
            "org/junit/platform/junit-platform-commons/1.11.4/junit-platform-commons-1.11.4.jar",
            "org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar",
            "org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar");

    private final Map<String, String> saved = new HashMap<>();

    @AfterEach
    void restoreAmbientRoots() {
        saved.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
        saved.clear();
    }

    private void set(String key, String value) {
        saved.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /** Point the artifact store, the Maven local repository and the cache root at this test's dirs. */
    private void redirectRoots(Path store, Path m2, Path cache) {
        set("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        set("jk.m2.local", m2.toAbsolutePath().toString());
        set("jk.env.JK_CACHE_DIR", cache.toAbsolutePath().toString());
    }

    @Test
    void the_pinned_set_resolves_out_of_the_artifact_store(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        redirectRoots(store, tmp.resolve("m2"), cache);
        Path central = store.resolve("repos").resolve(RepositorySpec.CENTRAL);
        for (String rel : PINNED) write(central.resolve(rel), jarBytes(rel));

        List<Path> cp = HardwareProbe.resolveJunitClasspath(HardwareProbe.Options.offline());

        assertThat(cp).hasSameSizeAs(PINNED).allSatisfy(p -> assertThat(p).startsWith(central));
        assertThat(cache)
                .as("a calibration that reads the store has no reason to mint a cache root")
                .doesNotExist();
    }

    @Test
    void the_pinned_set_resolves_out_of_the_maven_local_repository(@TempDir Path tmp) throws Exception {
        Path m2 = tmp.resolve("m2");
        Path cache = tmp.resolve("cache");
        redirectRoots(tmp.resolve("store"), m2, cache);
        for (String rel : PINNED) write(m2.resolve(rel), jarBytes(rel));

        List<Path> cp = HardwareProbe.resolveJunitClasspath(HardwareProbe.Options.offline());

        assertThat(cp).hasSameSizeAs(PINNED).allSatisfy(p -> assertThat(p).startsWith(m2));
        assertThat(cache).doesNotExist();
    }

    /**
     * The headline: a networked probe on a cold machine. Every jar has to land in the store as a
     * full entry — artifact plus {@code .jk} memo, which is what makes it a thing a later resolve
     * can hash-verify — and the cache root must still not exist afterwards.
     */
    @Test
    void a_fetched_jar_lands_in_the_store_with_a_memo_and_never_under_the_cache_root(@TempDir Path tmp)
            throws Exception {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        redirectRoots(store, tmp.resolve("m2"), cache);
        Fake central = new Fake(PINNED, true);

        List<Path> cp = HardwareProbe.resolveJunitClasspath(HardwareProbe.Options.of(true), central);

        Path centralRepo = store.resolve("repos").resolve(RepositorySpec.CENTRAL);
        assertThat(cp).hasSameSizeAs(PINNED).allSatisfy(p -> assertThat(p).startsWith(centralRepo));
        RepoArtifactStore repo = RepoArtifactStore.forRepoName(store, RepositorySpec.CENTRAL);
        for (String rel : PINNED) {
            assertThat(repo.contains(rel))
                    .as("%s stored without a .jk memo is an orphan no resolve will trust", rel)
                    .isTrue();
            assertThat(repo.verify(rel, Hashing.sha256Hex(jarBytes(rel))))
                    .isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
        }
        assertThat(cache)
                .as("calibration downloads are artifacts, not cache: nuking the cache must not lose them")
                .doesNotExist();
        assertThat(central.urls).hasSize(2 * PINNED.size()); // jar + .sha1 each
    }

    @Test
    void an_offline_probe_with_nothing_on_disk_is_skipped_rather_than_writing_anywhere(@TempDir Path tmp) {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        redirectRoots(store, tmp.resolve("m2"), cache);

        assertThat(HardwareProbe.resolveJunitClasspath(HardwareProbe.Options.offline()))
                .isEmpty();

        assertThat(cache).doesNotExist();
        assertThat(store.resolve("repos").resolve(RepositorySpec.CENTRAL)).doesNotExist();
    }

    /** A store entry outlives the process that wrote it, so unverified bytes must never become one. */
    @Test
    void a_checksum_mismatch_stores_nothing(@TempDir Path tmp) {
        Path store = tmp.resolve("store");
        Path cache = tmp.resolve("cache");
        redirectRoots(store, tmp.resolve("m2"), cache);

        assertThat(HardwareProbe.resolveJunitClasspath(HardwareProbe.Options.of(true), new Fake(PINNED, false)))
                .isEmpty();

        assertThat(RepoArtifactStore.forRepoName(store, RepositorySpec.CENTRAL).contains(PINNED.get(0)))
                .isFalse();
        assertThat(cache).doesNotExist();
    }

    // ---- fixture ------------------------------------------------------------

    /** Deterministic stand-in bytes; the probe only ever hashes and stores them. */
    private static byte[] jarBytes(String relativeMavenPath) {
        return ("PK " + relativeMavenPath).getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Path file, byte[] body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, body);
    }

    /** Central over a map, recording every URL asked for. */
    private static final class Fake implements HardwareProbe.CentralFetch {
        private final Map<String, byte[]> served = new HashMap<>();
        private final List<String> urls = new ArrayList<>();

        Fake(List<String> pinned, boolean honestChecksums) {
            for (String rel : pinned) {
                byte[] body = jarBytes(rel);
                served.put(HardwareProbe.CENTRAL_BASE + rel, body);
                String sha1 = honestChecksums ? Hashing.hashHex("SHA-1", body) : "0".repeat(40);
                served.put(HardwareProbe.CENTRAL_BASE + rel + ".sha1", sha1.getBytes(StandardCharsets.US_ASCII));
            }
        }

        @Override
        public byte[] get(String url) {
            urls.add(url);
            return served.get(url);
        }
    }
}
