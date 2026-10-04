// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The installed skill folder is the same bytes the CLI and MCP serve. */
class JkSkillTest {

    @Test
    void core_and_topics_stay_inside_the_budget() {
        String core = JkSkill.core();
        assertThat(core.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);
        assertThat(core).endsWith("\n").doesNotContain("\r");
        assertThat(frontmatter(core)).contains("name: jk");
        String description = frontmatter(core)
                .lines()
                .filter(line -> line.startsWith("description:"))
                .findFirst()
                .orElseThrow();
        assertThat(description).doesNotContain("\n");
        assertThat(description.substring("description:".length()).strip())
                .doesNotContain("\n")
                .isNotBlank();
        for (String topic : JkSkill.TOPICS) {
            String body = Objects.requireNonNull(JkSkill.topic(topic));
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).as(topic).isLessThanOrEqualTo(1_200);
        }
        assertThat(JkSkill.topic("nope")).isNull();
        assertThat(JkSkill.topic("../SKILL")).isNull();
    }

    @Test
    void install_writes_a_folder_whose_files_match_the_served_text(@TempDir Path dir) throws Exception {
        Path written = JkSkill.install(dir.resolve(".agents/skills"));
        assertThat(written.getFileName()).hasToString("jk");
        assertThat(Files.readString(written.resolve("SKILL.md"))).isEqualTo(JkSkill.core());
        for (String topic : JkSkill.TOPICS) {
            assertThat(Files.readString(written.resolve(topic + ".md"))).isEqualTo(JkSkill.topic(topic));
        }
    }

    @Test
    void the_guide_goes_above_a_generators_agents_file_and_keeps_its_managed_block(@TempDir Path dir) throws Exception {
        String next = "# AGENTS.md\n\n<!-- BEGIN:nextjs-agent-rules -->\nread the bundled docs\n"
                + "<!-- END:nextjs-agent-rules -->\n";
        Files.writeString(dir.resolve("AGENTS.md"), next, StandardCharsets.UTF_8);

        assertThat(JkSkill.mergeAgentsGuide(dir)).isTrue();
        String merged = Files.readString(dir.resolve("AGENTS.md"), StandardCharsets.UTF_8);
        assertThat(merged).startsWith(JkSkill.AGENTS_MD);
        assertThat(merged).contains("<!-- BEGIN:nextjs-agent-rules -->\nread the bundled docs\n");
        assertThat(merged.indexOf("# AGENTS.md")).isEqualTo(merged.lastIndexOf("# AGENTS.md"));
        assertThat(JkSkill.mergeAgentsGuide(dir)).as("already merged").isFalse();
    }

    @Test
    void agents_guide_writes_once(@TempDir Path dir) throws Exception {
        assertThat(JkSkill.ensureAgentsGuide(dir)).isTrue();
        assertThat(Files.readString(dir.resolve("AGENTS.md")))
                .isEqualTo(JkSkill.AGENTS_MD)
                .contains("jk skill");
        Files.writeString(dir.resolve("AGENTS.md"), "# custom\n");
        assertThat(JkSkill.ensureAgentsGuide(dir)).isFalse();
        assertThat(Files.readString(dir.resolve("AGENTS.md"))).isEqualTo("# custom\n");
    }

    private static String frontmatter(String markdown) {
        assertThat(markdown).startsWith("---\n");
        int end = markdown.indexOf("\n---\n", 4);
        assertThat(end).isPositive();
        return markdown.substring(4, end);
    }
}
