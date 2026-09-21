// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** A version's files arrive on different threads at once; every line survives. */
    @Test
    void concurrent_writes_for_one_version_keep_every_files_line(@TempDir Path dir) throws Exception {
        String[] files = {"widget-1.0.pom", "widget-1.0.jar", "widget-1.0.module", "widget-1.0-sources.jar"};
        for (int round = 0; round < 20; round++) {
            Files.deleteIfExists(dir.resolve("_remote.repositories"));
            List<Thread> threads = new ArrayList<>();
            for (String f : files) {
                threads.add(Thread.ofPlatform().start(() -> M2CompatWriter.writeRemoteRepositories(dir, "central", f)));
            }
            for (Thread t : threads) t.join();
            List<String> lines = Files.readAllLines(dir.resolve("_remote.repositories"));
            for (String f : files) {
                assertThat(lines).as("round " + round).contains(f + ">central=");
            }
        }
    }
}
