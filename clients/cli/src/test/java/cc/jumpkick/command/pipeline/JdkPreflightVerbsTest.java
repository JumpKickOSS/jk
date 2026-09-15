// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every build-kind verb runs the JDK pre-flight before anything else it does. The observable is
 * the pre-flight's own failure: a pin no registry holds, under {@code --offline}, cannot fetch the
 * feed — so the verb stops on the {@code JDK} fail wedge naming the offline refusal, ahead of any
 * build, without a byte of network.
 */
@Tag("integration")
class JdkPreflightVerbsTest {

    @BeforeEach
    @AfterEach
    void forgetRegistries() {
        JdkEnsure.resetSharedRegistries();
    }

    @ParameterizedTest
    @ValueSource(strings = {"build", "test", "run", "install", "dev", "explain"})
    void the_verb_pre_flights_the_pinned_jdk_before_it_builds(String verb, @TempDir Path tmp) throws IOException {
        Path project = pinnedProject(tmp);
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        String previous = System.getProperty("jk.env.JK_JDKS_DIR");
        System.setProperty("jk.env.JK_JDKS_DIR", jdks.toString());
        int[] exit = new int[1];
        String err;
        try {
            List<String> argv = new ArrayList<>(List.of(verb, "-C", project.toString(), "--offline"));
            if (!verb.equals("install")) argv.addAll(List.of("--jdks-dir", jdks.toString()));
            if (!verb.equals("dev"))
                argv.addAll(List.of("--cache-dir", tmp.resolve("cache").toString()));
            err = Capture.stderr(() -> exit[0] = run(argv.toArray(String[]::new)));
        } finally {
            if (previous == null) System.clearProperty("jk.env.JK_JDKS_DIR");
            else System.setProperty("jk.env.JK_JDKS_DIR", previous);
        }

        assertThat(exit[0]).as(verb + " exit").isNotEqualTo(0);
        // The client's own JDK wedge, one line — not the engine's ensure-jdk error under a build wedge.
        assertThat(err.lines())
                .as(verb + " stderr")
                .anyMatch(line -> line.contains("JDK") && line.contains("offline: refusing outbound request"));
        assertThat(project.resolve("target")).as(verb + " built nothing").doesNotExist();
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
