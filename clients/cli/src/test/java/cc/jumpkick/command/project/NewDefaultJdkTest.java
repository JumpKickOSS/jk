// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.engine.IsolatedState;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkInventory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk new} and the machine's default JDK, which the engine reads: each test has its own state
 * root, so the default it records there is the one its engine sees.
 */
@Tag("integration")
@IsolatedState
class NewDefaultJdkTest {

    @Test
    void a_project_targets_the_default_jdk_s_major_and_jdk_overrides_it(@TempDir Path tempDir) throws IOException {
        defaultJdk("temurin-21.0.7");
        Path demo = tempDir.resolve("demo");
        Run run = jk("new", "--no-module", demo.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(JkBuildParser.parse(demo.resolve("jk.toml")).project().java())
                .isEqualTo(21);

        Path pinned = tempDir.resolve("pinned");
        run = jk("new", "--jdk", "17", "--no-module", pinned.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(JkBuildParser.parse(pinned.resolve("jk.toml")).project().java())
                .isEqualTo(17);
    }

    @Test
    void a_module_keeps_the_workspace_s_java_whatever_the_default_jdk(@TempDir Path tempDir) throws IOException {
        defaultJdk("temurin-21.0.7");
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "ws"
                version  = "0.1.0"
                java     = 17

                [workspace]
                modules = []
                """);
        Path app = tempDir.resolve("app");
        Run run = jk("new", app.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(Files.readString(app.resolve("jk.toml"))).contains("java = 17");

        // A workspace that names no level leaves its module the template's own.
        Path plain = Files.createDirectories(tempDir.resolve("plain"));
        Files.writeString(plain.resolve("jk.toml"), """
                group    = "com.example"
                name     = "plain"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);
        Path lib = plain.resolve("lib");
        run = jk("new", lib.toString());
        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(Files.readString(lib.resolve("jk.toml"))).contains("java = 25");
    }

    /**
     * Records {@code id} as the machine's default JDK in this test's state root. Its home is the
     * running JDK, so anything that runs the default gets a real one.
     */
    private static void defaultJdk(String id) throws IOException {
        JdkInventory.current().setDefault(new InstalledJdk(id, Path.of(System.getProperty("java.home"))));
    }

    private record Run(int exit, String err) {}

    private static Run jk(String... argv) {
        int[] exit = new int[1];
        String err = Capture.stderr(() -> Capture.stdout(() -> exit[0] = Jk.execute(argv)));
        return new Run(exit[0], TestAnsi.strip(err).strip());
    }
}
