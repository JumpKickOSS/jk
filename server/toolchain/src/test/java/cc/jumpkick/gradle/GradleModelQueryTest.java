// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.mvn.PomImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model query runs the Gradle launcher it is handed in a fork with the init script, returns the
 * JSON the fork writes, and stops a fork whose output stands still for the stall window.
 */
@DisabledOnOs(OS.WINDOWS)
class GradleModelQueryTest {

    @Test
    void the_model_the_fork_writes_is_returned_and_the_scratch_directory_removed(@TempDir Path tmp) throws Exception {
        Path launcher = script(tmp, """
                #!/bin/sh
                for a in "$@"; do
                  case "$a" in -Pjk.import.out=*) out="${a#-Pjk.import.out=}";; esac
                done
                echo "> Configure project :"
                printf '{"gradle":"9.5.1","rootName":"fixture","settingsRepositories":[],"projects":[]}' > "$out"
                """);
        List<String> progress = new ArrayList<>();
        GradleModelQuery query = new GradleModelQuery(
                (root, notes) ->
                        new GradleModelQuery.Launch(List.of(launcher.toString()), null, "Gradle 9.5.1 on JDK 25"),
                tmp.resolve("tmp"),
                Clock.SYSTEM,
                60_000L);

        String json = query.read(tmp, List.of("java"), progress::add);

        assertThat(json).contains("\"rootName\":\"fixture\"");
        assertThat(progress)
                .anySatisfy(
                        p -> assertThat(p).contains("Gradle 9.5.1 on JDK 25").contains("evaluating"));
        try (Stream<Path> left = Files.list(tmp.resolve("tmp"))) {
            assertThat(left).as("the scratch directory is removed").isEmpty();
        }
    }

    @Test
    void the_init_script_the_task_and_the_probed_plugins_ride_the_command(@TempDir Path tmp) throws Exception {
        Path args = tmp.resolve("args.txt");
        Path launcher = script(tmp, """
                #!/bin/sh
                for a in "$@"; do
                  echo "$a" >> "%s"
                  case "$a" in -Pjk.import.out=*) out="${a#-Pjk.import.out=}";; esac
                done
                printf '{"projects":[]}' > "$out"
                """.formatted(args));
        GradleModelQuery query = new GradleModelQuery(
                (root, notes) -> new GradleModelQuery.Launch(List.of(launcher.toString()), null, "Gradle"),
                tmp.resolve("tmp"),
                Clock.SYSTEM,
                60_000L);

        query.read(tmp, List.of("java", "org.springframework.boot"), note -> {});

        List<String> seen = Files.readAllLines(args);
        assertThat(seen).contains("--init-script", "--no-daemon", "--console=plain", ":jkImportModel");
        assertThat(seen).contains("-Pjk.import.plugins=java,org.springframework.boot");
        assertThat(seen).anySatisfy(a -> assertThat(a).startsWith("-Pjk.import.out="));
        int init = seen.indexOf("--init-script");
        assertThat(seen.get(init + 1)).endsWith("jk-import-model.init.gradle");
    }

    @Test
    void a_failing_fork_is_refused_with_what_went_wrong(@TempDir Path tmp) throws Exception {
        Path launcher = script(tmp, """
                #!/bin/sh
                echo "> Configure project :"
                echo ""
                echo "FAILURE: Build failed with an exception."
                echo ""
                echo "* Where:"
                echo "Build file 'build.gradle' line: 7"
                echo ""
                echo "* What went wrong:"
                echo "Plugin [id: 'com.acme.house'] was not found in any of the following sources:"
                echo ""
                echo "* Try:"
                echo "> Run with --stacktrace option to get the stack trace."
                exit 1
                """);
        GradleModelQuery query = new GradleModelQuery(
                (root, notes) ->
                        new GradleModelQuery.Launch(List.of(launcher.toString()), null, "Gradle 9.5.1 on JDK 25"),
                tmp.resolve("tmp"),
                Clock.SYSTEM,
                60_000L);

        assertThatThrownBy(() -> query.read(tmp, List.of("java"), note -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Gradle exited 1")
                .hasMessageContaining("Plugin [id: 'com.acme.house'] was not found")
                .hasMessageNotContaining("--stacktrace");
    }

    @Test
    void a_fork_whose_output_stands_still_is_stopped_within_the_budget(@TempDir Path tmp) throws Exception {
        Path launcher = script(tmp, """
                #!/bin/sh
                echo "Download https://plugins.gradle.org/m2/com/acme/house/1.0/house-1.0.pom"
                sleep 60
                """);
        GradleModelQuery query = new GradleModelQuery(
                (root, notes) ->
                        new GradleModelQuery.Launch(List.of(launcher.toString()), null, "Gradle 9.5.1 on JDK 25"),
                tmp.resolve("tmp"),
                Clock.SYSTEM,
                400L);

        long started = System.nanoTime();
        assertThatThrownBy(() -> query.read(tmp, List.of("java"), note -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(PomImporter.BUDGET_EXCEEDED)
                .hasMessageContaining("no Gradle output for")
                .hasMessageContaining("Gradle 9.5.1 on JDK 25")
                .hasMessageContaining("house-1.0.pom");
        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(20);
    }

    @Test
    void a_fork_that_writes_no_model_is_refused(@TempDir Path tmp) throws Exception {
        Path launcher = script(tmp, """
                #!/bin/sh
                echo "BUILD SUCCESSFUL in 1s"
                """);
        GradleModelQuery query = new GradleModelQuery(
                (root, notes) -> new GradleModelQuery.Launch(List.of(launcher.toString()), null, "Gradle"),
                tmp.resolve("tmp"),
                Clock.SYSTEM,
                60_000L);

        assertThatThrownBy(() -> query.read(tmp, List.of("java"), note -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("without writing the project model");
    }

    private static Path script(Path dir, String body) throws IOException {
        Path file = dir.resolve("gradle");
        Files.writeString(file, body);
        Files.setPosixFilePermissions(
                file,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return file;
    }
}
