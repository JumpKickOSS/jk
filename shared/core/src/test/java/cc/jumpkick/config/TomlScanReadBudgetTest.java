// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A settled config file is read once, however many keys and callers ask for it.
 *
 * <p>Twenty-eight call sites scanned files with no memo between them, and seven asked the same file
 * the same question. `JkM2Config.resolve` sits on per-artifact paths, so a 500-artifact sync re-read
 * `~/.config/jk/config.toml` over a thousand times (JK-1033).
 */
class TomlScanReadBudgetTest {

    @BeforeEach
    void clear() {
        TomlScan.clearCache();
    }

    @Test
    void one_file_asked_many_different_questions_is_read_once(@TempDir Path dir) throws IOException {
        Path toml = aged(dir.resolve("config.toml"), """
                [engine]
                jobs = 4
                [cache]
                dir = "/tmp/c"
                [m2]
                local = "/tmp/m2"
                """);

        assertThat(TomlScan.scan(toml, "engine.jobs").get("engine.jobs")).isEqualTo("4");
        assertThat(TomlScan.scan(toml, "cache.dir").get("cache.dir")).isEqualTo("/tmp/c");
        assertThat(TomlScan.scan(toml, "m2.local").get("m2.local")).isEqualTo("/tmp/m2");
        for (int i = 0; i < 30; i++) {
            TomlScan.scan(toml, "engine.jobs");
        }

        assertThat(TomlScan.scans()).isEqualTo(33);
        assertThat(TomlScan.reads())
                .as("33 scans of one settled file, across three different key sets, must cost one read")
                .isEqualTo(1);
    }

    @Test
    void an_edit_is_still_seen(@TempDir Path dir) throws IOException {
        Path toml = aged(dir.resolve("config.toml"), "[engine]\njobs = 4\n");
        assertThat(TomlScan.scan(toml, "engine.jobs").get("engine.jobs")).isEqualTo("4");

        aged(toml, "[engine]\njobs = 16\n");

        assertThat(TomlScan.scan(toml, "engine.jobs").get("engine.jobs")).isEqualTo("16");
    }

    @Test
    void a_freshly_written_file_is_not_served_from_the_memo(@TempDir Path dir) throws IOException {
        // Inside the settle window size+mtime cannot be trusted — a same-length edit in one coarse
        // tick is invisible — so the memo is bypassed and the file re-read.
        Path toml = dir.resolve("config.toml");
        Files.writeString(toml, "[engine]\njobs = 4\n");
        assertThat(TomlScan.scan(toml, "engine.jobs").get("engine.jobs")).isEqualTo("4");

        Files.writeString(toml, "[engine]\njobs = 8\n"); // same length, likely same tick

        assertThat(TomlScan.scan(toml, "engine.jobs").get("engine.jobs"))
                .as("an unsettled file must not be served from a size+mtime stamp")
                .isEqualTo("8");
    }

    @Test
    void an_absent_file_costs_no_read(@TempDir Path dir) {
        assertThat(TomlScan.scan(dir.resolve("nope.toml"), "engine.jobs").hasKey("engine.jobs"))
                .isFalse();
        assertThat(TomlScan.reads()).isZero();
    }

    private static Path aged(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        return file;
    }
}
