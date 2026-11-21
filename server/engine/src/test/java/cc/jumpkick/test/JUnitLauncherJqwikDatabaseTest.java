// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jqwik keeps a failure-replay database, by default {@code .jqwik-database} in the working
 * directory — the module root, which every worker of a sharded suite shares. Each test JVM is
 * pointed at a database in its own temp root instead, so two workers never write one file.
 */
class JUnitLauncherJqwikDatabaseTest {

    @Test
    void every_worker_gets_a_database_in_its_own_temp_root(@TempDir Path tmp) throws Exception {
        JUnitLauncher launcher = new JUnitLauncher();
        Path moduleTmp = Files.createDirectories(tmp.resolve("target/tmp"));
        Path one = requireNonNull(TestTmpDir.forWorker(moduleTmp, 1, 2));
        Path two = requireNonNull(TestTmpDir.forWorker(moduleTmp, 2, 2));

        String first = only(launcher.jvmFlags(JvmRole.PULL_WORKER, 2, one));
        String second = only(launcher.jvmFlags(JvmRole.PULL_WORKER, 2, two));

        assertThat(first).isEqualTo("-Djqwik.database=" + one.resolve(".jqwik-database"));
        assertThat(second).isEqualTo("-Djqwik.database=" + two.resolve(".jqwik-database"));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void the_single_runner_and_discovery_write_under_the_module_temp_root(@TempDir Path tmp) {
        JUnitLauncher launcher = new JUnitLauncher();
        for (JvmRole role : JvmRole.values()) {
            assertThat(only(launcher.jvmFlags(role, 1, tmp)))
                    .as(role.name())
                    .isEqualTo("-Djqwik.database=" + tmp.resolve(".jqwik-database"));
        }
    }

    /** Without a temp root the launcher has no directory of its own to offer, so jqwik keeps its default. */
    @Test
    void no_temp_root_means_no_database_flag() {
        assertThat(new JUnitLauncher().jvmFlags(JvmRole.SUITE, 1, null)).noneMatch(f -> f.startsWith("-Djqwik."));
    }

    private static String only(List<String> flags) {
        List<String> hits =
                flags.stream().filter(f -> f.startsWith("-Djqwik.database=")).toList();
        assertThat(hits).hasSize(1);
        return hits.get(0);
    }
}
