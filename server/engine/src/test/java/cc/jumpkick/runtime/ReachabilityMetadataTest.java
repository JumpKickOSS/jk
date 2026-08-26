// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the GraalVM reachability-metadata repository lands, and which release lands there.
 *
 * <p>Both were wrong in ways nothing could see. The extracted tree was a <em>cache tier</em>, so
 * {@code jk cache nuke} — a command whose whole promise is "rebuildable bytes" — took 27 MB that
 * only Maven Central can give back. And the release was {@code ReachabilityMetadata.VERSION}, a
 * compile-time constant: which reflection config a native image kept was decided by the jk binary
 * that happened to run, recorded in no lock and choosable by no user.
 */
class ReachabilityMetadataTest {

    @BeforeEach
    void resetRepoMemos() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void the_matched_dir_native_image_is_handed_resolves_under_the_store(@TempDir Path tmp) throws Exception {
        Fixture f = new Fixture(tmp);
        f.publish("1.2.3", "com.acme", "widgets", "1", "4.5.6");

        List<String> log = new ArrayList<>();
        List<Path> dirs = ReachabilityMetadata.configDirs(
                f.store,
                f.repos(),
                pin("1.2.3", f.sha("1.2.3")),
                List.of(artifact("com.acme:widgets", "4.5.6")),
                log::add);

        // The exact path that goes into -H:ConfigurationFileDirectories.
        assertThat(dirs).containsExactly(configDir(f, "1.2.3", "1"));
        assertThat(log).anyMatch(l -> l.contains("com.acme:widgets@4.5.6"));
    }

    /**
     * The property the move to the store buys: {@code jk cache nuke} is {@code rm -rf} of the cache
     * root, and no part of the extracted repository is under it. Proven functionally, not by
     * inspecting a path — the cache root and the remote both go, and the next build still gets its
     * config dirs.
     */
    @Test
    void a_nuked_cache_costs_no_redownload(@TempDir Path tmp) throws Exception {
        Fixture f = new Fixture(tmp);
        f.publish("1.2.3", "com.acme", "widgets", "1", "4.5.6");
        Lockfile.NativeMetadata pinned = pin("1.2.3", f.sha("1.2.3"));

        assertThat(ReachabilityMetadata.configDirs(
                        f.store, f.repos(), pinned, List.of(artifact("com.acme:widgets", "4.5.6")), l -> {}))
                .hasSize(1);

        // A nuke: the whole cache root, exactly as CacheCommand.removeCacheRoot leaves it.
        Path extracted = ReachabilityMetadata.repositoryRoot(f.store, "1.2.3");
        assertThat(CacheTree.cachedUnder(f.cache))
                .as("no cache tier may contain the extracted repository")
                .noneMatch(extracted::startsWith);
        PathUtil.deleteRecursively(f.cache);
        // …and the remote is gone too, so anything that re-resolves fails rather than silently
        // re-fetching.
        PathUtil.deleteRecursively(f.remote);
        RepoGroup.clearProcessFetchCache();

        List<String> log = new ArrayList<>();
        assertThat(ReachabilityMetadata.configDirs(
                        f.store, f.repos(), pinned, List.of(artifact("com.acme:widgets", "4.5.6")), log::add))
                .containsExactly(configDir(f, "1.2.3", "1"));
        assertThat(log).noneMatch(l -> l.contains("unavailable"));
    }

    /** The release is the locked one, so two locks extract two trees and neither is a constant. */
    @Test
    void the_locked_release_is_the_one_extracted(@TempDir Path tmp) throws Exception {
        Fixture f = new Fixture(tmp);
        f.publish("1.1.4", "com.acme", "widgets", "old", "4.5.6");
        f.publish("2.0.0", "com.acme", "widgets", "new", "4.5.6");

        assertThat(ReachabilityMetadata.configDirs(
                        f.store,
                        f.repos(),
                        pin("1.1.4", f.sha("1.1.4")),
                        List.of(artifact("com.acme:widgets", "4.5.6")),
                        l -> {}))
                .containsExactly(configDir(f, "1.1.4", "old"));
        assertThat(ReachabilityMetadata.configDirs(
                        f.store,
                        f.repos(),
                        pin("2.0.0", f.sha("2.0.0")),
                        List.of(artifact("com.acme:widgets", "4.5.6")),
                        l -> {}))
                .containsExactly(configDir(f, "2.0.0", "new"));
    }

    /** No pin in the lock is not "guess a version": it is a build without metadata, and a nudge. */
    @Test
    void an_unpinned_lock_builds_without_metadata(@TempDir Path tmp) throws Exception {
        Fixture f = new Fixture(tmp);
        f.publish("1.2.3", "com.acme", "widgets", "1", "4.5.6");

        List<String> log = new ArrayList<>();
        assertThat(ReachabilityMetadata.configDirs(
                        f.store, f.repos(), null, List.of(artifact("com.acme:widgets", "4.5.6")), log::add))
                .isEmpty();
        assertThat(log).anySatisfy(l -> assertThat(l).contains("jk lock"));
        assertThat(f.store.resolve("native")).doesNotExist();
    }

    /** A zip that is not the one the lock pinned is not unpacked, and the build says so. */
    @Test
    void a_checksum_the_lock_did_not_pin_refuses_the_extract(@TempDir Path tmp) throws Exception {
        Fixture f = new Fixture(tmp);
        f.publish("1.2.3", "com.acme", "widgets", "1", "4.5.6");

        List<String> log = new ArrayList<>();
        assertThat(ReachabilityMetadata.configDirs(
                        f.store,
                        f.repos(),
                        pin("1.2.3", "0".repeat(64)),
                        List.of(artifact("com.acme:widgets", "4.5.6")),
                        log::add))
                .isEmpty();
        assertThat(log).anySatisfy(l -> assertThat(l).contains("checksum mismatch"));
        assertThat(ReachabilityMetadata.repositoryRoot(f.store, "1.2.3")).doesNotExist();
    }

    // ---- fixture -------------------------------------------------------------

    /** A {@code file://} Maven repo serving repository zips, a store, and an unused cache root. */
    private static final class Fixture {
        private final Path remote;
        private final Path store;
        private final Path cache;
        private final Cas cas;

        Fixture(Path tmp) throws IOException {
            this.remote = Files.createDirectories(tmp.resolve("remote"));
            this.store = Files.createDirectories(tmp.resolve("store"));
            this.cache = Files.createDirectories(tmp.resolve("cache"));
            this.cas = new Cas(tmp.resolve("cas"));
        }

        RepoGroup repos() {
            return new RepoGroup(List.of(new MavenRepo("local", remote.toUri(), new Http(), cas)));
        }

        /** Install a repository release advertising {@code metadataVersion} for one coordinate. */
        void publish(String release, String group, String artifact, String metadataVersion, String tested)
                throws IOException {
            Path target = remote.resolve(MavenLayout.artifactPath(ReachabilityMetadata.coordinate(release)));
            Files.createDirectories(target.getParent());
            Files.write(target, repositoryZip(group, artifact, metadataVersion, tested));
        }

        String sha(String release) throws IOException {
            return Hashing.sha256Hex(
                    remote.resolve(MavenLayout.artifactPath(ReachabilityMetadata.coordinate(release))));
        }
    }

    private static byte[] repositoryZip(String group, String artifact, String metadataVersion, String tested)
            throws IOException {
        String base = group + "/" + artifact + "/";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(
                    zip,
                    base + "index.json",
                    "[{\"metadata-version\":\"" + metadataVersion + "\",\"tested-versions\":[\"" + tested + "\"]}]");
            write(zip, base + metadataVersion + "/reachability-metadata.json", "{\"reflection\":[]}");
        }
        return bytes.toByteArray();
    }

    private static void write(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * The directory {@code -H:ConfigurationFileDirectories} is handed for {@code com.acme:widgets}:
     * store root, the entry this class owns, the locked release, then the repository's own
     * {@code <group>/<artifact>/<metadata-version>} layout.
     */
    private static Path configDir(Fixture f, String release, String metadataVersion) {
        return f.store
                .resolve("native/metadata-repository")
                .resolve(release)
                .resolve("com.acme")
                .resolve("widgets")
                .resolve(metadataVersion);
    }

    private static Lockfile.NativeMetadata pin(String version, String sha256Hex) {
        return new Lockfile.NativeMetadata(version, "sha256:" + sha256Hex);
    }

    private static Lockfile.Artifact artifact(String ga, String version) {
        return new Lockfile.Artifact(
                ga, version, "central", "sha256:" + "a".repeat(64), null, List.of(Scope.RUNTIME), List.of());
    }
}
