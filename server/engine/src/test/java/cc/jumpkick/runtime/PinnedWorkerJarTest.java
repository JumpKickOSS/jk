// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A declared plugin's pinned jar resolves to a Maven-layout path (which carries a reachable POM),
 * never a bare CAS blob — except path pins, whose sha-verified blob is by contract a self-contained
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
        RepoArtifactStore.forStoreId(cache, "jumpkick").materialize(REL, blob, hex);

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
