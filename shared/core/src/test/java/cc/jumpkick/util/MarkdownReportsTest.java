// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A report jk writes for a person carries the mark PowerShell 5.1 needs to decode it as UTF-8. */
class MarkdownReportsTest {

    private static final byte[] MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Test
    void a_report_starts_with_the_utf8_mark_and_then_its_text(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("jk-results.md");
        MarkdownReports.write(file, "# jk results — OK\n\n60.0% lines · 50.0% branches\n");

        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).startsWith(MARK);
        // Past the mark the bytes are the text itself, in UTF-8 — the mark is not an encoding change.
        assertThat(new String(bytes, MARK.length, bytes.length - MARK.length, StandardCharsets.UTF_8))
                .isEqualTo("# jk results — OK\n\n60.0% lines · 50.0% branches\n");
    }

    @Test
    void a_rewrite_leaves_one_mark(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("jk-results.md");
        MarkdownReports.write(file, "first\n");
        MarkdownReports.write(file, "second\n");

        assertThat(Files.readAllBytes(file)).startsWith(MARK);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(MarkdownReports.BOM + "second\n");
    }

    @Test
    void strip_takes_the_mark_off_once_and_leaves_unmarked_text_alone() {
        assertThat(MarkdownReports.strip(MarkdownReports.BOM + "# jk results")).isEqualTo("# jk results");
        assertThat(MarkdownReports.strip("# jk results")).isEqualTo("# jk results");
        assertThat(MarkdownReports.strip(MarkdownReports.BOM + MarkdownReports.BOM + "x"))
                .as("a second mark is content, not a second header")
                .isEqualTo(MarkdownReports.BOM + "x");
        assertThat(MarkdownReports.strip("")).isEmpty();
    }
}
