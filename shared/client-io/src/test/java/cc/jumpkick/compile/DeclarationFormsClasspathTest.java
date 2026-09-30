// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every form a dependency can be declared in roots the module's classpath at the lock row
 * {@code jk lock} wrote for it: a local jar by its declared name, a git dependency by the row
 * stamped with its repository and ref, a path dependency by the coordinate its target publishes,
 * and the optional dependencies a path dependency's features activate.
 */
class DeclarationFormsClasspathTest {

    private static final String SHA = "ee2c14648ac8fd2e172f08523926f62acaf06768c8f3808357914dab231ec461";

    @TempDir
    Path tmp;

    @Test
    void a_local_jar_declared_by_sha256_is_on_the_classpath() throws Exception {
        Path app = project("app", """
                [dependencies]
                greeter = { sha256 = "%s", group = "lib", version = "1.0.0" }
                """.formatted(SHA));
        JkBuild build = JkBuildParser.parse(app.resolve("jk.toml"));
        String declared = only(build).module();
        Lockfile lock = lock(
                new Lockfile.Artifact(
                        declared, "1.0.0", "jk-local", "sha256:" + SHA, null, List.of(Scope.MAIN), List.of()),
                row("com.other:noise:jar:", List.of(Scope.MAIN)));

        assertThat(names(lock, ClasspathResolver.COMPILE_MAIN, build, app)).containsExactly(declared);
        assertThat(names(lock, ClasspathResolver.RUNTIME, build, app)).containsExactly(declared);
    }

    @Test
    void a_git_dependency_is_the_row_stamped_with_its_repository_and_ref() throws Exception {
        Path app = project("app", """
                [dependencies]
                codec = { git = "https://github.com/acme/codec", tag = "v0.9.1" }
                """);
        JkBuild build = JkBuildParser.parse(app.resolve("jk.toml"));
        GitSource git = Objects.requireNonNull(only(build).gitSource());
        Lockfile lock = lock(
                row("com.acme:codec:jar:", List.of(Scope.MAIN), "com.acme:codec-core:jar:@1.0")
                        .withGit(new Lockfile.Artifact.GitInfo(
                                git.canonicalUrl(), "abc123", git.ref().token())),
                row("com.acme:codec-core:jar:", List.of(Scope.MAIN)),
                row("com.acme:other:jar:", List.of(Scope.MAIN))
                        .withGit(new Lockfile.Artifact.GitInfo(git.canonicalUrl(), "def456", "tag=v2")));

        assertThat(names(lock, ClasspathResolver.COMPILE_MAIN, build, app))
                .containsExactly("com.acme:codec:jar:", "com.acme:codec-core:jar:");
    }

    @Test
    void a_path_dependency_is_the_coordinate_its_target_publishes_with_the_features_it_selects() throws Exception {
        project("widget", """
                [dependencies]
                mysql = { group = "com.mysql", name = "mysql-connector-j", version = "8.0.0", optional = true }

                [features]
                default = []
                mysql = { deps = ["mysql"] }
                """);
        Path app = project("app", """
                [dependencies]
                widget = { path = "../widget", features = ["mysql"] }
                """);
        JkBuild build = JkBuildParser.parse(app.resolve("jk.toml"));
        Lockfile lock = lock(
                row("com.example:widget:jar:", List.of(Scope.MAIN)),
                row("com.mysql:mysql-connector-j:jar:", List.of(Scope.MAIN)),
                row("com.other:noise:jar:", List.of(Scope.MAIN)));

        assertThat(names(lock, ClasspathResolver.COMPILE_MAIN, build, app))
                .containsExactly("com.example:widget:jar:", "com.mysql:mysql-connector-j:jar:");
    }

    @Test
    void a_path_dependency_on_a_maven_or_gradle_project_is_every_row_a_path_build_published() throws Exception {
        Files.createDirectories(tmp.resolve("foreign"));
        Files.writeString(tmp.resolve("foreign/pom.xml"), "<project/>");
        Path app = project("app", """
                [dependencies]
                foreign = { path = "../foreign" }
                """);
        JkBuild build = JkBuildParser.parse(app.resolve("jk.toml"));
        Lockfile lock = lock(
                new Lockfile.Artifact(
                        "com.foreign:lib:jar:",
                        "1.0",
                        "git:com.foreign:lib:1.0+file:///tmp/repo",
                        "sha256:" + SHA,
                        null,
                        List.of(Scope.MAIN),
                        List.of()),
                row("com.other:noise:jar:", List.of(Scope.MAIN)));

        assertThat(names(lock, ClasspathResolver.COMPILE_MAIN, build, app)).containsExactly("com.foreign:lib:jar:");
    }

    @Test
    void a_siblings_local_jar_rides_to_its_consumer() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = member(root, "lib", """
                [dependencies]
                greeter = { sha256 = "%s", group = "lib", version = "1.0.0" }
                """.formatted(SHA));
        Path app = member(root, "app", """
                [dependencies]
                lib = { workspace = true }
                """);
        String declared = only(JkBuildParser.parse(lib.resolve("jk.toml"))).module();
        Lockfile lock = lock(new Lockfile.Artifact(
                declared, "1.0.0", "jk-local", "sha256:" + SHA, null, List.of(Scope.MAIN), List.of()));

        assertThat(names(lock, ClasspathResolver.TEST, JkBuildParser.parse(app.resolve("jk.toml")), app))
                .containsExactly(declared);
    }

    private static List<String> names(Lockfile lock, Set<Scope> scopes, JkBuild build, Path dir) {
        return ClasspathResolver.moduleRows(lock, scopes, build, dir).stream()
                .map(Lockfile.Artifact::name)
                .toList();
    }

    private static Dependency only(JkBuild build) {
        return build.dependencies().of(Scope.MAIN).getFirst();
    }

    private Path project(String name, String tables) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "%s"
                version = "0.1.0"
                java = 25

                %s""".formatted(name, tables));
        return dir;
    }

    private static Path member(Path root, String name, String tables) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "1.0"

                %s""".formatted(name, tables));
        return dir;
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(rows));
    }

    private static Lockfile.Artifact row(String name, List<Scope> scopes, String... deps) {
        return new Lockfile.Artifact(
                name,
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + SHA,
                null,
                scopes,
                List.of(deps));
    }
}
