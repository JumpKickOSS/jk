// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class TestCommandTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @Test
    void test_with_no_test_sources_passes(@TempDir Path tempDir) throws Exception {
        scaffoldNoDeps(tempDir);
        int exit = run(
                "test",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    // (Removed test_without_lockfile_errors: `jk test` no longer requires a
    // pre-existing jk-lock.toml — the plan auto-locks like `jk build`/`run`.
    // That auto-lock path is covered by the build/run integration tests.)

    // A genuinely test-source-free project: bare manifest, no sources. (`jk new`
    // now scaffolds a sample CalcTest, so it can't stand in for "no tests".)
    private static void scaffoldNoDeps(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("jk.toml"),
                "group = \"com.example\"\nname = \"x\"\nversion = \"0.1.0\"\njdk = \"25\"\njava = 25\n");
        ScaffoldTestSupport.writeEmptyLock(dir); // jk test needs a lock; nothing to resolve
    }
}
