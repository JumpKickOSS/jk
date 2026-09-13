// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.SysProps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@SysProps.TempRoots("jk.m2.local")
class CacheSyncTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void fetches_missing_artifact(@TempDir Path tempDir) throws Exception {
        byte[] jar = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        registerJar("com.foo", "leaf", "1.0", jar);

        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex));
        CacheSync.Report report = newSync(tempDir).sync(lock);

        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.upToDate()).isZero();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void recognizes_already_cached(@TempDir Path tempDir) throws Exception {
        byte[] jar = "already-cached".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        Path store = tempDir.resolve("cache");
        Path src = tempDir.resolve("leaf.jar");
        Files.write(src, jar);
        centralStore(store).materialize("com/foo/leaf/1.0/leaf-1.0.jar", src, hex);
        registerJar("com.foo", "leaf", "1.0", jar);

        CacheSync.Report report = new CacheSync(new Cas(store), new Http(), false)
                .sync(lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex)));

        assertThat(report.upToDate()).isEqualTo(1);
        assertThat(report.fetched()).isZero();
    }

    @Test
    void reports_checksum_mismatch(@TempDir Path tempDir) throws Exception {
        // Serve a jar whose actual sha256 won't match what the lockfile claims.
        byte[] jar = "real".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "leaf", "1.0", jar);
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:deadbeef"));

        CacheSync.Report report = newSync(tempDir).sync(lock);

        assertThat(report.fetched()).isZero();
        assertThat(report.errors()).singleElement().asString().contains("checksum mismatch");
    }

    @Test
    void mismatching_m2_is_not_overwritten(@TempDir Path tempDir) throws Exception {
        byte[] jar = "genuine-bytes".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        registerJar("com.foo", "leaf", "1.0", jar);
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex));
        assertThat(newSync(tempDir, true).sync(lock).fetched()).isEqualTo(1);

        Path m2Jar = Path.of(System.getProperty("jk.m2.local")).resolve("com/foo/leaf/1.0/leaf-1.0.jar");
        Files.write(m2Jar, "poisoned".getBytes(StandardCharsets.UTF_8));

        CacheSync.Report report = newSync(tempDir, true).sync(lock);

        assertThat(report.errors()).isEmpty();
        assertThat(Files.readAllBytes(m2Jar)).isEqualTo("poisoned".getBytes(StandardCharsets.UTF_8));
        Path storeJar = centralStore(tempDir.resolve("cache"))
                .locate("com/foo/leaf/1.0/leaf-1.0.jar")
                .orElseThrow();
        assertThat(storeJar).exists();
        assertThat(Files.readAllBytes(storeJar)).isEqualTo(jar);
    }

    @Test
    void does_not_touch_m2_when_mirroring_is_disabled(@TempDir Path tempDir) throws Exception {
        byte[] jar = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        registerJar("com.foo", "leaf", "1.0", jar);
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex));

        assertThat(newSync(tempDir).sync(lock).fetched()).isEqualTo(1);

        Path m2Jar = Path.of(System.getProperty("jk.m2.local")).resolve("com/foo/leaf/1.0/leaf-1.0.jar");
        assertThat(m2Jar).doesNotExist();
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) throws Exception {
        Lockfile lock = lockOf(pkg("com.foo:parent-pom", "1.0", null));
        CacheSync.Report report = newSync(tempDir).sync(lock);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.errors()).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private CacheSync newSync(Path tempDir) {
        return newSync(tempDir, false);
    }

    private CacheSync newSync(Path tempDir, boolean mirrorToM2) {
        return new CacheSync(new Cas(tempDir.resolve("cache")), new Http(), mirrorToM2);
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".jar";
        http.served().put(path, bytes);
    }

    private Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    /** The store the lock rows' source resolves to: keyed by the stub's origin, whatever the row calls it. */
    private RepoArtifactStore centralStore(Path store) {
        return RepoArtifactStore.forSource(store, "central+" + http.base() + "/");
    }

    private Lockfile.Artifact pkg(String module, String version, @Nullable String checksum) {
        return new Lockfile.Artifact(module, version, "central+" + http.base() + "/", checksum, null, List.of());
    }
}
