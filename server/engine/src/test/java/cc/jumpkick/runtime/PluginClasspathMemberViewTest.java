// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
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

/**
 * The classpaths a plugin step or packager sees are the member's view of the workspace lock: a
 * partition row the member holds displaces the workspace's plain row of that coordinate, as it
 * does on the compile and test classpaths.
 */
class PluginClasspathMemberViewTest {

    private static final String PROTOBUF = "com.google.protobuf:protobuf-java:jar:";

    @Test
    void a_step_s_classpaths_read_the_member_s_partition_row_in_place_of_the_workspace_s(@TempDir Path tmp)
            throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["csi", "other"]
                """);
        Path csi = module(root, "csi");
        Path other = module(root, "other");
        Path lockFile = root.resolve("jk-lock.toml");
        Lockfile.Artifact workspaceRow = materialized(tmp, store, "2.5.0", Scope.MAIN, Scope.PROVIDED, Scope.TEST);
        Lockfile.Artifact csiRow =
                materialized(tmp, store, "3.25.5", Scope.MAIN, Scope.PROVIDED).withMembers(List.of("csi"));
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        List.of(workspaceRow, csiRow)),
                lockFile);
        Cas cas = new Cas(store);
        JkBuild csiBuild = JkBuildParser.parse(csi.resolve("jk.toml"));
        JkBuild otherBuild = JkBuildParser.parse(other.resolve("jk.toml"));

        assertThat(names(PluginBuild.productionClasspath(csi, cas, lockFile, csiBuild)))
                .as("the runtime classpath a step sees")
                .containsExactly("protobuf-java-3.25.5.jar");
        assertThat(names(PluginBuild.compileClasspath(csi, cas, lockFile, csiBuild)))
                .as("the compile classpath a step sees")
                .containsExactly("protobuf-java-3.25.5.jar");
        assertThat(PluginBuild.productionEntries(csi, cas, lockFile, csiBuild))
                .extracting(PluginBuild.ProdEntry::version)
                .as("the runtime entries a packager ships")
                .containsExactly("3.25.5");
        assertThat(PluginBuild.testRuntimeEntries(csi, cas, lockFile, csiBuild))
                .extracting(PluginBuild.ProdEntry::version)
                .as("the test runtime entries")
                .containsExactly("3.25.5");

        assertThat(names(PluginBuild.productionClasspath(other, cas, lockFile, otherBuild)))
                .as("every other member reads the workspace's row")
                .containsExactly("protobuf-java-2.5.0.jar");
        assertThat(PluginBuild.productionEntries(other, cas, lockFile, otherBuild))
                .extracting(PluginBuild.ProdEntry::version)
                .containsExactly("2.5.0");
    }

    private static Path module(Path root, String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                """.formatted(name));
        return dir;
    }

    private static List<String> names(List<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).toList();
    }

    /** One checksummed protobuf-java row whose jar is in {@code store} under the central repo layout. */
    private static Lockfile.Artifact materialized(Path tmp, Path store, String version, Scope... scopes)
            throws Exception {
        Path src = Files.writeString(tmp.resolve("protobuf-java-" + version + ".bin"), "protobuf-" + version);
        String hex = Hashing.sha256Hex(src);
        String relative = "com/google/protobuf/protobuf-java/" + version + "/protobuf-java-" + version + ".jar";
        RepoArtifactStore.forStoreId(store, "central").materialize(relative, src, hex);
        return new Lockfile.Artifact(
                PROTOBUF,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + hex,
                null,
                List.of(scopes),
                List.of());
    }
}
