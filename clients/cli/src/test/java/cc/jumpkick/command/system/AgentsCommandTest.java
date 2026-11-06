// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.docs.AgentsGuide;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentsCommandTest {

    @Test
    void agents_creates_then_reports_current_and_keeps_project_text(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(AgentsGuide.FILE);
        String created = Capture.stdout(
                () -> assertThat(Jk.execute("agents", dir.toString())).isEqualTo(Exit.SUCCESS));
        assertThat(created.lines().findFirst()).contains("created " + file);
        assertThat(Files.readString(file)).isEqualTo(AgentsGuide.newFile());

        Files.writeString(file, Files.readString(file) + "\n## Project rules\n");
        String current = Capture.stdout(
                () -> assertThat(Jk.execute("agents", dir.toString())).isEqualTo(Exit.SUCCESS));
        assertThat(current.lines().findFirst()).contains(file + " is current");
        assertThat(Files.readString(file)).endsWith("\n## Project rules\n");
    }

    @Test
    void agents_refuses_unpaired_markers(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(AgentsGuide.FILE), AgentsGuide.BEGIN + "\n");
        assertThat(Jk.execute("agents", dir.toString())).isEqualTo(Exit.CONFIG);
    }

    @Test
    void remove_takes_back_what_all_wrote_and_keeps_project_text(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(AgentsGuide.FILE), "# Mine\n\nrules\n");
        Capture.stdout(
                () -> assertThat(Jk.execute("agents", "--all", dir.toString())).isEqualTo(Exit.SUCCESS));
        assertThat(dir.resolve(".mcp.json")).isRegularFile();
        Capture.stdout(() ->
                assertThat(Jk.execute("agents", "--remove", dir.toString())).isEqualTo(Exit.SUCCESS));
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
        assertThat(dir.resolve("CLAUDE.md")).doesNotExist();
        assertThat(dir.resolve(".claude/settings.json")).doesNotExist();
        assertThat(Files.readString(dir.resolve(AgentsGuide.FILE))).isEqualTo("# Mine\n\nrules\n");
    }

    @Test
    void agents_beside_a_broken_config_toml_names_its_position_and_writes_nothing(@TempDir Path home, @TempDir Path dir)
            throws Exception {
        Path config = Files.writeString(home.resolve("config.toml"), "[network\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "g"
                name = "p"
                version = "1.0"
                java = 25
                """);
        System.setProperty("jk.env.JK_HOME", home.toString());
        int[] exit = new int[1];
        String err = Capture.stderr(() -> {
            exit[0] = Jk.execute("agents", dir.toString());
        });

        assertThat(exit[0]).isEqualTo(Exit.CONFIG);
        assertThat(err).contains(config + ":1:");
        assertThat(dir.resolve(AgentsGuide.FILE))
                .as("the guide's format line depends on the manifest, which cannot be read")
                .doesNotExist();
    }
}
