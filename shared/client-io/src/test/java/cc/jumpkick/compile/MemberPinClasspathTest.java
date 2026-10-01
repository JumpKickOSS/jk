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
 * A member's exact pin is on that member's classpaths and no sibling's: {@code app} pins leaf 2.0
 * and reads its own row on its compile and test classpaths, {@code lib} reaches leaf through
 * middle and reads the workspace's 1.0, and the annotation processor path keeps the row its own
 * graph resolved.
 */
class MemberPinClasspathTest {

    @TempDir
    Path tmp;

    private Path store;
    private Path lockFile;
    private Path app;
    private Path lib;
    private Lockfile lock;
    private Path middle;
    private Path leaf1;
    private Path leaf2;
    private Path leaf3;
    private Path proc;

    @BeforeEach
    void workspace() throws Exception {
        store = Files.createDirectories(tmp.resolve("store"));
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["app", "lib"]
                """);
        lockFile = root.resolve("jk-lock.toml");
        app = module(root, "app", """
                [dependencies]
                leaf = { group = "com.foo", version = "2.0" }
                middle = { group = "com.foo", version = "1.0" }

                [processor-dependencies]
                proc = { group = "com.proc", version = "1.0" }
                """);
        lib = module(root, "lib", """
                [dependencies]
                middle = { group = "com.foo", version = "1.0" }
                """);
        middle = putJar("com/foo/middle/1.0/middle-1.0.jar", "middle");
        leaf1 = putJar("com/foo/leaf/1.0/leaf-1.0.jar", "leaf1");
        leaf2 = putJar("com/foo/leaf/2.0/leaf-2.0.jar", "leaf2");
        leaf3 = putJar("com/foo/leaf/3.0/leaf-3.0.jar", "leaf3");
        proc = putJar("com/proc/proc/1.0/proc-1.0.jar", "proc");
        lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        row("com.foo:middle", "1.0", middle, List.of(Scope.MAIN, Scope.TEST), "com.foo:leaf:jar:@1.0"),
                        row("com.foo:leaf", "1.0", leaf1, List.of(Scope.MAIN, Scope.TEST)),
                        row("com.foo:leaf", "3.0", leaf3, List.of(Scope.PROCESSOR)),
                        row("com.foo:leaf", "2.0", leaf2, List.of(Scope.MAIN)).withMembers(List.of("app")),
                        row("com.proc:proc", "1.0", proc, List.of(Scope.PROCESSOR), "com.foo:leaf:jar:@3.0")));
    }

    @Test
    void the_pinning_member_compiles_and_tests_against_its_own_version() throws Exception {
        assertThat(classpath(app, ClasspathResolver.COMPILE_MAIN)).containsExactly(jar(leaf2), jar(middle));
        assertThat(classpath(app, ClasspathResolver.TEST)).containsExactly(jar(leaf2), jar(middle));
    }

    @Test
    void a_sibling_that_does_not_pin_reads_the_workspaces_version() throws Exception {
        assertThat(classpath(lib, ClasspathResolver.COMPILE_MAIN)).containsExactly(jar(middle), jar(leaf1));
        assertThat(classpath(lib, ClasspathResolver.TEST)).containsExactly(jar(middle), jar(leaf1));
    }

    @Test
    void the_processor_path_keeps_its_own_graphs_row() throws Exception {
        assertThat(classpath(app, ClasspathResolver.PROCESSOR_PATH)).containsExactly(jar(proc), jar(leaf3));
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
