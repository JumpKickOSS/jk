// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lock-pinned worker jars must resolve to Maven-layout paths (which carry a reachable POM), never
 * bare CAS blobs — except path pins, whose sha-verified blob is by contract a self-contained
 * classpath.
 */
class PinnedWorkerJarTest {

    private static final String MODULE = "com.acme:acme-rules";
    private static final String VERSION = "1.0.0";
    private static final String REL = "com/acme/acme-rules/1.0.0/acme-rules-1.0.0.jar";

    @Test
    void blob_only_pin_materializes_into_repos_local(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jar = writeJar(tmp.resolve("acme-rules.jar"));
        String hex = Hashing.sha256Hex(jar);
        Path blob = new Cas(cache).putFile(jar, hex);

        Path resolved = PluginDescriptorOps.pinnedLayoutJar(new Cas(cache), MODULE, VERSION, hex)
                .orElseThrow();

        assertThat(resolved).isEqualTo(cache.resolve("repos/jk-local").resolve(REL));
        assertThat(resolved).isRegularFile();
        assertThat(Files.isSameFile(resolved, blob))
                .as("copy, not a CAS hard link")
                .isFalse();
        // Idempotent on a warm store.
        assertThat(PluginDescriptorOps.pinnedLayoutJar(new Cas(cache), MODULE, VERSION, hex))
                .contains(resolved);
    }

    @Test
    void verified_repo_entry_wins_over_materializing(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jar = writeJar(tmp.resolve("acme-rules.jar"));
        String hex = Hashing.sha256Hex(jar);
        Path blob = new Cas(cache).putFile(jar, hex);
        RepoArtifactStore.forRepoName(cache, "jumpkick").materialize(REL, blob, hex);

        Path resolved = PluginDescriptorOps.pinnedLayoutJar(new Cas(cache), MODULE, VERSION, hex)
                .orElseThrow();

        assertThat(resolved).isEqualTo(cache.resolve("repos/jumpkick").resolve(REL));
        assertThat(cache.resolve("repos/jk-local").resolve(REL)).doesNotExist();
    }

    @Test
    void unsynced_pin_resolves_empty(@TempDir Path tmp) {
        Path cache = tmp.resolve("cache");
        assertThat(PluginDescriptorOps.pinnedLayoutJar(new Cas(cache), MODULE, VERSION, "ab".repeat(32)))
                .isEmpty();
    }

    @Test
    void cas_blob_worker_is_a_self_contained_classpath(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jar = writeJar(tmp.resolve("acme-rules.jar"));
        String hex = Hashing.sha256Hex(jar);
        Path blob = new Cas(cache).putFile(jar, hex);

        assertThat(Cas.isBlobPath(blob)).isTrue();
        assertThat(WorkerLaunchClasspath.paths(blob)).containsExactly(blob);
    }

    @Test
    void layout_worker_resolves_its_pom_classpath(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jar = writeJar(tmp.resolve("acme-rules.jar"));
        String hex = Hashing.sha256Hex(jar);
        Path blob = new Cas(cache).putFile(jar, hex);
        Path resolved = PluginDescriptorOps.pinnedLayoutJar(new Cas(cache), MODULE, VERSION, hex)
                .orElseThrow();
        Files.writeString(resolved.resolveSibling("acme-rules-1.0.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-rules</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        assertThat(Cas.isBlobPath(resolved)).isFalse();
        assertThat(WorkerLaunchClasspath.paths(resolved)).containsExactly(resolved);
    }

    @Test
    void unsatisfiable_lock_pin_is_loud_not_a_silent_fallback(@TempDir Path tmp) throws Exception {
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of(new Lockfile.PluginEntry(
                                "cc.jumpkick:jk-spring-boot", "0.0.1", "sha256:" + "ee".repeat(32)))),
                tmp.resolve("jk-lock.toml"));
        String prior = System.getProperty("jk.official.repo.url");
        System.setProperty("jk.official.repo.url", "http://127.0.0.1:1/");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> PluginBuild.lockedFirstPartyJar(tmp, "jk-spring-boot", tmp.resolve("cache")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("pins cc.jumpkick:jk-spring-boot:0.0.1")
                    .hasMessageContaining("run `jk lock` to re-pin");
        } finally {
            if (prior == null) {
                System.clearProperty("jk.official.repo.url");
            } else {
                System.setProperty("jk.official.repo.url", prior);
            }
        }
    }

    @Test
    void worker_without_a_pin_returns_null_for_locate_fallback(@TempDir Path tmp) throws Exception {
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of()),
                tmp.resolve("jk-lock.toml"));

        assertThat(PluginBuild.lockedFirstPartyJar(tmp, "jk-spring-boot", tmp.resolve("cache")))
                .isNull();
    }

    private static Path writeJar(Path target) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(target);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.flush();
        }
        return target;
    }
}
