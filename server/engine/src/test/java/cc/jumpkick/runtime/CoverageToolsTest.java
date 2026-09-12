// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The report command hands JaCoCo only class directories that exist and asks for the XML the guard reads. */
class CoverageToolsTest {

    @Test
    void the_report_command_names_existing_class_dirs_and_the_xml(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("classes/main"));
        Path kotlin = tmp.resolve("classes/kotlin"); // never compiled: absent
        Path xml = tmp.resolve("reports/jacoco.xml");

        List<String> cmd = CoverageTools.reportCommand(
                tmp.resolve("jdk"),
                tmp.resolve("cli.jar"),
                tmp.resolve("jacoco.exec"),
                List.of(main, kotlin),
                xml,
                "g:a");

        assertThat(cmd)
                .containsSubsequence(
                        "-jar",
                        tmp.resolve("cli.jar").toString(),
                        "report",
                        tmp.resolve("jacoco.exec").toString());
        assertThat(cmd).containsSequence("--classfiles", main.toString());
        assertThat(cmd).doesNotContain(kotlin.toString());
        assertThat(cmd).containsSequence("--xml", xml.toString()).containsSequence("--name", "g:a");
        assertThat(cmd.get(0)).startsWith(tmp.resolve("jdk").resolve("bin").toString());
    }

    @Test
    void without_execution_data_the_report_is_refused_before_any_tool_runs(@TempDir Path tmp) {
        var tools = new CoverageTools.Jacoco("0.8.13", tmp.resolve("agent.jar"), tmp.resolve("cli.jar"));
        assertThatThrownBy(() -> CoverageTools.writeReport(
                        tmp.resolve("jdk"),
                        tools,
                        tmp.resolve("missing.exec"),
                        List.of(),
                        tmp.resolve("jacoco.xml"),
                        "g:a"))
                .hasMessageContaining("no execution data");
    }
}
