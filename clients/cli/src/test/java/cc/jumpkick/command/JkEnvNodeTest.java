// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.system.HookEnvCommand;
import cc.jumpkick.command.system.JkDiff;
import cc.jumpkick.command.toolchain.BashShell;
import cc.jumpkick.host.Os;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.lock.ToolchainPins;
import cc.jumpkick.node.PackageManagerShims;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shell hook puts the lock's Node.js and its package manager first on PATH, and takes them off. */
class JkEnvNodeTest {

    private static final String STORE = "jk.env.JK_STORE_DIR";
    private static final String VERSION = "24.99.0";

    @TempDir
    Path tmp;

    private @Nullable String priorStore;

    @BeforeEach
    void store() {
        priorStore = System.getProperty(STORE);
        System.setProperty(STORE, tmp.resolve("store").toString());
    }

    @AfterEach
    void restore() {
        if (priorStore == null) System.clearProperty(STORE);
        else System.setProperty(STORE, priorStore);
    }

    @Test
    void the_locked_node_and_its_manager_shims_are_exported(@TempDir Path project) throws Exception {
        Path nodeHome = tmp.resolve("store/tools/node").resolve(VERSION);
        Files.createDirectories(nodeHome.resolve("bin"));
        Path pnpmHome = tmp.resolve("store/tools/pnpm/10.18.1");
        Files.createDirectories(pnpmHome.resolve(PackageManagerShims.DIR));
        lock(project, "pnpm@10.18.1");

        Map<String, String> vars = JkEnv.nodeVars(ToolchainPins.scan(project));

        assertThat(vars)
                .containsEntry(JkEnv.NODE_HOME, nodeHome.toString())
                .containsEntry(
                        JkEnv.NODE_SHIMS,
                        pnpmHome.resolve(PackageManagerShims.DIR).toString());
        assertThat(JkEnv.nodeDirs(vars.get(JkEnv.NODE_HOME), vars.get(JkEnv.NODE_SHIMS)))
                .containsExactly(
                        pnpmHome.resolve(PackageManagerShims.DIR).toString(),
                        bin(nodeHome).toString());
    }

    @Test
    void a_node_nothing_has_installed_exports_nothing(@TempDir Path project) throws Exception {
        lock(project, null);
        assertThat(JkEnv.nodeVars(ToolchainPins.scan(project))).isEmpty();
    }

    @Test
    void the_hook_prepends_the_node_bin_and_strips_it_on_leaving() {
        String home = tmp.resolve("node").toString();
        String bin = bin(Path.of(home)).toString();
        String live = "/usr/local/bin" + File.pathSeparator + "/usr/bin";
        Map<String, String> env = new HashMap<>(Map.of("PATH", live));

        StringBuilder enter = new StringBuilder();
        HookEnvCommand.emit(
                new BashShell(),
                new JkEnv.Target(Optional.of(tmp), Map.of(JkEnv.NODE_HOME, home, JkEnv.PATH, live)),
                JkDiff.parse(""),
                env::get,
                enter);
        assertThat(exported(enter, JkEnv.NODE_HOME)).isEqualTo(home);
        assertThat(exported(enter, "PATH")).isEqualTo(bin + File.pathSeparator + live);

        // The shell now carries what the hook exported, and the diff that says it was jk's.
        env.put(JkEnv.NODE_HOME, home);
        env.put("PATH", exported(enter, "PATH"));
        StringBuilder leave = new StringBuilder();
        HookEnvCommand.emit(
                new BashShell(), JkEnv.Target.empty(), JkDiff.parse(exported(enter, "__JK_DIFF")), env::get, leave);
        assertThat(leave.toString()).contains("unset JK_NODE_HOME");
        assertThat(exported(leave, "PATH")).isEqualTo(live);
    }

    /** Where a Node.js home keeps {@code node}: its root on Windows, {@code bin/} elsewhere. */
    private static Path bin(Path home) {
        return Os.isWindows() ? home : home.resolve("bin");
    }

    /** The value {@code out} exports {@code key} as, its POSIX quoting undone. */
    private static String exported(CharSequence out, String key) {
        for (String line : out.toString().split("\n")) {
            if (!line.startsWith("export " + key + "=")) continue;
            String v = line.substring(("export " + key + "=").length());
            if (v.startsWith("'") && v.endsWith("'"))
                v = v.substring(1, v.length() - 1).replace("'\\''", "'");
            return v;
        }
        throw new AssertionError("no export of " + key + " in:\n" + out);
    }

    private static void lock(Path project, @Nullable String packageManager) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n");
        LockfileWriter.write(
                Lockfile.empty("1.0.0").withNode(new NodePin(VERSION, null, packageManager, Map.of())),
                project.resolve("jk-lock.toml"));
    }
}
