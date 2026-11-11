// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * oxc installs from the lock the engine ships: every package it names is a registry tarball with a
 * sha512, and npm installs exactly that lock with {@code ci}, never resolving versions itself.
 */
class GraphScriptsTest {

    private static final Pattern PACKAGE =
            Pattern.compile("\"node_modules/([^\"]+)\":\\s*\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}", Pattern.DOTALL);

    @Test
    void the_staged_lock_pins_every_package_by_sha512(@TempDir Path tmp) throws Exception {
        GraphScripts.stage(tmp);

        assertThat(tmp.resolve("package.json")).content().contains("\"oxc-parser\"", "\"oxc-resolver\"");
        String lock = Files.readString(tmp.resolve("package-lock.json"));
        Matcher m = PACKAGE.matcher(lock);
        int packages = 0;
        while (m.find()) {
            packages++;
            assertThat(m.group(2))
                    .as(m.group(1))
                    .containsPattern("\"resolved\":\\s*\"https://registry\\.npmjs\\.org/")
                    .containsPattern("\"integrity\":\\s*\"sha512-");
        }
        assertThat(packages).isGreaterThanOrEqualTo(2);
        assertThat(lock).contains("\"node_modules/oxc-parser\"", "\"node_modules/oxc-resolver\"");
    }

    @Test
    void npm_installs_the_lock_and_resolves_nothing(@TempDir Path tmp) {
        List<String> command = GraphScripts.command(Path.of("npm"), tmp);

        assertThat(command.get(1)).isEqualTo("ci");
        assertThat(command).contains("--ignore-scripts").noneMatch(arg -> arg.contains("@"));
    }

    @Test
    void the_install_directory_moves_with_the_lock() throws Exception {
        assertThat(GraphScripts.lockId()).matches("[0-9a-f]{16}");
    }
}
