// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedStore;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jdk.JdkEnsure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * No build-kind verb installs a JDK on the client any more: a pinned JDK installs in the engine's
 * ensure-jdk steps, as part of the run (G118 fences the client side). Under {@code --offline}, with
 * a pin no registry holds and the test's own store, a verb fails inside the run, compiles nothing
 * and leaves the JDK root empty. {@code jk explain} is read-only: it prices the install and
 * downloads nothing. The run reaches its lock before its JDK, and an offline lock against an empty
 * store fails first, so the refusal message is not asserted, only that nothing was installed.
 */
@Tag("integration")
@IsolatedStore
class JdkInEngineVerbsTest {

    @BeforeEach
    @AfterEach
    void forgetRegistries() {
        JdkEnsure.resetSharedRegistries();
    }

    @ParameterizedTest
    @ValueSource(strings = {"build", "test", "run", "install", "dev"})
    void the_verb_installs_no_jdk_on_the_client_and_compiles_nothing_without_it(String verb, @TempDir Path tmp)
            throws IOException {
        Path project = pinnedProject(tmp);
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        String previous = System.getProperty("jk.env.JK_JDKS_DIR");
        System.setProperty("jk.env.JK_JDKS_DIR", jdks.toString());
        int[] exit = new int[1];
        Capture.Streams console;
        try {
            List<String> argv = new ArrayList<>(List.of(verb, "-C", project.toString(), "--offline"));
            if (!verb.equals("install")) argv.addAll(List.of("--jdks-dir", jdks.toString()));
            if (!verb.equals("dev"))
                argv.addAll(List.of("--cache-dir", tmp.resolve("cache").toString()));
            console = Capture.both(() -> exit[0] = run(argv.toArray(String[]::new)));
        } finally {
            if (previous == null) System.clearProperty("jk.env.JK_JDKS_DIR");
            else System.setProperty("jk.env.JK_JDKS_DIR", previous);
        }

        assertThat(exit[0]).as(verb + " exit").isNotEqualTo(0);
        assertThat(console.out() + console.err()).as(verb + " output").contains("Failure");
        assertThat(project.resolve("target/classes/example/Main.class"))
                .as(verb + " compiled nothing")
                .doesNotExist();
        assertThat(jdks).as(verb + " installed nothing").isEmptyDirectory();
    }

    /** {@code jk explain} forecasts the run, JDK install included, and installs nothing itself. */
    @Test
    void explain_prices_the_install_and_downloads_nothing(@TempDir Path tmp) throws IOException {
        Path project = pinnedProject(tmp);
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        String previous = System.getProperty("jk.env.JK_JDKS_DIR");
        System.setProperty("jk.env.JK_JDKS_DIR", jdks.toString());
        Capture.Streams console;
        try {
            console = Capture.both(() -> run(
                    "explain",
                    "-C",
                    project.toString(),
                    "--offline",
                    "--jdks-dir",
                    jdks.toString(),
                    "--cache-dir",
                    tmp.resolve("cache").toString()));
        } finally {
            if (previous == null) System.clearProperty("jk.env.JK_JDKS_DIR");
            else System.setProperty("jk.env.JK_JDKS_DIR", previous);
        }

        assertThat(console.out() + console.err()).doesNotContain("offline: refusing outbound request");
        assertThat(jdks).isEmptyDirectory();
    }

    /** An application pinned to a JDK no host is likely to carry, with one source so every verb has work. */
    private static Path pinnedProject(Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                name = "app"
                group = "example"
                version = "0.1.0"
                java = 21
                jdk = "=dragonwell-21"

                [application]
                main = "example.Main"
                """);
        Path src = project.resolve("src/main/java/example/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example;\npublic class Main { public static void main(String[] a) {} }\n");
        return project;
    }
}
