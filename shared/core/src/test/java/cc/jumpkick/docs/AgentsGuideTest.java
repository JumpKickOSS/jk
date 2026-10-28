// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.docs.AgentsGuide.Change;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** jk owns the text between its markers in {@code AGENTS.md} and nothing else. */
class AgentsGuideTest {

    @Test
    void a_missing_file_is_created_with_a_title_and_the_section(@TempDir Path dir) throws IOException {
        assertThat(AgentsGuide.sync(dir)).isEqualTo(Change.CREATED);
        assertThat(read(dir)).isEqualTo(AgentsGuide.newFile()).startsWith("# AGENTS.md\n\n" + AgentsGuide.BEGIN);
        assertThat(AgentsGuide.sync(dir)).isEqualTo(Change.UNCHANGED);
    }

    @Test
    void the_section_goes_below_an_existing_title_and_keeps_the_projects_text(@TempDir Path dir) throws IOException {
        String next = "# AGENTS.md\n\n<!-- BEGIN:nextjs-agent-rules -->\nread the bundled docs\n"
                + "<!-- END:nextjs-agent-rules -->\n";
        write(dir, next);

        assertThat(AgentsGuide.sync(dir)).isEqualTo(Change.INSERTED);
        assertThat(read(dir))
                .isEqualTo("# AGENTS.md\n\n" + AgentsGuide.block() + "\n<!-- BEGIN:nextjs-agent-rules -->\n"
                        + "read the bundled docs\n<!-- END:nextjs-agent-rules -->\n");
        assertThat(AgentsGuide.sync(dir)).isEqualTo(Change.UNCHANGED);
    }

    @Test
    void a_file_without_a_title_gets_the_section_on_top(@TempDir Path dir) throws IOException {
        write(dir, "Use tabs.\n");
        assertThat(AgentsGuide.sync(dir)).isEqualTo(Change.INSERTED);
        assertThat(read(dir)).isEqualTo(AgentsGuide.block() + "\nUse tabs.\n");
    }

    @Test
    void a_stale_section_is_rewritten_and_the_text_around_it_is_byte_stable(@TempDir Path dir) throws IOException {
        String before = "# Hotel\n\nIntro.\n\n";
        String after = "\n## Project rules\n\n- DTOs only over HTTP.\n";
        write(dir, before + AgentsGuide.BEGIN + "\n## JumpKick\n\nold guide\n" + AgentsGuide.END + "\n" + after);

        assertThat(AgentsGuide.sync(dir)).isEqualTo(Change.UPDATED);
        assertThat(read(dir)).isEqualTo(before + AgentsGuide.block() + after);
    }

    @Test
    void refresh_rewrites_only_a_file_that_already_has_the_section(@TempDir Path dir) throws IOException {
        assertThat(AgentsGuide.refresh(dir)).isEqualTo(Change.ABSENT);
        assertThat(dir.resolve(AgentsGuide.FILE)).doesNotExist();

        write(dir, "# Mine\n");
        assertThat(AgentsGuide.refresh(dir)).isEqualTo(Change.ABSENT);
        assertThat(read(dir)).isEqualTo("# Mine\n");

        write(dir, "# Mine\n\n" + AgentsGuide.BEGIN + "\nold\n" + AgentsGuide.END + "\n");
        assertThat(AgentsGuide.refresh(dir)).isEqualTo(Change.UPDATED);
        assertThat(read(dir)).isEqualTo("# Mine\n\n" + AgentsGuide.block());
        assertThat(AgentsGuide.refresh(dir)).isEqualTo(Change.UNCHANGED);
    }

    @Test
    void crlf_files_stay_crlf(@TempDir Path dir) throws IOException {
        write(dir, "# Win\r\n\r\nRules.\r\n");
        AgentsGuide.sync(dir);
        String text = read(dir);
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");
        assertThat(text).isEqualTo(("# Win\n\n" + AgentsGuide.block() + "\nRules.\n").replace("\n", "\r\n"));
    }

    @Test
    void malformed_markers_are_refused_by_sync_and_left_alone_by_refresh(@TempDir Path dir) throws IOException {
        String broken = "# X\n\n" + AgentsGuide.BEGIN + "\nhalf a section\n";
        write(dir, broken);
        assertThatThrownBy(() -> AgentsGuide.sync(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("with no " + AgentsGuide.END);
        assertThat(AgentsGuide.refresh(dir)).isEqualTo(Change.UNCHANGED);
        assertThat(read(dir)).isEqualTo(broken);

        write(dir, AgentsGuide.block() + AgentsGuide.block());
        assertThatThrownBy(() -> AgentsGuide.sync(dir)).hasMessageContaining("more than once");
    }

    @Test
    void the_section_points_at_the_skill_and_stays_short() {
        assertThat(AgentsGuide.SECTION)
                .contains("jk skill")
                .doesNotContain("jk graph")
                .endsWith("\n");
        assertThat(AgentsGuide.SECTION.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1_200);
        assertThat(AgentsGuide.SECTION).doesNotContain(AgentsGuide.BEGIN).doesNotContain(AgentsGuide.END);
    }

    private static void write(Path dir, String text) throws IOException {
        Files.writeString(dir.resolve(AgentsGuide.FILE), text, StandardCharsets.UTF_8);
    }

    private static String read(Path dir) throws IOException {
        return Files.readString(dir.resolve(AgentsGuide.FILE), StandardCharsets.UTF_8);
    }
}
