// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void the_workspace_module_is_pinned_by_its_path_whatever_jar_is_stored(@TempDir Path root, @TempDir Path store)
            throws Exception {
        workspace(root, true);
        Cas cas = new Cas(store);
        Lockfile.PluginEntry pin = requireNonNull(GuardSuiteLibrary.pin(root, cas));
        assertThat(pin.coordinate()).isEqualTo(GuardSuiteLibrary.COORDINATE);
        assertThat(pin.version())
                .as("the module's own version, not the running jk's")
                .isEqualTo("0.0.1");
        assertThat(pin.path()).isEqualTo("lib");
        assertThat(pin.checksum())
                .as("a module's identity is its source: no jar digest")
                .isNull();
        String rendered = LockfileWriter.render(Lockfile.empty("0.0.1").withPlugins(List.of(pin)));

        // A staged jar, then a rebuilt one with different bytes: the row does not move.
        Path jar = Files.writeString(root.resolve("lib.jar"), "first build");
        RepoArtifactStore local = new RepoArtifactStore(store, RepoArtifactResolver.JK_LOCAL);
        local.materialize(GuardSuiteLibrary.relativePath(), jar, Hashing.sha256Hex(jar));
        assertThat(GuardSuiteLibrary.pin(root, cas)).isEqualTo(pin);
        Files.writeString(jar, "second build, other bytes");
        local.materialize(GuardSuiteLibrary.relativePath(), jar, Hashing.sha256Hex(jar));
        Lockfile.PluginEntry again = GuardSuiteLibrary.pin(root, cas);
        assertThat(again).isEqualTo(pin);
        assertThat(LockfileWriter.render(Lockfile.empty("0.0.1").withPlugins(List.of(again))))
                .isEqualTo(rendered);
    }

    @Test
    void a_project_without_the_module_pins_the_stored_jar_by_digest(@TempDir Path root, @TempDir Path store)
            throws Exception {
        workspace(root, false);
        Cas cas = new Cas(store);
        assertThat(GuardSuiteLibrary.pin(root, cas))
                .as("nothing stored: nothing to pin")
                .isNull();

        Path jar = Files.writeString(root.resolve("lib.jar"), "provisioned bytes");
        RepoArtifactStore local = new RepoArtifactStore(store, RepoArtifactResolver.JK_LOCAL);
        local.materialize(GuardSuiteLibrary.relativePath(), jar, Hashing.sha256Hex(jar));
        Lockfile.PluginEntry pin = requireNonNull(GuardSuiteLibrary.pin(root, cas));
        assertThat(pin.isWorkspace()).isFalse();
        assertThat(pin.path()).isNull();
        assertThat(pin.version()).isEqualTo(JkVersion.VERSION);
        assertThat(pin.sha256Hex()).isEqualTo(Hashing.sha256Hex(jar));

        // The digest follows the bytes: a jar that changes is a different pin.
        Files.writeString(jar, "republished bytes");
        local.materialize(GuardSuiteLibrary.relativePath(), jar, Hashing.sha256Hex(jar));
        Lockfile.PluginEntry republished = requireNonNull(GuardSuiteLibrary.pin(root, cas));
        assertThat(republished.sha256Hex()).isEqualTo(Hashing.sha256Hex(jar)).isNotEqualTo(pin.sha256Hex());
    }
}
