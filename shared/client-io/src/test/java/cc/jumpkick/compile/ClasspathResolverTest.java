// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathResolverTest {

    @Test
    void maps_packages_with_checksums_to_cas_paths(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "sha256:aaaa1111"), pkg("com.foo:b", "1.0", "sha256:bbbb2222")));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("aaaa1111"), cas.pathFor("bbbb2222"));
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "sha256:aaaa1111"), pkg("com.foo:b", "1.0", null)));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("aaaa1111"));
    }

    @Test
    void accepts_raw_hex_checksum(@TempDir Path tempDir) {
        // Some packages may record the bare hex without the "sha256:" prefix.
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "abcd1234")));

        List<Path> cp = new ClasspathResolver(cas).classpathFor(lock);
        assertThat(cp).containsExactly(cas.pathFor("abcd1234"));
    }

    @Test
    void falls_back_to_cas_when_repos_artifact_no_longer_matches_lock(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir.resolve("cache"));
        byte[] jar = "genuine".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        String m2Path = "com/foo/a/1.0/a-1.0.jar";
        Path casBlob = cas.put(jar);
        RepoArtifactStore store = RepoArtifactStore.forRepoName(cas.root(), "central");
        store.materialize(m2Path, casBlob, hex);
        Path readablePath = store.locate(m2Path).orElseThrow();

        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(pkg("com.foo:a", "1.0", "sha256:" + hex)));

        // Intact store: the human-readable repos/<name>/... path wins.
        assertThat(new ClasspathResolver(cas).classpathFor(lock)).containsExactly(readablePath);

        // Corrupt the index sidecar itself (repos/<name>/ is exclusively jk-owned, so this models
        // local corruption/tampering rather than an external tool's rewrite). The resolver must
        // not serve an artifact whose recorded hash no longer matches the lockfile pin — it falls
        // back to the CAS path, whose bytes are the hash it is named by.
        Path sidecar = store.root().resolve(m2Path + ".sha256");
        Files.writeString(sidecar, "0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(new ClasspathResolver(cas).classpathFor(lock)).containsExactly(cas.pathFor(hex));
    }

    @Test
    void classpath_closure_only_includes_reachable_runtime_deps(@TempDir Path tempDir) {
        // Workspace-style lock: many main-scoped packages, but packaging must walk from roots.
        Cas cas = new Cas(tempDir);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        pkg("com.foo:app:jar:", "1.0", "sha256:aaaa1111", List.of("com.foo:lib:jar:@1.0")),
                        pkg("com.foo:lib:jar:", "1.0", "sha256:bbbb2222", List.of()),
                        // Unrelated monorepo noise (android / quarkus / …) — must not ship in app fat jar.
                        pkg("com.other:noise:jar:", "9.0", "sha256:cccc3333", List.of())));

        ClasspathResolver resolver = new ClasspathResolver(cas);
        // Full-lock path still sees everything (compile/workspace semantics).
        assertThat(resolver.classpathFor(lock, ClasspathResolver.RUNTIME)).hasSize(3);

        List<Path> closure = resolver.classpathClosure(lock, List.of("com.foo:app"), ClasspathResolver.RUNTIME);
        assertThat(closure)
                .containsExactlyInAnyOrder(cas.pathFor("aaaa1111"), cas.pathFor("bbbb2222"))
                .doesNotContain(cas.pathFor("cccc3333"));
    }

    @Test
    void reachable_artifacts_bfs_and_strip_version_pins() {
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        pkg("a:root:jar:", "1", "sha256:aa", List.of("a:mid:jar:@2", "a:other:jar:@1")),
                        pkg("a:mid:jar:", "2", "sha256:bb", List.of("a:leaf:jar:@3")),
                        pkg("a:leaf:jar:", "3", "sha256:cc", List.of()),
                        pkg("a:other:jar:", "1", "sha256:dd", List.of()),
                        pkg("z:unrelated:jar:", "1", "sha256:ee", List.of())));

        List<Lockfile.Artifact> reached = ClasspathResolver.reachableArtifacts(lock, List.of("a:root"));
        assertThat(reached)
                .extracting(Lockfile.Artifact::packageKey)
                .containsExactlyInAnyOrder("a:root:jar:", "a:mid:jar:", "a:leaf:jar:", "a:other:jar:");
    }

    @Test
    void strip_version_handles_package_key_pins() {
        assertThat(ClasspathResolver.stripVersion("g:a:jar:@1.2.3")).isEqualTo("g:a:jar:");
        assertThat(ClasspathResolver.stripVersion("g:a@1.2.3")).isEqualTo("g:a");
        assertThat(ClasspathResolver.stripVersion("g:a:jar:")).isEqualTo("g:a:jar:");
    }

    private static Lockfile.Artifact pkg(String module, String version, String checksum) {
        return pkg(module, version, checksum, List.of());
    }

    private static Lockfile.Artifact pkg(String module, String version, String checksum, List<String> deps) {
        return new Lockfile.Artifact(
                module,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                checksum,
                null,
                List.of(Scope.MAIN),
                deps);
    }
}
