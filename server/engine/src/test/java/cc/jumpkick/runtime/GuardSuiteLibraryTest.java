// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The provisioned library: the workspace module in jk's own tree, else the jar in the store. */
class GuardSuiteLibraryTest {

    private static void workspace(Path root, boolean withLibraryModule) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = ["app"%s]
                """.formatted(withLibraryModule ? ", \"lib\"" : ""));
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/jk.toml"), "group = \"t\"\nname = \"app\"\nversion = \"0.0.1\"\n");
        if (withLibraryModule) {
            Files.createDirectories(root.resolve("lib"));
            Files.writeString(
                    root.resolve("lib/jk.toml"),
                    "group = \"cc.jumpkick\"\nname = \"jk-guards-junit\"\nversion = \"0.0.1\"\n");
        }
    }

    @Test
    void the_workspace_module_named_like_the_library_is_the_library_once_compiled(
            @TempDir Path root, @TempDir Path store) throws Exception {
        workspace(root, true);
        Cas cas = new Cas(store);
        // not compiled yet: no class directory, so the store is asked and has nothing
        assertThatThrownBy(() -> GuardSuiteLibrary.locate(root, cas))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-guards-junit")
                .hasMessageContaining("installLocal");
        Path classes = Files.createDirectories(root.resolve("target/lib/classes/main"));
        GuardSuiteLibrary.Located located = GuardSuiteLibrary.locate(root, cas);
        assertThat(located.path()).isEqualTo(classes);
        assertThat(located.jar()).as("a workspace module has no jar to pin").isNull();
    }

    @Test
    void a_project_without_the_module_reads_the_jar_from_the_local_store(@TempDir Path root, @TempDir Path store)
            throws Exception {
        workspace(root, false);
        Cas cas = new Cas(store);
        assertThat(GuardSuiteLibrary.stored(cas)).isNull();
        Path jar = Files.writeString(root.resolve("lib.jar"), "not really a jar");
        new RepoArtifactStore(store, RepoArtifactResolver.JK_LOCAL)
                .materialize(GuardSuiteLibrary.relativePath(), jar, Hashing.sha256Hex(jar));
        GuardSuiteLibrary.Located located = GuardSuiteLibrary.locate(root, cas);
        assertThat(located.jar()).isNotNull().isEqualTo(located.path());
        assertThat(located.path().toString().replace('\\', '/'))
                .endsWith("repos/jk-local/cc/jumpkick/jk-guards-junit/" + JkVersion.VERSION + "/jk-guards-junit-"
                        + JkVersion.VERSION + ".jar");
    }
}
