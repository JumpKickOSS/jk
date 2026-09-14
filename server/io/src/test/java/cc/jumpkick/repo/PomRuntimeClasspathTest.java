// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        Path pomPath = store.resolve("repos/jk-local").resolve(MavenLayout.pomPath(worker));
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        putJar(store, RepoArtifactResolver.JK_LOCAL, sdk, "sdk-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, sdk, """
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
                .contains(store.resolve("repos/jk-local")
                        .resolve(MavenLayout.artifactPath(sdk))
                        .toAbsolutePath()
                        .normalize());
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString)).doesNotContain("junit-jupiter-5.12.0.jar");
    }

    /**
     * Maven's nearest-wins, in the shape that put an Android Guava under Jib: the worker declares
     * {@code client-lib} (jib-core), which declares {@code http} (google-http-client) before its
     * own {@code util:2.0-jre} (guava). {@code http}'s parent manages {@code util} at
     * {@code 1.0-android}. Depth two beats depth three, whatever order the POM lists them in and
     * however deep a walk went first — so the {@code -jre} jar is the one on the classpath.
     */
    @Test
    void a_direct_dependency_outranks_a_deeper_managed_request_for_the_same_module(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-image-builder", "1.0");
        Coordinate clientLib = Coordinate.of("com.example", "client-lib", "1.0");
        Coordinate http = Coordinate.of("com.example", "http", "1.0");
        Coordinate httpParent = Coordinate.of("com.example", "http-parent", "1.0");
        Coordinate utilJre = Coordinate.of("com.example", "util", "2.0-jre");
        Coordinate utilAndroid = Coordinate.of("com.example", "util", "1.0-android");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-image-builder</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>client-lib</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "central", clientLib, "client-lib");
        putPom(store, "central", clientLib, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>client-lib</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>http</artifactId><version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>util</artifactId><version>2.0-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putPom(store, "central", httpParent, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>http-parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId><artifactId>util</artifactId><version>1.0-android</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        putJar(store, "central", http, "http");
        putPom(store, "central", http, """
                <project>
                  <parent>
                    <groupId>com.example</groupId><artifactId>http-parent</artifactId><version>1.0</version>
                  </parent>
                  <artifactId>http</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId><artifactId>util</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path jreJar = putJar(store, "central", utilJre, "util-jre");
        putPom(
                store,
                "central",
                utilJre,
                "<project><groupId>com.example</groupId>"
                        + "<artifactId>util</artifactId><version>2.0-jre</version></project>");
        putJar(store, "central", utilAndroid, "util-android");
        putPom(
                store,
                "central",
                utilAndroid,
                "<project><groupId>com.example</groupId>"
                        + "<artifactId>util</artifactId><version>1.0-android</version></project>");

        List<Path> cp = resolve(store, workerJar);
        List<String> names =
                cp.stream().map(Path::getFileName).map(Path::toString).toList();
        assertThat(cp).contains(jreJar.toAbsolutePath().normalize());
        assertThat(names).doesNotContain("util-1.0-android.jar");
        assertThat(names).containsOnlyOnce("util-2.0-jre.jar");
    }

    /**
     * At equal depth the first declaration wins (Maven), and a module requested at one version by
     * an earlier sibling is not fetched again at another version by a later one.
     */
    @Test
    void at_equal_depth_the_first_declared_request_wins(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-formatter", "1.0");
        Coordinate a = Coordinate.of("com.example", "a", "1.0");
        Coordinate b = Coordinate.of("com.example", "b", "1.0");
        Coordinate leaf1 = Coordinate.of("com.example", "leaf", "1.0");
        Coordinate leaf2 = Coordinate.of("com.example", "leaf", "2.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-formatter</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>a</artifactId><version>1.0</version></dependency>
                    <dependency><groupId>com.example</groupId><artifactId>b</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "central", a, "a");
        putPom(store, "central", a, """
                <project>
                  <groupId>com.example</groupId><artifactId>a</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "central", b, "b");
        putPom(store, "central", b, """
                <project>
                  <groupId>com.example</groupId><artifactId>b</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>2.0</version></dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "central", leaf1, "leaf1");
        putLeafPom(store, leaf1);
        putJar(store, "central", leaf2, "leaf2");
        putLeafPom(store, leaf2);

        List<String> names = resolve(store, workerJar).stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .toList();
        assertThat(names).contains("leaf-1.0.jar").doesNotContain("leaf-2.0.jar");
    }

    /**
     * The worker POM's own {@code <dependencyManagement>} governs every transitive request, as it
     * does for a Maven project: a pin is the version fetched, not merely a tiebreak among the
     * versions the tree happens to ask for.
     */
    @Test
    void the_root_dependency_management_pins_transitive_versions(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-formatter", "1.0");
        Coordinate lib = Coordinate.of("com.example", "lib", "1.0");
        Coordinate leafOld = Coordinate.of("com.example", "leaf", "1.0");
        Coordinate leafPinned = Coordinate.of("com.example", "leaf", "3.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-formatter</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>3.0</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "central", lib, "lib");
        putPom(store, "central", lib, """
                <project>
                  <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "central", leafOld, "leaf-old");
        putLeafPom(store, leafOld);
        Path pinnedJar = putJar(store, "central", leafPinned, "leaf-pinned");
        putLeafPom(store, leafPinned);

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp).contains(pinnedJar.toAbsolutePath().normalize());
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString)).doesNotContain("leaf-1.0.jar");
    }

    /**
     * A version the worker POM writes on its own dependency is the one fetched, as in a Maven
     * project, even when its {@code <dependencyManagement>} manages the module at another: the
     * pins govern the transitive requests and the direct ones that omit a version.
     */
    @Test
    void a_root_declaration_with_its_own_version_outranks_the_root_pin(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-formatter", "1.0");
        Coordinate libDeclared = Coordinate.of("com.example", "lib", "2.0");
        Coordinate leafPinned = Coordinate.of("com.example", "leaf", "3.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-formatter</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                      <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>3.0</version></dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>2.0</version></dependency>
                  </dependencies>
                </project>
                """);
        Path declaredJar = putJar(store, "central", libDeclared, "lib-declared");
        putPom(store, "central", libDeclared, """
                <project>
                  <groupId>com.example</groupId><artifactId>lib</artifactId><version>2.0</version>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>leaf</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "central", Coordinate.of("com.example", "lib", "1.0"), "lib-managed");
        Coordinate leafRequested = Coordinate.of("com.example", "leaf", "1.0");
        putJar(store, "central", leafRequested, "leaf-requested");
        putLeafPom(store, leafRequested);
        Path pinnedJar = putJar(store, "central", leafPinned, "leaf-pinned");
        putLeafPom(store, leafPinned);

        List<Path> cp = resolve(store, workerJar);
        assertThat(cp)
                .contains(
                        declaredJar.toAbsolutePath().normalize(),
                        pinnedJar.toAbsolutePath().normalize());
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString))
                .doesNotContain("lib-1.0.jar", "leaf-1.0.jar");
    }

    @Test
    void workspace_worker_resolves_from_host_store_when_sandbox_is_empty(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host-store");
        Path sandbox = tmp.resolve("sandbox-home");
        Files.createDirectories(sandbox);
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-host-worker", "1.0.0");
        Coordinate dep = Coordinate.of("org.example", "lib", "1.0");
        Path workspaceJar = tmp.resolve("target/plugins/host-worker/jk-host-worker-1.0.0.jar");
        Path moduleOut = requireNonNull(workspaceJar.getParent());
        Files.createDirectories(moduleOut);
        // A module output directory, not just a path with `target` in it: the compiled classes are
        // what make this a jar jk built rather than a jar that happens to sit under that name.
        Files.createDirectories(moduleOut.resolve("classes").resolve("main"));
        Files.writeString(workspaceJar, "workspace-worker");
        putJar(host, RepoArtifactResolver.JK_LOCAL, worker, "store-worker");
        putPom(host, RepoArtifactResolver.JK_LOCAL, worker, """
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
        Path depJar = putJar(host, RepoArtifactResolver.JK_LOCAL, dep, "dep-bytes");
        putPom(host, RepoArtifactResolver.JK_LOCAL, dep, """
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
        putJar(host, RepoArtifactResolver.JK_LOCAL, worker, "store-worker");
        putPom(host, RepoArtifactResolver.JK_LOCAL, worker, """
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        putPom(store, RepoArtifactResolver.JK_LOCAL, parent, """
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
        putJar(store, RepoArtifactResolver.JK_LOCAL, databind, "databind");
        putPom(store, RepoArtifactResolver.JK_LOCAL, databind, """
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
        Path annotationsJar = putJar(store, RepoArtifactResolver.JK_LOCAL, annotations, "annotations");
        putPom(store, RepoArtifactResolver.JK_LOCAL, annotations, """
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        putPom(store, RepoArtifactResolver.JK_LOCAL, bom, """
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
        Path libJar = putJar(store, RepoArtifactResolver.JK_LOCAL, lib, "lib");
        putPom(store, RepoArtifactResolver.JK_LOCAL, lib, """
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        Path childJar = putJar(store, RepoArtifactResolver.JK_LOCAL, child, "child");
        putPom(store, RepoArtifactResolver.JK_LOCAL, child, """
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
        putJar(store, RepoArtifactResolver.JK_LOCAL, testlib, "testlib");
        putPom(store, RepoArtifactResolver.JK_LOCAL, testlib, """
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        putJar(store, RepoArtifactResolver.JK_LOCAL, child, "child");
        putPom(store, RepoArtifactResolver.JK_LOCAL, child, """
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        putJar(store, RepoArtifactResolver.JK_LOCAL, lib, "lib-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, lib, """
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
    void blank_version_after_effective_pom_is_loud(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        // Flattened root POM pins guava 33; lib's own upstream POM still says 32.
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
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
        putJar(store, RepoArtifactResolver.JK_LOCAL, lib, "lib-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, lib, """
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
        putJar(store, RepoArtifactResolver.JK_LOCAL, guava33, "guava33-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, guava33, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.google</groupId><artifactId>guava</artifactId><version>33</version>
                </project>
                """);
        putJar(store, RepoArtifactResolver.JK_LOCAL, guava32, "guava32-bytes");

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

    /** A dependency-free POM beside a leaf jar: every jar on the classpath has its POM in the store. */
    private static void putLeafPom(Path store, Coordinate coord) throws Exception {
        putPom(
                store,
                "central",
                coord,
                "<project><modelVersion>4.0.0</modelVersion><groupId>" + coord.group() + "</groupId><artifactId>"
                        + coord.artifact() + "</artifactId><version>" + coord.version() + "</version></project>");
    }

    private static void putPom(Path store, String repo, Coordinate coord, String xml) throws Exception {
        put(store, repo, MavenLayout.pomPath(coord), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Path put(Path store, String repo, String rel, byte[] bytes) throws Exception {
        Path f = store.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        RepoArtifactStore.forStoreId(store, repo).writeMemo(rel, f, Hashing.sha256Hex(bytes));
        return f;
    }
}
