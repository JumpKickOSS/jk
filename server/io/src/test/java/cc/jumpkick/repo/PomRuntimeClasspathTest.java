// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.util.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomRuntimeClasspathTest {

    @BeforeEach
    void clearCaches() {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        PomRuntimeClasspath.clearResolveCacheForTests();
    }

    @Test
    void single_arg_resolve_memoizes_until_the_jar_or_pom_changes(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Path workerJar = putJar(store, "local", worker, "worker-bytes");
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                </project>
                """);

        List<Path> first = PomRuntimeClasspath.resolve(workerJar);
        assertThat(PomRuntimeClasspath.resolve(workerJar)).isSameAs(first);

        // A republished POM (new mtime) must invalidate the memo.
        Path pomPath = store.resolve("repos/local").resolve(MavenLayout.pomPath(worker));
        FileTime bumped = FileTime.fromMillis(Files.getLastModifiedTime(pomPath).toMillis() + 5_000);
        Files.setLastModifiedTime(pomPath, bumped);
        assertThat(PomRuntimeClasspath.resolve(workerJar)).isNotSameAs(first).isEqualTo(first);
    }

    @Test
    void walks_compile_deps_and_skips_provided(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate sdk = Coordinate.of("cc.jumpkick", "jk-plugin-sdk", "0.12.0");
        Coordinate junit = Coordinate.of("org.junit.jupiter", "junit-jupiter", "5.12.0");
        Path workerJar = putJar(store, "local", worker, "worker-bytes");
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick</groupId>
                      <artifactId>jk-plugin-sdk</artifactId>
                      <version>0.12.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.12.0</version>
                      <scope>provided</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", sdk, "sdk-bytes");
        putPom(store, "local", sdk, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-plugin-sdk</artifactId>
                  <version>0.12.0</version>
                </project>
                """);
        putJar(store, "central", junit, "junit-bytes");

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp).contains(workerJar.toAbsolutePath().normalize());
        assertThat(cp)
                .contains(store.resolve("repos/local")
                        .resolve(MavenLayout.artifactPath(sdk))
                        .toAbsolutePath()
                        .normalize());
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString)).doesNotContain("junit-jupiter-5.12.0.jar");
    }

    @Test
    void throws_without_a_pom(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("lonely.jar");
        Files.writeString(jar, "x");
        assertThatThrownBy(() -> PomRuntimeClasspath.resolve(jar, PomRuntimeClasspath.localRepos(tmp)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POM");
    }

    @Test
    void workspace_worker_resolves_from_host_store_when_sandbox_is_empty(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host-store");
        Path sandbox = tmp.resolve("sandbox-home");
        Files.createDirectories(sandbox);
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-host-worker", "1.0.0");
        Coordinate dep = Coordinate.of("org.example", "lib", "1.0");
        Path workspaceJar = tmp.resolve("target/plugins/host-worker/jk-host-worker-1.0.0.jar");
        Files.createDirectories(workspaceJar.getParent());
        Files.writeString(workspaceJar, "workspace-worker");
        putJar(host, "local", worker, "store-worker");
        putPom(host, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-host-worker</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path depJar = putJar(host, "local", dep, "dep-bytes");
        putPom(host, "local", dep, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        String prevHome = System.getProperty("jk.env.JK_HOME");
        String prevHost = System.getProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
        try {
            System.setProperty("jk.env.JK_HOME", sandbox.toString());
            System.setProperty(
                    PomRuntimeClasspath.HOST_STORE_PROPERTY,
                    host.toAbsolutePath().toString());
            List<Path> cp = PomRuntimeClasspath.resolve(workspaceJar);
            assertThat(cp)
                    .contains(
                            workspaceJar.toAbsolutePath().normalize(),
                            depJar.toAbsolutePath().normalize());
        } finally {
            restoreProp("jk.env.JK_HOME", prevHome);
            restoreProp(PomRuntimeClasspath.HOST_STORE_PROPERTY, prevHost);
        }
    }

    @Test
    void a_first_party_name_outside_target_does_not_use_the_host_store(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host-store");
        Path sandbox = tmp.resolve("sandbox-home");
        Files.createDirectories(sandbox);
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-host-worker", "1.0.0");
        putJar(host, "local", worker, "store-worker");
        putPom(host, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-host-worker</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path jar = tmp.resolve("jk-host-worker-1.0.0.jar");
        Files.writeString(jar, "x");

        String prevHome = System.getProperty("jk.env.JK_HOME");
        String prevHost = System.getProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
        try {
            System.setProperty("jk.env.JK_HOME", sandbox.toString());
            System.setProperty(
                    PomRuntimeClasspath.HOST_STORE_PROPERTY,
                    host.toAbsolutePath().toString());
            assertThatThrownBy(() -> PomRuntimeClasspath.resolve(jar))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("POM");
        } finally {
            restoreProp("jk.env.JK_HOME", prevHome);
            restoreProp(PomRuntimeClasspath.HOST_STORE_PROPERTY, prevHost);
        }
    }

    private static void restoreProp(String key, String prev) {
        if (prev == null) System.clearProperty(key);
        else System.setProperty(key, prev);
    }

    @Test
    void interpolates_parent_property_versions(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-formatter", "1.0");
        Coordinate databind = Coordinate.of("org.example", "databind", "1.0");
        Coordinate annotations = Coordinate.of("org.example", "annotations", "2.21");
        Coordinate parent = Coordinate.of("org.example", "parent", "1.0");
        Path workerJar = putJar(store, "local", worker, "worker");
        putPom(store, "local", worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-formatter</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>databind</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putPom(store, "local", parent, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <jackson.version.annotations>2.21</jackson.version.annotations>
                  </properties>
                </project>
                """);
        putJar(store, "local", databind, "databind");
        putPom(store, "local", databind, """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>databind</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>annotations</artifactId>
                      <version>${jackson.version.annotations}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path annotationsJar = putJar(store, "local", annotations, "annotations");
        putPom(store, "local", annotations, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>annotations</artifactId>
                  <version>2.21</version>
                </project>
                """);

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp).contains(annotationsJar.toAbsolutePath().normalize());
        assertThat(cp.stream().map(Path::toString)).noneMatch(p -> p.contains("${"));
    }

    @Test
    void fills_blank_version_from_imported_bom(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-formatter", "1.0");
        Coordinate bom = Coordinate.of("org.example", "bom", "1.0");
        Coordinate lib = Coordinate.of("org.example", "lib", "9.9.9");
        Path workerJar = putJar(store, "local", worker, "worker");
        putPom(store, "local", worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-formatter</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putPom(store, "local", bom, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib</artifactId>
                        <version>9.9.9</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path libJar = putJar(store, "local", lib, "lib");
        putPom(store, "local", lib, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>9.9.9</version>
                </project>
                """);

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp).contains(libJar.toAbsolutePath().normalize());
    }

    @Test
    void managed_test_scope_does_not_enter_runtime_classpath(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-formatter", "1.0");
        Coordinate child = Coordinate.of("org.example", "child", "1.0");
        Coordinate testlib = Coordinate.of("org.example", "testlib", "1.0");
        Path workerJar = putJar(store, "local", worker, "worker");
        putPom(store, "local", worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-formatter</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>child</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path childJar = putJar(store, "local", child, "child");
        putPom(store, "local", child, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>testlib</artifactId>
                        <version>1.0</version>
                        <scope>test</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>testlib</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", testlib, "testlib");
        putPom(store, "local", testlib, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>testlib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp).contains(childJar.toAbsolutePath().normalize());
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString)).doesNotContain("testlib-1.0.jar");
    }

    @Test
    void leftover_property_after_effective_pom_is_named_not_looked_up(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-formatter", "1.0");
        Coordinate child = Coordinate.of("org.example", "child", "1.0");
        Path workerJar = putJar(store, "local", worker, "worker");
        putPom(store, "local", worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-formatter</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>child</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", child, "child");
        putPom(store, "local", child, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>missing</artifactId>
                      <version>${not.defined}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThatThrownBy(() -> resolve(store, workerJar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved version ${not.defined}")
                .hasMessageNotContaining("run `jk install`");
    }

    @Test
    void excluded_dep_with_unresolved_version_is_pruned_before_policing(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate lib = Coordinate.of("com.foo", "lib", "1.0");
        Path workerJar = putJar(store, "local", worker, "worker-bytes");
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.bad</groupId><artifactId>broken</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", lib, "lib-bytes");
        putPom(store, "local", lib, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.foo</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.bad</groupId>
                      <artifactId>broken</artifactId>
                      <version>${never.defined}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString)).contains("lib-1.0.jar");
    }

    @Test
    void missing_parent_pom_is_loud_not_a_silent_prune(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate lib = Coordinate.of("com.foo", "lib", "1.0");
        Path workerJar = putJar(store, "local", worker, "worker-bytes");
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>lib</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", lib, "lib-bytes");
        putPom(store, "local", lib, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.foo</groupId><artifactId>parent</artifactId><version>9</version>
                  </parent>
                  <groupId>com.foo</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertThatThrownBy(() -> resolve(store, workerJar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete POM chain")
                .hasMessageContaining("com.foo:parent:9");
    }

    @Test
    void dep_without_its_own_pom_is_a_jar_only_leaf(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate fat = Coordinate.of("com.foo", "fat", "1.0");
        Path workerJar = putJar(store, "local", worker, "worker-bytes");
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>fat</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", fat, "fat-bytes");

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString)).contains("fat-1.0.jar");
    }

    @Test
    void blank_version_after_effective_pom_is_loud(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Path workerJar = putJar(store, "local", worker, "worker-bytes");
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>ungoverned</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThatThrownBy(() -> resolve(store, workerJar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:ungoverned")
                .hasMessageContaining("has no version");
    }

    @Test
    void root_pin_mediates_conflicting_transitive_versions(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate lib = Coordinate.of("com.foo", "lib", "1.0");
        Coordinate guava33 = Coordinate.of("com.google", "guava", "33");
        Coordinate guava32 = Coordinate.of("com.google", "guava", "32");
        Path workerJar = putJar(store, "local", worker, "worker-bytes");
        // Flattened root POM pins guava 33; lib's own upstream POM still says 32.
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>lib</artifactId><version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.google</groupId><artifactId>guava</artifactId><version>33</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", lib, "lib-bytes");
        putPom(store, "local", lib, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.foo</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.google</groupId><artifactId>guava</artifactId><version>32</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", guava33, "guava33-bytes");
        putPom(store, "local", guava33, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.google</groupId><artifactId>guava</artifactId><version>33</version>
                </project>
                """);
        putJar(store, "local", guava32, "guava32-bytes");

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString))
                .contains("guava-33.jar")
                .doesNotContain("guava-32.jar");
    }

    private static List<Path> resolve(Path store, Path workerJar) {
        return PomRuntimeClasspath.resolve(workerJar, PomRuntimeClasspath.localRepos(store));
    }

    private static Path putJar(Path store, String repo, Coordinate coord, String bytes) throws Exception {
        return put(store, repo, MavenLayout.artifactPath(coord), bytes.getBytes(StandardCharsets.UTF_8));
    }

    private static void putPom(Path store, String repo, Coordinate coord, String xml) throws Exception {
        put(store, repo, MavenLayout.pomPath(coord), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Path put(Path store, String repo, String rel, byte[] bytes) throws Exception {
        Path f = store.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        Files.writeString(Path.of(f + ".sha256"), Hashing.sha256Hex(bytes));
        return f;
    }
}
