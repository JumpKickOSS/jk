// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sibling only a member's tests depend on is in that member's test graph: {@code app} tests
 * against {@code fixtures}, whose tool needs leaf, and its own solve put leaf at 2.0 on its test
 * graph where the workspace holds 1.0 on main. The tool and leaf 2.0 are on {@code app}'s test
 * classpath and on none of its main ones; {@code lib}, which uses the tool from main, reads the
 * workspace's leaf.
 */
class TestOnlySiblingClasspathTest {

    @TempDir
    Path tmp;

    private Path store;
    private Path lockFile;
    private Path app;
    private Path lib;
    private Lockfile lock;
    private Path tool;
    private Path leaf1;
    private Path leaf2;

    @BeforeEach
    void workspace() throws Exception {
        store = Files.createDirectories(tmp.resolve("store"));
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["fixtures", "app", "lib"]
                """);
        lockFile = root.resolve("jk-lock.toml");
        module(root, "fixtures", """
                [dependencies]
                tool = { group = "com.foo", version = "1.0" }
                """);
        app = module(root, "app", """
                [test-dependencies]
                fixtures = { workspace = true }
                """);
        lib = module(root, "lib", """
                [dependencies]
                fixtures = { workspace = true }
                """);
        tool = putJar("com/foo/tool/1.0/tool-1.0.jar", "tool");
        leaf1 = putJar("com/foo/leaf/1.0/leaf-1.0.jar", "leaf1");
        leaf2 = putJar("com/foo/leaf/2.0/leaf-2.0.jar", "leaf2");
        lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        row("com.foo:tool", "1.0", tool, List.of(Scope.MAIN), "com.foo:leaf:jar:@1.0"),
                        row("com.foo:leaf", "1.0", leaf1, List.of(Scope.MAIN)),
                        row("com.foo:leaf", "2.0", leaf2, List.of(Scope.TEST)).withMembers(List.of("app"))));
    }

    @Test
    void a_test_only_siblings_dependencies_are_on_the_test_classpaths_at_the_members_versions() throws Exception {
        assertThat(classpath(app, ClasspathResolver.TEST)).containsExactly(jar(tool), jar(leaf2));
        assertThat(classpath(app, ClasspathResolver.COMPILE_TEST)).containsExactly(jar(tool), jar(leaf2));
    }

    @Test
    void a_test_only_siblings_dependencies_are_on_no_main_classpath() throws Exception {
        assertThat(classpath(app, ClasspathResolver.COMPILE_MAIN)).isEmpty();
        assertThat(classpath(app, ClasspathResolver.RUNTIME)).isEmpty();
        assertThat(classpath(app, ClasspathResolver.PROCESSOR_PATH)).isEmpty();
    }

    @Test
    void a_member_using_the_sibling_from_main_reads_the_workspaces_rows() throws Exception {
        assertThat(classpath(lib, ClasspathResolver.COMPILE_MAIN)).containsExactly(jar(tool), jar(leaf1));
        assertThat(classpath(lib, ClasspathResolver.TEST)).containsExactly(jar(tool), jar(leaf1));
    }

    private List<Path> classpath(Path moduleDir, Set<Scope> scopes) throws Exception {
        JkBuild build = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
        Lockfile view = MemberRows.view(lock, lockFile, moduleDir);
        return new ClasspathResolver(store).classpathFor(view, scopes, false, build, moduleDir);
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

    private static Lockfile.Artifact row(String module, String version, Path jar, List<Scope> scopes, String... deps)
            throws Exception {
        return new Lockfile.Artifact(
                module + ":jar:",
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(jar),
                null,
                scopes,
                List.of(deps));
    }
}
