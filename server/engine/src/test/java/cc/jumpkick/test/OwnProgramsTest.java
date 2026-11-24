// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A utility, or a program a test wrote, whose command names nothing outside the test's temp roots
 * and the system's own trees keys its class by its identity; one that can read the workspace, the
 * user's files or another program's settings is a program started.
 */
class OwnProgramsTest {

    @TempDir
    Path root;

    private Path module;
    private Path bin;

    @BeforeEach
    void layout() throws Exception {
        module = Files.createDirectories(root.resolve("ws/app"));
        Files.writeString(module.resolve("README"), "x");
        bin = Files.createDirectories(root.resolve("bin"));
        for (String program : List.of("mvn", "sleep", "sh")) {
            // On Windows a program on PATH carries one of PATHEXT's extensions.
            Path f = Files.writeString(bin.resolve(Os.isWindows() ? program + ".cmd" : program), "#!/bin/sh\n");
            f.toFile().setExecutable(true);
        }
        Files.createDirectories(temp().resolve("1"));
    }

    @Test
    void a_utility_naming_nothing_outside_the_tests_files_keys_by_its_name() {
        assertThat(tool("sleep 30", null)).isEqualTo("sleep");
        assertThat(tool("sh -c exit 0", null))
                .as("a shell running only its words")
                .isEqualTo("sh");
        assertThat(tool("setsid sleep 60", null))
                .as("a wrapper running a utility")
                .isEqualTo("setsid");
        assertThat(tool("sh -c trap '' INT HUP; exec \"$@\" sh sleep 5", null))
                .as("the command's own words passed on")
                .isEqualTo("sh");
        assertThat(tool("timeout 0.250 sha256sum /dev/zero", null))
                .as("a device")
                .isEqualTo("timeout");
        assertThat(tool("cat " + temp().resolve("1/out.txt"), null))
                .as("a file in a temp root")
                .isEqualTo("cat");
        assertThat(tool("cat out.txt", temp().resolve("1").toString()))
                .as("a relative file where the command runs in a temp root")
                .isEqualTo("cat");
        if (!Os.isWindows()) {
            assertThat(tool("/bin/sh -c exit 0", null))
                    .as("the administered shell")
                    .isEqualTo("sh");
        }
    }

    @Test
    void a_backslash_outside_windows_is_the_shells_escape_not_a_directory() {
        assumeFalse(Os.isWindows());
        assertThat(tool("sh -c head -c 200000 /dev/zero | tr '\\0' x", null))
                .as("an escape a filter reads")
                .isEqualTo("sh");
        assertThat(tool("sh -c cat \\README", null))
                .as("a file of the module, escaped")
                .isNull();
        assertThat(tool("sh -c cat \\/etc/passwd", temp().resolve("1").toString()))
                .as("a system file, escaped, from a temp root")
                .isNull();
    }

    @Test
    void a_program_the_test_wrote_keys_by_its_class() {
        assertThat(tool(temp().resolve("1/fake-tool") + " an arg", null)).isEqualTo(ClassReads.WRITTEN_PROGRAM);
        assertThat(tool("./fake-tool --flag", temp().resolve("1").toString()))
                .as("named relative to a temp root")
                .isEqualTo(ClassReads.WRITTEN_PROGRAM);
    }

    @Test
    void a_program_that_can_read_past_the_tests_own_files_is_a_program_started() {
        assertThat(tool("mvn package", temp().resolve("1").toString()))
                .as("a build tool reads its user's settings")
                .isNull();
        assertThat(tool("sh -c mvn package", temp().resolve("1").toString()))
                .as("a shell running a build tool")
                .isNull();
        assertThat(tool("git status", temp().resolve("1").toString()))
                .as("git is GitRuns'")
                .isNull();
        assertThat(tool("sh -c git status", temp().resolve("1").toString())).isNull();
        assertThat(tool("cat README", null)).as("a file of the module").isNull();
        assertThat(tool("cat *", null)).as("a wildcard in the module").isNull();
        assertThat(tool("ls .", null)).as("the module's own directory").isNull();
        assertThat(tool("cat ../../secret", temp().resolve("1").toString()))
                .as("out of the temp root")
                .isNull();
        assertThat(tool("cat " + module.resolve("README"), null))
                .as("a workspace path")
                .isNull();
        assertThat(tool("sh -c cat $HOME/.netrc", null)).as("a variable").isNull();
        assertThat(tool("sh -c cat `pwd`/x", null)).as("a command").isNull();
        assertThat(tool("sh -c cd; cat .profile", null))
                .as("a directory no word names")
                .isNull();
        assertThat(tool("cat ~/.profile", null)).as("the home").isNull();
        assertThat(tool("sh -c cat</etc/passwd", null))
                .as("a redirect from a file")
                .isNull();
        assertThat(tool("cat /etc/hosts", null)).as("a system file").isNull();
        assertThat(tool("sh -c curl https://example.com", temp().resolve("1").toString()))
                .as("another host")
                .isNull();
        assertThat(tool("node app.js", temp().resolve("1").toString()))
                .as("no utility")
                .isNull();
        assertThat(tool(module.resolve("run.sh").toString(), null))
                .as("a checked-in script")
                .isNull();
        assertThat(tool("./run.sh", null)).as("a script in the module").isNull();
    }

    @Test
    void an_installed_program_named_by_its_path_must_be_a_utility_too() {
        assumeFalse(Os.isWindows());
        assertThat(tool("setsid /bin/sleep 1", null)).isEqualTo("setsid");
        assertThat(tool("setsid /opt/graal/bin/native-image -cp app.jar app.Main", null))
                .as("a program outside the system's own directories")
                .isNull();
    }

    private @Nullable String tool(String command, @Nullable String directory) {
        return OwnPrograms.tool(command, directory, module, List.of(temp()), bin.toString());
    }

    private Path temp() {
        return root.resolve("tmp");
    }
}
