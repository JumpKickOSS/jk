// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.resolver.DependencyTree;
import cc.jumpkick.resolver.DependencyTreeStyle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One lock holds every member's rows; each member's classpath is the part of it that member
 * reaches. The workspace is a library and an application that depends on it, each with a main
 * and a test table of its own.
 */
class WorkspaceModuleClasspathTest {

    @TempDir
    Path tmp;

    private Path store;
    private Path library;
    private Path application;
    private Lockfile lock;
    private Path boot;
    private Path core;
    private Path web;
    private Path tomcat;
    private Path testkit;
    private Path mockit;
    private Path launcher;

    @BeforeEach
    void workspace() throws Exception {
        store = Files.createDirectories(tmp.resolve("store"));
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["library", "application"]
                """);
        library = module(root, "library", """
                [dependencies]
                boot = { group = "com.lib", version = "1.0" }

                [test-dependencies]
                testkit = { group = "com.test", version = "1.0" }
                """);
        application = module(root, "application", """
                [dependencies]
                library = { workspace = true }
                web = { group = "com.web", version = "1.0" }

                [test-dependencies]
                testkit = { group = "com.test", version = "1.0" }
                mockit = { group = "com.test", version = "1.0" }
                """);
        boot = putJar("com/lib/boot/1.0/boot-1.0.jar", "boot");
        core = putJar("com/lib/core/1.0/core-1.0.jar", "core");
        web = putJar("com/web/web/1.0/web-1.0.jar", "web");
        tomcat = putJar("com/web/tomcat/1.0/tomcat-1.0.jar", "tomcat");
        testkit = putJar("com/test/testkit/1.0/testkit-1.0.jar", "testkit");
        mockit = putJar("com/test/mockit/1.0/mockit-1.0.jar", "mockit");
        launcher = putJar(
                "org/junit/platform/junit-platform-launcher/6.1.3/junit-platform-launcher-6.1.3.jar", "launcher");
        lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        row("com.lib:boot", boot, List.of(Scope.MAIN, Scope.TEST), "com.lib:core:jar:@1.0"),
                        row("com.lib:core", core, List.of(Scope.MAIN, Scope.TEST)),
                        row("com.test:mockit", mockit, List.of(Scope.TEST)),
                        row("com.test:testkit", testkit, List.of(Scope.TEST), "com.lib:core:jar:@1.0"),
                        row("com.web:tomcat", tomcat, List.of(Scope.MAIN)),
                        row("com.web:web", web, List.of(Scope.MAIN), "com.web:tomcat:jar:@1.0"),
                        new Lockfile.Artifact(
                                "org.junit.platform:junit-platform-launcher:jar:",
                                "6.1.3",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:" + Hashing.sha256Hex(launcher),
                                null,
                                List.of(Scope.TEST),
                                List.of())));
    }

    @Test
    void a_member_compiles_against_what_it_declares_and_never_a_siblings_declarations() throws Exception {
        assertThat(classpath(library, ClasspathResolver.COMPILE_MAIN)).containsExactly(jar(boot), jar(core));
    }

    @Test
    void a_member_whose_only_dependency_is_gone_compiles_against_nothing() throws Exception {
        Files.writeString(library.resolve("jk.toml"), """
                group = "com.ex"
                name = "library"
                version = "1.0"
                """);

        assertThat(classpath(library, ClasspathResolver.COMPILE_MAIN))
                .as("the application's web stack is not the library's")
                .isEmpty();
    }

    @Test
    void a_members_test_classpath_leaves_out_what_only_a_siblings_tests_declare() throws Exception {
        assertThat(classpath(library, ClasspathResolver.TEST))
                .containsExactly(jar(boot), jar(testkit), jar(launcher), jar(core))
                .doesNotContain(jar(mockit), jar(web), jar(tomcat));
    }

    @Test
    void a_consumer_reads_its_own_rows_first_then_those_its_sibling_passes_on() throws Exception {
        assertThat(classpath(application, ClasspathResolver.COMPILE_MAIN))
                .containsExactly(jar(web), jar(tomcat), jar(boot), jar(core));
        assertThat(classpath(application, ClasspathResolver.TEST))
                .contains(jar(mockit), jar(testkit), jar(launcher), jar(boot))
                .doesNotHaveDuplicates();
    }

    @Test
    void a_siblings_test_dependencies_do_not_ride_to_its_consumer() throws Exception {
        Files.writeString(application.resolve("jk.toml"), """
                group = "com.ex"
                name = "application"
                version = "1.0"

                [dependencies]
                library = { workspace = true }
                """);

        assertThat(classpath(application, ClasspathResolver.TEST)).containsExactly(jar(launcher), jar(boot), jar(core));
    }

    @Test
    void a_siblings_shared_workspace_dependency_rides_to_its_consumer() throws Exception {
        Path root = tmp.resolve("ws");
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["library", "application"]

                [workspace.dependencies]
                boot = "com.lib:boot:1.0"
                """);
        Files.writeString(library.resolve("jk.toml"), """
                group = "com.ex"
                name = "library"
                version = "1.0"

                [export-dependencies]
                boot.workspace = true
                """);

        assertThat(classpath(library, ClasspathResolver.COMPILE_MAIN)).containsExactly(jar(boot), jar(core));
        assertThat(classpath(application, ClasspathResolver.COMPILE_MAIN))
                .as("the library's shared boot edge is inherited like a pinned one")
                .contains(jar(boot), jar(core));
    }

    @Test
    void the_jupiter_engine_rides_only_the_test_classpath_of_a_member_whose_tests_reach_a_jupiter_api()
            throws Exception {
        Path api = putJar("org/junit/jupiter/junit-jupiter-api/5.12.2/junit-jupiter-api-5.12.2.jar", "api");
        Path engine = putJar("org/junit/jupiter/junit-jupiter-engine/5.12.2/junit-jupiter-engine-5.12.2.jar", "engine");
        List<Lockfile.Artifact> rows = new ArrayList<>(lock.artifacts());
        rows.removeIf(r -> r.name().equals("com.test:mockit:jar:"));
        rows.add(
                row("com.test:mockit", mockit, List.of(Scope.TEST), "org.junit.jupiter:junit-jupiter-api:jar:@5.12.2"));
        rows.add(new Lockfile.Artifact(
                "org.junit.jupiter:junit-jupiter-api:jar:",
                "5.12.2",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(api),
                null,
                List.of(Scope.TEST),
                List.of()));
        rows.add(new Lockfile.Artifact(
                "org.junit.jupiter:junit-jupiter-engine:jar:",
                "5.12.2",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(engine),
                null,
                List.of(Scope.TEST),
                List.of("org.junit.jupiter:junit-jupiter-api:jar:@5.12.2")));
        lock = lock.withArtifacts(rows);

        assertThat(classpath(application, ClasspathResolver.TEST))
                .as("mockit brings the Jupiter API in, so the lock added its engine")
                .contains(jar(mockit), jar(api), jar(engine), jar(launcher));
        assertThat(classpath(library, ClasspathResolver.TEST))
                .as("the library's tests reach no Jupiter: the engine the workspace locked is not theirs")
                .containsExactly(jar(boot), jar(testkit), jar(launcher), jar(core));
    }

    @Test
    void a_member_whose_provided_jupiter_api_is_its_only_jupiter_gets_the_engine_on_its_test_classpath()
            throws Exception {
        Path api = putJar("org/junit/jupiter/junit-jupiter-api/5.12.2/junit-jupiter-api-5.12.2.jar", "api");
        Path engine = putJar("org/junit/jupiter/junit-jupiter-engine/5.12.2/junit-jupiter-engine-5.12.2.jar", "engine");
        List<Lockfile.Artifact> rows = new ArrayList<>(lock.artifacts());
        rows.add(new Lockfile.Artifact(
                "org.junit.jupiter:junit-jupiter-api:jar:",
                "5.12.2",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(api),
                null,
                List.of(Scope.PROVIDED),
                List.of()));
        rows.add(new Lockfile.Artifact(
                "org.junit.jupiter:junit-jupiter-engine:jar:",
                "5.12.2",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(engine),
                null,
                List.of(Scope.TEST),
                List.of("org.junit.jupiter:junit-jupiter-api:jar:@5.12.2")));
        lock = lock.withArtifacts(rows);
        Files.writeString(library.resolve("jk.toml"), """
                group = "com.ex"
                name = "library"
                version = "1.0"

                [provided-dependencies]
                junit-jupiter-api = { group = "org.junit.jupiter", version = "5.12.2" }
                """);

        assertThat(classpath(library, ClasspathResolver.TEST))
                .as("the provided API is on the test classpath, so its engine is too, as under Surefire")
                .contains(jar(api), jar(engine), jar(launcher));
    }

    @Test
    void jk_tree_and_the_compile_classpath_name_the_same_artifacts() throws Exception {
        JkBuild build = JkBuildParser.parse(library.resolve("jk.toml"));
        String tree = DependencyTree.render(
                build, lock, library, 0, DependencyTreeStyle.Styling.plain(), true, List.of(Scope.MAIN));

        Set<String> drawn = new TreeSet<>();
        Matcher coord =
                Pattern.compile("\\b(com\\.(?:lib|web|test):[a-z]+):1\\.0\\b").matcher(tree);
        while (coord.find()) drawn.add(coord.group(1));
        Set<String> onClasspath = new TreeSet<>();
        for (ClasspathResolver.Entry entry :
                new ClasspathResolver(store).entriesFor(lock, ClasspathResolver.COMPILE_MAIN, false, build, library)) {
            onClasspath.add(
                    entry.artifact().moduleGroup() + ":" + entry.artifact().moduleArtifact());
        }

        assertThat(drawn).isEqualTo(onClasspath).containsExactly("com.lib:boot", "com.lib:core");
    }

    private List<Path> classpath(Path moduleDir, Set<Scope> scopes) throws Exception {
        JkBuild build = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
        return new ClasspathResolver(store).classpathFor(lock, scopes, false, build, moduleDir);
    }

    private static Path module(Path root, String name, String tables) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "1.0"

                %s""".formatted(name, tables));
        return dir;
    }

    private Path putJar(String relative, String payload) throws Exception {
        Path src = Files.writeString(store.resolve("src.bin"), payload);
        RepoArtifactStore.forStoreId(store, "central").materialize(relative, src, Hashing.sha256Hex(src));
        Files.deleteIfExists(src);
        return jar(store.resolve("repos/central").resolve(relative));
    }

    private static Path jar(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Lockfile.Artifact row(String module, Path jar, List<Scope> scopes, String... deps) throws Exception {
        return new Lockfile.Artifact(
                module + ":jar:",
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(jar),
                null,
                scopes,
                List.of(deps));
    }
}
