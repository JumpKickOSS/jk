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
 * A settled manifest is read once, however many times it is parsed.
 *
 * <p>The memo always returned the right answer; it just re-read the file on every lookup, because its
 * staleness stamp <em>was</em> the file's bytes. Nothing observable failed — which is why this asserts
 * the read count rather than the parse result. On a 31-module workspace `parse` fans out over every
 * sibling, so that one unconditional read became 260,971 read syscalls and 379 MB for a read-only
 * `jk status` (JK-1028).
 */
class JkBuildParserReadBudgetTest {

    @BeforeEach
    void clearCounters() {
        JkBuildParser.resetStats();
    }

    @Test
    void a_settled_manifest_is_read_once_however_often_it_is_parsed(@TempDir Path dir) throws IOException {
        Path toml = settledManifest(dir, "name = \"m\"\nversion = \"1.0\"\n");

        for (int i = 0; i < 40; i++) {
            assertThat(JkBuildParser.parseLocal(toml).project().name()).isEqualTo("m");
        }

        assertThat(JkBuildParser.parseRequests()).isEqualTo(40);
        assertThat(JkBuildParser.manifestReads())
                .as("40 parses of one settled manifest must cost one read, not 40")
                .isEqualTo(1);
    }

    @Test
    void an_edit_is_still_seen(@TempDir Path dir) throws IOException {
        Path toml = settledManifest(dir, "name = \"before\"\nversion = \"1.0\"\n");
        assertThat(JkBuildParser.parseLocal(toml).project().name()).isEqualTo("before");

        // A different length, so (size, mtime) alone would catch it.
        settledManifest(dir, "name = \"after-and-longer\"\nversion = \"1.0\"\n");
        assertThat(JkBuildParser.parseLocal(toml).project().name()).isEqualTo("after-and-longer");
    }

    @Test
    void a_same_length_edit_inside_one_mtime_tick_is_still_seen(@TempDir Path dir) throws IOException {
        // The hazard the byte-stamp existed for, and the reason the cheap stamp is two-tier: an editor
        // save landing in the same coarse tick, with the length unchanged. Both writes are left at
        // "now", so the file is inside the settle window and the stamp carries its bytes.
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, "name = \"aaa\"\nversion = \"1.0\"\n");
        assertThat(JkBuildParser.parseLocal(toml).project().name()).isEqualTo("aaa");

        Files.writeString(toml, "name = \"bbb\"\nversion = \"1.0\"\n");
        Files.setLastModifiedTime(toml, Files.getLastModifiedTime(toml)); // same tick, same length

        assertThat(JkBuildParser.parseLocal(toml).project().name())
                .as("a same-length edit inside the settle window must not serve the previous parse")
                .isEqualTo("bbb");
    }

    /** Write {@code body} and age its mtime past the settle window, so the cheap stamp applies. */
    private static Path settledManifest(Path dir, String body) throws IOException {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, body);
        Files.setLastModifiedTime(toml, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        return toml;
    }

    @Test
    void a_workspace_fan_out_reads_each_manifest_once(@TempDir Path dir) throws IOException {
        // The shape that produced the finding: `parse` resolves workspace inheritance, which reads the
        // root and every member, so resolving all N modules used to read O(N^2) manifests. With the
        // stamp cheap, each distinct file is read once no matter how many times it is visited.
        int members = 10;
        StringBuilder rootToml = new StringBuilder("group = \"g\"\nversion = \"1.0\"\nname = \"root\"\n[workspace]\nmodules = [");
        for (int i = 0; i < members; i++) {
            rootToml.append(i > 0 ? ", " : "").append('"').append("m").append(i).append('"');
        }
        rootToml.append("]\n");
        settledFile(dir.resolve("jk.toml"), rootToml.toString());
        for (int i = 0; i < members; i++) {
            Path md = dir.resolve("m" + i);
            Files.createDirectories(md);
            settledFile(md.resolve("jk.toml"), "name = \"m" + i + "\"\ngroup.workspace = true\nversion.workspace = true\n");
        }

        JkBuildParser.resetStats();
        for (int i = 0; i < members; i++) {
            JkBuildParser.parse(dir.resolve("m" + i).resolve("jk.toml"));
        }

        // 10 members + 1 root = 11 distinct manifests. Anything materially above that is the old
        // read-per-lookup behaviour returning.
        assertThat(JkBuildParser.manifestReads())
                .as("distinct manifests are %d; reads were %d", members + 1, JkBuildParser.manifestReads())
                .isLessThanOrEqualTo(members + 1L);
        assertThat(JkBuildParser.parseRequests())
                .as("the fan-out itself is unchanged — this ticket makes each request cheap, not rarer")
                .isGreaterThan(members);
    }

    private static void settledFile(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
    }
}
