// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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

    /**
     * The test classpath carries the provided rows as Maven's does: the container or framework
     * API a test reaches for is there at test time and still absent from the packaged artifact.
     */
    @Test
    void a_provided_row_is_on_the_test_classpath_and_not_the_runtime_one(@TempDir Path tempDir) throws Exception {
        Path main = putJar(tempDir, "com/foo/main/1.0/main-1.0.jar", "main");
        Path provided = putJar(tempDir, "com/foo/api/1.0/api-1.0.jar", "api");
        Path test = putJar(tempDir, "com/foo/junit/1.0/junit-1.0.jar", "junit");
        Lockfile lock = lock(
                scoped("com.foo:main", "1.0", Hashing.sha256Hex(main), Scope.MAIN),
                scoped("com.foo:api", "1.0", Hashing.sha256Hex(provided), Scope.PROVIDED),
                scoped("com.foo:junit", "1.0", Hashing.sha256Hex(test), Scope.TEST));
        ClasspathResolver resolver = new ClasspathResolver(tempDir);

        assertThat(resolver.classpathFor(lock, ClasspathResolver.TEST))
                .containsExactly(
                        main.toAbsolutePath().normalize(),
                        provided.toAbsolutePath().normalize(),
                        test.toAbsolutePath().normalize());
        assertThat(resolver.classpathFor(lock, ClasspathResolver.RUNTIME))
                .containsExactly(main.toAbsolutePath().normalize());
        assertThat(resolver.classpathFor(lock, ClasspathResolver.RUN))
                .containsExactly(main.toAbsolutePath().normalize());
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) throws Exception {
        Path a = putJar(tempDir, "com/foo/a/1.0/a-1.0.jar", "aaaa");
        Lockfile lock = lock(pkg("com.foo:a", "1.0", Hashing.sha256Hex(a)), pkg("com.foo:b", "1.0", null));

        assertThat(new ClasspathResolver(tempDir).classpathFor(lock))
                .containsExactly(a.toAbsolutePath().normalize());
    }

    /**
     * A row that pins no checksum is a BOM, an aggregator, a relocation stub or a KMP root when it
     * says so — its type or its {@code path} names the POM or module file it stands for — and those
     * put nothing on a classpath. One that says nothing pins a jar nobody fetched: a classpath
     * built to compile against fails on it by name rather than compiling without it.
     */
    @Test
    void a_compile_classpath_fails_on_a_row_that_pins_no_checksum_and_names_no_pom_only_file(@TempDir Path tempDir)
            throws Exception {
        Path a = putJar(tempDir, "com/foo/a/1.0/a-1.0.jar", "aaaa");
        Lockfile lock = lock(pkg("com.foo:a", "1.0", Hashing.sha256Hex(a)), unmarkedPicketbox());

        assertThatThrownBy(
                        () -> new ClasspathResolver(tempDir).classpathFor(lock, ClasspathResolver.COMPILE_MAIN, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "dependency org.picketbox:picketbox:5.0.3.Final has no file: its lock row pins no checksum and"
                                + " names no POM-only file")
                .hasMessageContaining("run `jk lock`");
    }

    private static Lockfile.Artifact unmarkedPicketbox() {
        return new Lockfile.Artifact(
                "org.picketbox:picketbox:jar:",
                "5.0.3.Final",
                "jumpkick+https://jumpkick.build/repo/",
                null,
                null,
                List.of(Scope.PROVIDED),
                List.of());
    }

    @Test
    void a_row_that_names_the_pom_or_module_file_it_stands_for_is_left_out_silently(@TempDir Path tempDir)
            throws Exception {
        Path a = putJar(tempDir, "com/foo/a/1.0/a-1.0.jar", "aaaa");
        Lockfile lock = lock(
                pkg("com.foo:a", "1.0", Hashing.sha256Hex(a)),
                fileless("com.foo:aggregator:jar:", "aggregator-1.0.pom"),
                fileless("com.foo:kmp-root:jar:", "kmp-root-1.0.module"),
                fileless("com.foo:bom:pom:", null));

        assertThat(new ClasspathResolver(tempDir).classpathFor(lock, ClasspathResolver.COMPILE_MAIN, true))
                .containsExactly(a.toAbsolutePath().normalize());
        assertThat(ClasspathResolver.lockedWithoutFile(lock, ClasspathResolver.COMPILE_MAIN))
                .as("only a jar-typed row standing for a POM is a suspect when a package is missing")
                .extracting(Lockfile.Artifact::name)
                .containsExactly("com.foo:aggregator:jar:");
    }

    private static Lockfile.Artifact fileless(String name, @Nullable String path) {
        return new Lockfile.Artifact(
                name,
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                null,
                path,
                List.of(Scope.MAIN),
                List.of());
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
    void requirePresent_names_every_missing_row_in_one_diagnostic(@TempDir Path tempDir) throws Exception {
        Path present = putJar(tempDir, "com/foo/here/1.0/here-1.0.jar", "here");
        Lockfile lock = lock(
                pkg("com.foo:here", "1.0", Hashing.sha256Hex(present)),
                pkg("com.foo:a", "1.0", "0".repeat(64)),
                pkg("com.foo:b", "2.0", "1".repeat(64)));

        assertThatThrownBy(
                        () -> new ClasspathResolver(tempDir).classpathFor(lock, ClasspathResolver.COMPILE_MAIN, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:a:1.0")
                .hasMessageContaining("com.foo:b:2.0")
                .hasMessageContaining("not on disk after sync")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("com.foo:here"));
    }

    @Test
    void a_module_classpath_holds_only_the_rows_its_declarations_reach(@TempDir Path tempDir) throws Exception {
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

        JkBuild module = JkBuildParser.parse("""
                name = "m"
                [dependencies]
                app = { group = "com.foo", version = "1.0" }
                """);
        List<Path> closure =
                resolver.classpathFor(lock, ClasspathResolver.RUNTIME, false, module, tempDir.resolve("m"));
        assertThat(closure)
                .containsExactly(
                        app.toAbsolutePath().normalize(), lib.toAbsolutePath().normalize());
    }

    /**
     * {@code [test] exclude-dependencies} takes a declared and a transitive coordinate off the test
     * JVM's classpath and leaves the compile-test classpath, and the excluded jar's own
     * dependencies, alone.
     */
    @Test
    void test_exclude_dependencies_leave_the_test_runtime_classpath_and_not_compile_test(@TempDir Path tempDir)
            throws Exception {
        Path api = putJar(tempDir, "org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar", "api");
        Path simple = putJar(tempDir, "org/slf4j/slf4j-simple/2.0.17/slf4j-simple-2.0.17.jar", "simple");
        Path capture = putJar(tempDir, "com/ex/capture/1.0/capture-1.0.jar", "capture");
        Path logback = putJar(tempDir, "ch/qos/logback/logback-classic/1.5.0/logback-classic-1.5.0.jar", "logback");
        Lockfile lock = lock(
                testRow("ch.qos.logback:logback-classic:jar:", "1.5.0", logback, List.of()),
                testRow("com.ex:capture:jar:", "1.0", capture, List.of("ch.qos.logback:logback-classic:jar:@1.5.0")),
                testRow("org.slf4j:slf4j-api:jar:", "2.0.17", api, List.of()),
                testRow("org.slf4j:slf4j-simple:jar:", "2.0.17", simple, List.of("org.slf4j:slf4j-api:jar:@2.0.17")));
        JkBuild module = JkBuildParser.parse("""
                name = "m"
                [test-dependencies]
                slf4j-simple = { group = "org.slf4j", version = "2.0.17" }
                capture = { group = "com.ex", version = "1.0" }

                [test]
                exclude-dependencies = ["org.slf4j:slf4j-simple", "ch.qos.logback:logback-classic"]
                """);
        ClasspathResolver resolver = new ClasspathResolver(tempDir);
        Path dir = tempDir.resolve("m");

        assertThat(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST, true, module, dir))
                .contains(
                        simple.toAbsolutePath().normalize(),
                        logback.toAbsolutePath().normalize(),
                        api.toAbsolutePath().normalize());
        assertThat(resolver.testRuntimeClasspathFor(lock, true, module, dir))
                .containsExactlyInAnyOrder(
                        api.toAbsolutePath().normalize(),
                        capture.toAbsolutePath().normalize());
        assertThat(resolver.testRuntimeEntriesFor(lock, true, module, dir))
                .extracting(e -> e.artifact().moduleArtifact())
                .containsExactlyInAnyOrder("slf4j-api", "capture");
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

        List<Lockfile.Artifact> reached = ClasspathResolver.reachableArtifacts(lock.artifacts(), List.of("a:root"));
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

    @Test
    void a_direct_dependency_precedes_the_transitive_that_shares_its_package(@TempDir Path tempDir) throws Exception {
        String split = "com/caucho/hessian/io/SerializerFactory.class";
        Path fork = putJarWithEntry(tempDir, "com/alipay/sofa/hessian/3.5.5/hessian-3.5.5.jar", split, "fork");
        Path direct = putJarWithEntry(tempDir, "com/caucho/hessian/4.0.63/hessian-4.0.63.jar", split, "direct");
        Path lib = putJar(tempDir, "org/example/lib/1.0/lib-1.0.jar", "lib");
        Path noise = putJar(tempDir, "aa/noise/1.0/noise-1.0.jar", "noise");
        // Lock order is by name: the fork and the transitive's parent sort ahead of the direct
        // declaration, and a row nothing declared reaches sorts first.
        Lockfile lock = lock(
                pkg("aa:noise:jar:", "1.0", Hashing.sha256Hex(noise)),
                pkg("com.alipay.sofa:hessian:jar:", "3.5.5", Hashing.sha256Hex(fork)),
                pkg("com.caucho:hessian:jar:", "4.0.63", Hashing.sha256Hex(direct)),
                pkg(
                        "org.example:lib:jar:",
                        "1.0",
                        Hashing.sha256Hex(lib),
                        List.of("com.alipay.sofa:hessian:jar:@3.5.5")));
        JkBuild module = JkBuildParser.parse("""
                name = "m"
                [dependencies]
                lib = { group = "org.example", version = "1.0" }
                hessian = { group = "com.caucho", version = "4.0.63" }
                """);

        List<Path> cp = new ClasspathResolver(tempDir)
                .classpathFor(lock, ClasspathResolver.COMPILE_MAIN, false, module, tempDir.resolve("m"));

        assertThat(cp)
                .containsExactly(
                        lib.toAbsolutePath().normalize(),
                        direct.toAbsolutePath().normalize(),
                        fork.toAbsolutePath().normalize())
                .doesNotContain(noise.toAbsolutePath().normalize());
        URL[] urls = new URL[cp.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = cp.get(i).toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            URL winner = loader.getResource(split);
            assertThat(winner).isNotNull();
            assertThat(winner.toString()).contains("hessian-4.0.63.jar");
        }
    }

    private static Path putJarWithEntry(Path store, String relative, String entry, String payload) throws Exception {
        Path src = store.resolve("src.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(src))) {
            jar.putNextEntry(new JarEntry(entry));
            OutputStream body = jar;
            body.write(payload.getBytes());
            jar.closeEntry();
        }
        RepoArtifactStore.forStoreId(store, "central").materialize(relative, src, Hashing.sha256Hex(src));
        Files.deleteIfExists(src);
        return store.resolve("repos/central").resolve(relative);
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

    private static Lockfile.Artifact scoped(String module, String version, String checksum, Scope scope) {
        return new Lockfile.Artifact(
                module,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + checksum,
                null,
                List.of(scope),
                List.of());
    }

    private static Lockfile.Artifact testRow(String module, String version, Path jar, List<String> deps)
            throws Exception {
        return new Lockfile.Artifact(
                module,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(jar),
                null,
                List.of(Scope.TEST),
                deps);
    }

    private static Lockfile.Artifact pkg(String module, String version, @Nullable String checksum, List<String> deps) {
        String c = checksum == null || checksum.startsWith("sha256:") || checksum.length() != 64
                ? checksum
                : "sha256:" + checksum;
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", c, null, List.of(Scope.MAIN), deps);
    }
}
