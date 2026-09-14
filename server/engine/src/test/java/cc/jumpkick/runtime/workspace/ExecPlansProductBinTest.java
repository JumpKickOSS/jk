// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.tool.AppLauncher;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module declaring {@code [install] product-bin} is jk's own client: its install plan links the
 * built native binary over the PATH entry of that name — the one name every other install is
 * refused under {@code bin/} — and beside it renders the JVM launcher {@code jk-jvm}, a {@code
 * java -cp} script over the thin jar's closure, which is the whole install where no native client
 * was built. That closure is the client module's own — its declared runtime dependencies walked
 * through the lock — not the workspace lock's whole runtime set.
 */
class ExecPlansProductBinTest {

    @Test
    void the_native_client_is_linked_over_the_path_entry_with_the_jvm_launcher_beside_it(@TempDir Path tmp)
            throws Exception {
        Path dir = client(tmp);
        Files.writeString(dir.resolve("target/jk"), "native client");
        Path bin = tmp.resolve("home/bin");

        ExecPlan plan = ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, bin, null);

        assertThat(plan.error()).isNull();
        assertThat(plan.linkSrcs())
                .containsExactly(dir.resolve("target/jk").toAbsolutePath().toString());
        assertThat(plan.linkDests()).containsExactly(bin.resolve("jk").toString());
        assertThat(plan.binPath()).isEqualTo(bin.resolve("jk").toString());
        assertThat(plan.launcherPath())
                .isEqualTo(bin.resolve(AppLauncher.launcherFileName("jk-jvm")).toString());
        assertThat(plan.launcherScript())
                .contains("cc.jumpkick.cli.Jk")
                .contains("-cp")
                .contains("jk-cli-0.1.0.jar");
    }

    @Test
    void without_a_built_native_client_the_jvm_launcher_is_the_whole_install(@TempDir Path tmp) throws Exception {
        Path dir = client(tmp);
        Path bin = tmp.resolve("bin");

        ExecPlan plan = ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, bin, null);

        assertThat(plan.error()).isNull();
        assertThat(plan.linkSrcs()).as("nothing to link over the PATH client").isEmpty();
        assertThat(plan.launcherPath())
                .isEqualTo(bin.resolve(AppLauncher.launcherFileName("jk-jvm")).toString());
        assertThat(plan.binPath()).isEqualTo(plan.launcherPath());
        assertThat(plan.launcherScript()).contains("cc.jumpkick.cli.Jk");
    }

    /**
     * A workspace lock is the union of every member's graph. The launcher lists what the client
     * itself runs on: its declared dependency and that dependency's own edge, never a sibling's
     * dependency the client never loads.
     */
    @Test
    void the_jvm_launcher_s_classpath_is_the_client_s_own_closure_not_the_workspace_lock(@TempDir Path tmp)
            throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path cli = putJar(store, "com/foo/cli-lib/1.0/cli-lib-1.0.jar");
        Path leaf = putJar(store, "com/foo/leaf/1.0/leaf-1.0.jar");
        Path noise = putJar(store, "com/other/noise/9.0/noise-9.0.jar");
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["cli", "other"]
                """);
        Path dir = client(root);
        Files.writeString(dir.resolve("jk.toml"), Files.readString(dir.resolve("jk.toml")) + """

                [dependencies]
                cli-lib = { group = "com.foo", version = "1.0" }
                """);
        Path other = Files.createDirectories(root.resolve("other"));
        Files.writeString(other.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "other"
                version = "0.1.0"
                java = 25

                [dependencies]
                noise = { group = "com.other", version = "9.0" }
                """);
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        List.of(
                                artifact("com.foo:cli-lib:jar:", "1.0", cli, List.of("com.foo:leaf:jar:@1.0")),
                                artifact("com.foo:leaf:jar:", "1.0", leaf, List.of()),
                                artifact("com.other:noise:jar:", "9.0", noise, List.of()))),
                root.resolve("jk-lock.toml"));

        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        try {
            ExecPlan plan =
                    ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, tmp.resolve("bin"), null);

            assertThat(plan.error()).isNull();
            assertThat(plan.launcherScript())
                    .contains("jk-cli-0.1.0.jar")
                    .contains(cli.toString())
                    .contains(leaf.toString())
                    .doesNotContain("noise-9.0.jar");
        } finally {
            if (prev != null) System.setProperty("jk.env.JK_STORE_DIR", prev);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }

    /**
     * A workspace sibling the client runs on is listed from the shelf once the install has put it
     * there; the checkout's {@code target/} jar is what the launcher reads only until then.
     */
    @Test
    void the_jvm_launcher_lists_a_shelved_sibling_from_the_shelf_not_the_checkout_s_target(@TempDir Path tmp)
            throws Exception {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["cli", "core"]
                """);
        Path dir = client(root);
        Files.writeString(dir.resolve("jk.toml"), Files.readString(dir.resolve("jk.toml")) + """

                [dependencies]
                core = { workspace = true }
                """);
        Path core = Files.createDirectories(root.resolve("core"));
        Files.writeString(core.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "core"
                version = "0.1.0"
                java = 25
                """);
        Path builtCore = root.resolve("target/core/lib/core-0.1.0.jar");
        Files.createDirectories(builtCore.getParent());
        Files.writeString(builtCore, "core built");
        Path unshelvedOnly = root.resolve("target/cli/lib/jk-cli-0.1.0.jar");
        Files.createDirectories(unshelvedOnly.getParent());
        Files.writeString(unshelvedOnly, "cli built");
        LockfileWriter.write(
                new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of()),
                root.resolve("jk-lock.toml"));

        String prevStore = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        try {
            ExecPlan before =
                    ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, tmp.resolve("bin"), null);
            assertThat(before.launcherScript())
                    .as("until the shelf has the sibling, its target/ jar is the entry")
                    .contains(builtCore.toAbsolutePath().normalize().toString());

            Path shelvedCore = putJar(store, "jk-local", "cc/jumpkick/core/0.1.0/core-0.1.0.jar");
            ExecPlan after =
                    ExecPlans.execPlan(dir, tmp.resolve("cache"), "install", null, null, tmp.resolve("bin"), null);
            assertThat(after.launcherScript())
                    .contains(shelvedCore.toString())
                    .doesNotContain(root.resolve("target/core").toString());
        } finally {
            restore("jk.env.JK_STORE_DIR", prevStore);
        }
    }

    private static void restore(String property, @Nullable String previous) {
        if (previous != null) System.setProperty(property, previous);
        else System.clearProperty(property);
    }

    private static Path putJar(Path store, String relative) throws Exception {
        return putJar(store, "central", relative);
    }

    private static Path putJar(Path store, String storeId, String relative) throws Exception {
        Path src = Files.writeString(store.resolve("src.bin"), relative);
        RepoArtifactStore.forStoreId(store, storeId).materialize(relative, src, Hashing.sha256Hex(src));
        Files.deleteIfExists(src);
        return store.resolve("repos/" + storeId)
                .resolve(relative)
                .toAbsolutePath()
                .normalize();
    }

    private static Lockfile.Artifact artifact(String name, String version, Path jar, List<String> deps)
            throws Exception {
        return new Lockfile.Artifact(
                name,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + Hashing.sha256Hex(jar),
                null,
                List.of(Scope.MAIN),
                deps);
    }

    private static Path client(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("cli"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "jk-cli"
                version = "0.1.0"
                java = 25

                [application]
                main = "cc.jumpkick.cli.Jk"

                [native]
                enabled = "always"
                name = "jk"

                [install]
                product-bin = "jk"
                """);
        Files.createDirectories(dir.resolve("target"));
        return dir;
    }
}
