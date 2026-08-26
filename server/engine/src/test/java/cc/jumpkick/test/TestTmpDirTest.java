// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The temp root a forked test JVM writes under. {@code TestEnv} names it; these pin what happens to
 * it afterwards — that it exists before a worker starts, and that splitting the worker pool isolates
 * inside it rather than leaving for the host temp dir.
 */
class TestTmpDirTest {

    @TempDir
    Path tmp;

    @Test
    void the_root_is_created_so_the_first_createTempFile_cannot_fail() throws Exception {
        Path root = tmp.resolve("target/tmp");
        assertThat(root).doesNotExist();

        assertThat(TestTmpDir.ensure(root.toString())).isEqualTo(root);
        assertThat(root).isDirectory();
        // Idempotent: every module run calls this, and most find the directory already there.
        assertThat(TestTmpDir.ensure(root.toString())).isEqualTo(root);
    }

    @Test
    void no_configured_root_leaves_the_jvm_default_alone() {
        // JUnitLauncher is also driven directly, with no module layout to write under; inventing a
        // directory there would be worse than the default it is trying to replace.
        assertThat(TestTmpDir.ensure(null)).isNull();
        assertThat(TestTmpDir.ensure("  ")).isNull();
    }

    @Test
    void a_split_pool_isolates_workers_inside_the_module_root() throws Exception {
        // Isolated per worker AND still under target/ — the two are not in tension, and the
        // implementation this replaced bought the first by giving up the second, because
        // Files.createTempDirectory puts the worker's root under the host temp dir.
        Path root = Files.createDirectories(tmp.resolve("target/tmp"));

        Path w0 = TestTmpDir.forWorker(root, 0, 4);
        Path w1 = TestTmpDir.forWorker(root, 1, 4);

        assertThat(w0).isDirectory().isNotEqualTo(w1);
        assertThat(w1).isDirectory();
        assertThat(w0).startsWith(root);
        assertThat(w1).startsWith(root);
    }

    @Test
    void an_unsplit_pool_uses_the_module_root_itself() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("target/tmp"));
        assertThat(TestTmpDir.forWorker(root, 0, 1)).isEqualTo(root);
    }

    @Test
    void no_module_root_stays_no_module_root() {
        assertThat(TestTmpDir.forWorker(null, 0, 4)).isNull();
        assertThat(TestTmpDir.forWorker(null, 0, 1)).isNull();
    }
}
