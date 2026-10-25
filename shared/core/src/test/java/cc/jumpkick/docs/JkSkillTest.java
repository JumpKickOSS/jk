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
        assertThat(core.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2560);
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

    private static String frontmatter(String markdown) {
        assertThat(markdown).startsWith("---\n");
        int end = markdown.indexOf("\n---\n", 4);
        assertThat(end).isPositive();
        return markdown.substring(4, end);
    }
}
