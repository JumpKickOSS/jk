// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The provenance hint keeps one line per file, so a jar and its POM are both on record. */
class M2CompatWriterTest {

    @Test
    void the_hint_keeps_a_line_per_file_and_replaces_a_files_own(@TempDir Path dir) throws Exception {
        M2CompatWriter.writeRemoteRepositories(dir, "central", "widget-1.0.jar");
        M2CompatWriter.writeRemoteRepositories(dir, "central", "widget-1.0.pom");
        M2CompatWriter.writeRemoteRepositories(dir, "nexus", "widget-1.0.jar");

        assertThat(Files.readAllLines(dir.resolve("_remote.repositories")))
                .containsExactly(
                        "#NOTE: This is a jk-written provenance hint for Maven tooling.",
                        "widget-1.0.pom>central=",
                        "widget-1.0.jar>nexus=");
    }
}
