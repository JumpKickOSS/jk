// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forked test JVM starts in a directory the launcher chose: the module root under either jk
 * layout, else the sandbox's temp root, else the classes dir. It never inherits the engine
 * daemon's cwd, which is the product home's state directory.
 */
class JUnitLauncherWorkDirTest {

    @TempDir
    Path tmp;

    @Test
    void a_standalone_module_s_classes_dir_names_the_module_root() {
        Path module = tmp.resolve("app");
        assertThat(JUnitLauncher.inferModuleDir(module.resolve("target/classes/test")))
                .isEqualTo(module);
    }

    @Test
    void a_workspace_member_s_classes_dir_under_the_central_out_tree_names_the_member() throws Exception {
        Path member = Files.createDirectories(tmp.resolve("ws/server/engine"));
        Path classes = tmp.resolve("ws/target/server/engine/classes/test");
        assertThat(JUnitLauncher.inferModuleDir(classes)).isEqualTo(member);
    }

    @Test
    void a_central_out_path_whose_member_directory_does_not_exist_is_not_a_module() {
        assertThat(JUnitLauncher.inferModuleDir(tmp.resolve("ws/target/ghost/classes/test")))
                .isNull();
    }

    @Test
    void a_layout_with_no_target_ancestor_is_not_jk_s() {
        assertThat(JUnitLauncher.inferModuleDir(tmp.resolve("build/classes/test")))
                .isNull();
        assertThat(JUnitLauncher.inferModuleDir(tmp.resolve("target/classes/main")))
                .isNull();
        assertThat(JUnitLauncher.inferModuleDir(null)).isNull();
    }

    @Test
    void the_work_dir_falls_back_from_module_to_sandbox_tmp_to_classes_dir() {
        Path module = tmp.resolve("module");
        Path sandbox = tmp.resolve("sandbox-tmp");
        Path classes = tmp.resolve("classes");
        assertThat(JUnitLauncher.workDir(module, sandbox, classes)).isEqualTo(module);
        assertThat(JUnitLauncher.workDir(null, sandbox, classes)).isEqualTo(sandbox);
        assertThat(JUnitLauncher.workDir(null, null, classes)).isEqualTo(classes);
    }

    @Test
    void before_run_the_launcher_sets_no_user_dir() {
        assertThat(new JUnitLauncher().jvmFlags(JvmRole.SUITE, 1, null)).noneMatch(f -> f.startsWith("-Duser.dir="));
    }
}
