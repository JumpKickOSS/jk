// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forked test JVM starts in a directory the launcher chose: the module root, else
 * the sandbox's temp root, else the classes dir. It never inherits the engine
 * daemon's cwd, which is the product home's state directory.
 */
class JUnitLauncherWorkDirTest {

    @TempDir
    Path tmp;

    @Test
    void a_module_s_test_classes_dir_names_the_module() {
        Path module = tmp.resolve("ws/server/engine");
        assertThat(JUnitLauncher.inferModuleDir(module.resolve("target/test-classes")))
                .isEqualTo(module);
    }

    @Test
    void a_directory_jk_did_not_lay_out_is_not_a_module() {
        assertThat(JUnitLauncher.inferModuleDir(tmp.resolve("build/test-classes")))
                .isNull();
        assertThat(JUnitLauncher.inferModuleDir(tmp.resolve("target/classes"))).isNull();
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
