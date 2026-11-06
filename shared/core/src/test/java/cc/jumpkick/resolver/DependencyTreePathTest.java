// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DependencyTree} draws a {@code path} dependency as the coordinate its target publishes. */
class DependencyTreePathTest {

    @Test
    void a_members_path_dependency_is_the_coordinate_its_target_publishes(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "9.9.9"

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "9.9.9"

                [dependencies]
                outside = { path = "../../outside" }
                """);
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("jk.toml"), """
                group = "com.out"
                name = "outside"
                version = "1.0"
                """);
        Lockfile lock = lockOf(pkg("com.out:outside", "1.0", List.of()));
        LockfileWriter.write(lock, root.resolve("jk-lock.toml"));

        for (Path dir : List.of(root, app)) {
            String rendered = DependencyTree.render(
                    JkBuildParser.parse(dir.resolve("jk.toml")),
                    lock,
                    dir,
                    Integer.MAX_VALUE,
                    DependencyTreeStyle.Styling.plain());
            assertThat(rendered).as(rendered).contains("com.out:outside:1.0").doesNotContain("(missing)");
        }
    }

    private static Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private static Lockfile.Artifact pkg(String module, String version, List<String> deps) {
        return new Lockfile.Artifact(
                module, version, "central+https://repo.maven.apache.org/maven2/", "sha256:dummy", null, deps);
    }
}
