// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathResolverTest {

    @Test
    void maps_packages_with_checksums_to_maven_layout_jars(@TempDir Path tempDir) throws Exception {
        Path a = putJar(tempDir, "com/foo/a/1.0/a-1.0.jar", "aaaa");
        Path b = putJar(tempDir, "com/foo/b/1.0/b-1.0.jar", "bbbb");
        Lockfile lock =
                lock(pkg("com.foo:a", "1.0", Hashing.sha256Hex(a)), pkg("com.foo:b", "1.0", Hashing.sha256Hex(b)));

        List<Path> cp = new ClasspathResolver(tempDir).classpathFor(lock);
        assertThat(cp)
                .containsExactly(
                        a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
        assertThat(cp).allMatch(p -> p.getFileName().toString().endsWith(".jar"));
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) throws Exception {
        Path a = putJar(tempDir, "com/foo/a/1.0/a-1.0.jar", "aaaa");
        Lockfile lock = lock(pkg("com.foo:a", "1.0", Hashing.sha256Hex(a)), pkg("com.foo:b", "1.0", null));

        assertThat(new ClasspathResolver(tempDir).classpathFor(lock))
                .containsExactly(a.toAbsolutePath().normalize());
    }

    @Test
    void accepts_raw_hex_checksum(@TempDir Path tempDir) throws Exception {
        Path a = putJar(tempDir, "com/foo/a/1.0/a-1.0.jar", "abcd");
        Lockfile lock = lock(pkg("com.foo:a", "1.0", Hashing.sha256Hex(a)));

        assertThat(new ClasspathResolver(tempDir).classpathFor(lock))
                .containsExactly(a.toAbsolutePath().normalize());
    }

    @Test
    void mismatching_store_jar_is_skipped(@TempDir Path tempDir) throws Exception {
        Path a = putJar(tempDir, "com/foo/a/1.0/a-1.0.jar", "genuine");
        Lockfile lock = lock(pkg("com.foo:a", "1.0", "0".repeat(64)));

        assertThat(new ClasspathResolver(tempDir).classpathFor(lock)).isEmpty();
        assertThat(a).exists();
    }

    @Test
    void requirePresent_fails_when_jar_missing(@TempDir Path tempDir) {
        Lockfile lock = lock(pkg("com.foo:a", "1.0", "0".repeat(64)));

        assertThatThrownBy(
                        () -> new ClasspathResolver(tempDir).classpathFor(lock, ClasspathResolver.COMPILE_MAIN, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:a")
                .hasMessageContaining("not on disk after sync");
    }

    @Test
    void classpath_closure_only_includes_reachable_runtime_deps(@TempDir Path tempDir) throws Exception {
        Path app = putJar(tempDir, "com/foo/app/1.0/app-1.0.jar", "app");
        Path lib = putJar(tempDir, "com/foo/lib/1.0/lib-1.0.jar", "lib");
        putJar(tempDir, "com/other/noise/9.0/noise-9.0.jar", "noise");
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        pkg(
                                "com.foo:app:jar:",
                                "1.0",
                                "sha256:" + Hashing.sha256Hex(app),
                                List.of("com.foo:lib:jar:@1.0")),
                        pkg("com.foo:lib:jar:", "1.0", "sha256:" + Hashing.sha256Hex(lib), List.of()),
                        pkg(
                                "com.other:noise:jar:",
                                "9.0",
                                "sha256:"
                                        + Hashing.sha256Hex(Files.readAllBytes(
                                                tempDir.resolve("repos/central/com/other/noise/9.0/noise-9.0.jar"))),
                                List.of())));

        ClasspathResolver resolver = new ClasspathResolver(tempDir);
        assertThat(resolver.classpathFor(lock, ClasspathResolver.RUNTIME)).hasSize(3);

        List<Path> closure = resolver.classpathClosure(lock, List.of("com.foo:app"), ClasspathResolver.RUNTIME);
        assertThat(closure)
                .containsExactlyInAnyOrder(
                        app.toAbsolutePath().normalize(), lib.toAbsolutePath().normalize());
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

    private static Path putJar(Path store, String relative, String payload) throws Exception {
        Path src = store.resolve("src.bin");
        Files.writeString(src, payload);
        RepoArtifactStore.forStoreId(store, "central").materialize(relative, src, Hashing.sha256Hex(src));
        Files.deleteIfExists(src);
        return store.resolve("repos/central").resolve(relative);
    }

    private static Lockfile lock(Lockfile.Artifact... artifacts) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(artifacts));
    }

    private static Lockfile.Artifact pkg(String module, String version, @Nullable String checksum) {
        return pkg(module, version, checksum, List.of());
    }

    private static Lockfile.Artifact pkg(String module, String version, @Nullable String checksum, List<String> deps) {
        String c = checksum == null || checksum.startsWith("sha256:") || checksum.length() != 64
                ? checksum
                : "sha256:" + checksum;
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", c, null, List.of(Scope.MAIN), deps);
    }
}
