// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runtime closure a module packages: its own externals, its siblings' — and a shaded sibling's jar alone. */
class ModuleRuntimeClasspathTest {

    /**
     * A sibling whose fat jar relocates packages bundles its dependencies, so a consumer's runtime
     * closure carries that jar and none of the sibling's own rows; a plain sibling's externals ride.
     */
    @Test
    void a_relocating_sibling_contributes_its_shaded_jar_and_none_of_its_rows(@TempDir Path tmp) throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["shaded", "plain", "app"]
                """);
        module(root, "shaded", """
                [library]
                relocate = { "com.foo" = "com.ex.shaded.foo" }

                [dependencies]
                bundled = { group = "com.foo", name = "bundled", version = "1.0" }
                """);
        module(root, "plain", """
                [dependencies]
                riding = { group = "com.foo", name = "riding", version = "1.0" }
                """);
        Path app = module(root, "app", """
                [dependencies]
                shaded = { workspace = true }
                plain = { workspace = true }
                """);
        Path bundled = putJar(store, "com/foo/bundled/1.0/bundled-1.0.jar", "bundled");
        Path riding = putJar(store, "com/foo/riding/1.0/riding-1.0.jar", "riding");
        Path lockFile = root.resolve("jk-lock.toml");
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        List.of(row("com.foo:bundled", bundled), row("com.foo:riding", riding))),
                lockFile);
        JkBuild appBuild = JkBuildParser.parse(app.resolve("jk.toml"));
        BuildLayout shaded =
                BuildLayout.of(root.resolve("shaded"), JkBuildParser.parse(root.resolve("shaded/jk.toml")));
        BuildLayout plain = BuildLayout.of(root.resolve("plain"), JkBuildParser.parse(root.resolve("plain/jk.toml")));

        List<Path> jars = ModuleRuntimeClasspath.jars(app, appBuild, lockFile, new ClasspathResolver(store), p -> true);

        assertThat(jars)
                .contains(riding.toAbsolutePath().normalize(), shaded.assemblyJar(), plain.mainJar())
                .doesNotContain(bundled.toAbsolutePath().normalize(), shaded.mainJar());
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

    private static Path putJar(Path store, String relative, String payload) throws Exception {
        Path src = Files.writeString(store.resolve("src.bin"), payload);
        RepoArtifactStore.forStoreId(store, "central").materialize(relative, src, Hashing.sha256Hex(src));
        Files.deleteIfExists(src);
        return store.resolve("repos/central").resolve(relative);
    }

    private static Lockfile.Artifact row(String module, Path jar) throws Exception {
        return new Lockfile.Artifact(
                module + ":jar:",
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(jar),
                null,
                List.of(Scope.MAIN),
                List.of());
    }
}
