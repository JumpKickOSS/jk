// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestHistory;
import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.config.TomlScan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code flaky} MCP tool: the same list, show and release as {@code jk flaky}. */
class FlakyToolTest {

    @TempDir
    Path tmp;

    private Path root;
    private Path state;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("ws"));
        state = tmp.resolve("state");
        TomlScan.clearCache();
        Path module = root.resolve("app");
        Path file = TestHistory.file(state, module);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# " + module.toAbsolutePath().normalize() + "\na.FlakyTest\tF.R.R\na.OkTest\t...\n");
        TestQuarantine.NONE
                .with(new TestQuarantine.Entry("a.FlakyTest", "app", "flaky", "2026-11-20", null))
                .write(root);
    }

    @Test
    void list_answers_the_window_and_each_class_with_its_history() throws IOException {
        Map<String, Object> data = FlakyTool.list(state, root);
        assertThat(data).containsEntry("flakes", 2).containsEntry("runs", 20);
        assertThat(data.get("classes"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .singleElement()
                .isEqualTo(Map.of(
                        "class", "a.FlakyTest",
                        "module", "app",
                        "quarantined", true,
                        "flakes", 2,
                        "runs", 5,
                        "history", "F.R.R",
                        "since", "2026-11-20",
                        "reason", "flaky"));
    }

    @Test
    void show_needs_a_class_it_knows() throws IOException {
        assertThat(FlakyTool.show(state, root, null)).containsEntry("error", "show requires class");
        assertThat(FlakyTool.show(state, root, "a.Nope"))
                .containsEntry("error", "no history or quarantine entry: a.Nope");
        assertThat(FlakyTool.show(state, root, "FlakyTest")).containsKey("classes");
    }

    @Test
    void release_deletes_the_entry_once_and_resets_the_count() throws IOException {
        assertThat(FlakyTool.release(state, root, "FlakyTest"))
                .isEqualTo(Map.of("released", List.of(Map.of("class", "a.FlakyTest", "module", "app"))));
        assertThat(TestQuarantine.read(root).entries()).isEmpty();
        assertThat(FlakyTool.list(state, root).get("classes"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .as("its re-run passes were before the release")
                .isEmpty();
        assertThat(FlakyTool.show(state, root, "FlakyTest").get("classes"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("history", "F.R.R|")
                .containsEntry("flakes", 0)
                .containsEntry("runs", 0);
        assertThat(FlakyTool.release(state, root, "FlakyTest")).containsEntry("error", "not quarantined: FlakyTest");
    }
}
