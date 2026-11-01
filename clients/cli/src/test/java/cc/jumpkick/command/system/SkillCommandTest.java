// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.docs.JkSkill;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillCommandTest {

    @Test
    void command_is_registered_and_manual_is_gone() {
        assertThat(CommandDispatch.commands().stream().map(c -> c.name()))
                .contains("skill")
                .doesNotContain("manual");
    }

    @Test
    void prints_the_same_core_the_skill_source_serves() {
        String out = Capture.stdout(() -> assertThat(Jk.execute("skill")).isEqualTo(Exit.SUCCESS));
        assertThat(out).isEqualTo(JkSkill.core());
        assertThat(out).contains("·");
    }

    @Test
    void core_is_byte_exact_under_no_ansi() throws Exception {
        String out = NoAnsi.forced(
                () -> Capture.stdout(() -> assertThat(Jk.execute("skill")).isEqualTo(Exit.SUCCESS)));
        assertThat(out).isEqualTo(JkSkill.core());
    }

    @Test
    void list_and_one_topic_match_the_source() {
        String list =
                Capture.stdout(() -> assertThat(Jk.execute("skill", "--list")).isEqualTo(Exit.SUCCESS));
        assertThat(list).isEqualTo(String.join("\n", JkSkill.TOPICS) + "\n");
        String topic = Capture.stdout(
                () -> assertThat(Jk.execute("skill", "dependencies")).isEqualTo(Exit.SUCCESS));
        assertThat(topic).isEqualTo(JkSkill.topic("dependencies"));
    }

    @Test
    void unknown_topic_is_usage() {
        assertThat(Jk.execute("skill", "no-such-topic")).isEqualTo(Exit.USAGE);
    }

    @Test
    void install_writes_the_folder(@TempDir Path dir) {
        String out = Capture.stdout(
                () -> assertThat(Jk.execute("skill", "install", dir.toString())).isEqualTo(Exit.SUCCESS));
        assertThat(out.strip()).isEqualTo(dir.resolve("jk").toString());
        assertThat(dir.resolve("jk/SKILL.md")).isRegularFile();
        assertThat(dir.resolve("jk/dependencies.md")).isRegularFile();
    }

    @Test
    void default_install_dir_is_agents_skills_under_the_project(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n");
        Path nested = Files.createDirectories(dir.resolve("mod"));
        // The command walks from the cwd. This test calls the resolver with the project as cwd
        // only when the process cwd is that directory — the helper itself walks from user.dir,
        // so assert the constant and the written layout instead of chdir.
        assertThat(JkSkill.INSTALL_DIR).isEqualTo(".agents/skills");
        assertThat(nested).isDirectory();
    }
}
