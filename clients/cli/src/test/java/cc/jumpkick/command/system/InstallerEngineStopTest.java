// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The installers stop the old engine with output discarded, so the flags they pass must be ones
 * {@code jk engine stop} itself declares: a global flag of the same spelling parses, does nothing
 * for the stop, and leaves it waiting on the install lock with nothing on the terminal.
 */
class InstallerEngineStopTest {

    private static final Pattern FLAG = Pattern.compile("(?<![\\w-])(--?[a-zA-Z][\\w-]*)");

    @Test
    void the_installers_stop_the_engine_with_flags_the_stop_command_declares() throws IOException {
        Path repo = RepoRoot.find(InstallerEngineStopTest.class);
        List<String> declared = new EngineStopCommand()
                .options().stream().flatMap(o -> o.names().stream()).toList();
        assertThat(declared).contains("--now");

        String sh = Files.readString(repo.resolve("install.sh"));
        String ps1 = Files.readString(repo.resolve("install.ps1"));
        List<String> shLines =
                sh.lines().filter(l -> l.contains("run_jk engine stop")).toList();
        List<String> psLines =
                ps1.lines().filter(l -> l.contains("\"engine\", \"stop\"")).toList();
        assertThat(shLines).isNotEmpty();
        assertThat(psLines).isNotEmpty();

        for (String line : shLines) {
            String args = line.substring(line.indexOf("engine stop") + "engine stop".length());
            args = args.replaceAll(">.*", "");
            assertThat(flags(args)).as("install.sh: %s", line.strip()).isSubsetOf(declared);
        }
        for (String line : psLines) {
            String args = line.substring(line.indexOf("\"stop\"") + "\"stop\"".length());
            args = args.substring(0, args.indexOf(')'));
            assertThat(flags(args)).as("install.ps1: %s", line.strip()).isSubsetOf(declared);
        }
    }

    private static List<String> flags(String args) {
        Matcher m = FLAG.matcher(args);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
