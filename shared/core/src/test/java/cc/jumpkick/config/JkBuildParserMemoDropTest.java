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
 * The idle engine empties the manifest memos: {@link JkBuildParser#dropMemos} says how many
 * entries went, and the next parse of a dropped file costs a read again.
 */
class JkBuildParserMemoDropTest {

    @BeforeEach
    void startClean() {
        JkBuildParser.dropMemos();
        JkBuildParser.resetStats();
    }

    @Test
    void dropping_the_memos_counts_what_went_and_the_next_parse_reads_again(@TempDir Path dir) throws IOException {
        Path toml = settledManifest(dir, "name = \"m\"\nversion = \"1.0\"\n");
        JkBuildParser.parseLocal(toml);
        JkBuildParser.parseLocal(toml);
        assertThat(JkBuildParser.manifestReads())
                .as("the memo served the second parse")
                .isEqualTo(1);

        assertThat(JkBuildParser.dropMemos())
                .as("one manifest is memoized twice: its parse and its document")
                .isEqualTo(2);
        assertThat(JkBuildParser.dropMemos()).as("nothing left to drop").isZero();

        JkBuildParser.parseLocal(toml);
        assertThat(JkBuildParser.manifestReads())
                .as("a dropped manifest is read again")
                .isEqualTo(2);
    }

    private static Path settledManifest(Path dir, String body) throws IOException {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, body);
        Files.setLastModifiedTime(toml, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        return toml;
    }
}
