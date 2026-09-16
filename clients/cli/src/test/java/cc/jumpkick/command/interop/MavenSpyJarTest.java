// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Classpaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenSpyJarTest {

    @TempDir
    Path tmp;

    @Test
    void arguments_prepend_the_spy_and_the_events_file() {
        Path jar = Path.of("/lib/jk-maven-spy-1.jar");
        Path events = Path.of("/tmp/e.jsonl");
        assertThat(MavenSpyJar.arguments(jar, events, List.of("-q", "test")))
                .containsExactly(
                        "-Djk.mvn.events=/tmp/e.jsonl", "-Dmaven.ext.class.path=/lib/jk-maven-spy-1.jar", "-q", "test");
    }

    @Test
    void a_users_extension_path_keeps_its_entries_and_gains_the_spy() {
        Path jar = Path.of("/lib/spy.jar");
        List<String> out = MavenSpyJar.arguments(
                jar, Path.of("/tmp/e.jsonl"), List.of("-Dmaven.ext.class.path=/x/a.jar", "verify"));
        assertThat(out)
                .containsExactly(
                        "-Djk.mvn.events=/tmp/e.jsonl",
                        "-Dmaven.ext.class.path=/x/a.jar" + Classpaths.SEPARATOR + "/lib/spy.jar",
                        "verify");
    }

    @Test
    void the_override_wins_when_it_names_a_file_and_is_skipped_when_it_does_not() throws Exception {
        Path jar = Files.createFile(tmp.resolve("spy.jar"));
        assertThat(MavenSpyJar.locate(jar.toString(), "9.9.9"))
                .contains(jar.toAbsolutePath().normalize());
        assertThat(MavenSpyJar.locate(tmp.resolve("missing.jar").toString(), "9.9.9"))
                .isEmpty();
    }
}
