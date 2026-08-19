// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.model.command.Invocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TestCommand#resolveTestSelection}: the resolved selection is final ({@code tagsResolved})
 * exactly when a layer spoke — baseline tags, a present profile key (including {@code = []} to
 * clear), or a CLI flag. Silent runs stay unresolved so the engine can still fold
 * per-module {@code [test]} tags for workspace members.
 */
class TestSelectionResolveTest {

    /** TestCommand's options plus the global mixin so {@code -C <dir>} parses like a real run. */
    private static final cc.jumpkick.model.command.Command CMD = new cc.jumpkick.model.command.Command() {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public List<cc.jumpkick.model.command.Opt> options() {
            var opts = new ArrayList<>(new TestCommand().options());
            opts.addAll(cc.jumpkick.cli.GlobalOptions.globalOpts());
            return opts;
        }
    };

    private static Invocation parse(String... args) throws Exception {
        return ArgParser.parse(CMD, List.of(args));
    }

    private static void writeToml(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                """ + body);
    }

    @Test
    void profile_empty_list_clears_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]

                [profiles.ci]
                exclude-tags = []
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "-p", "ci"));
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void baseline_tags_are_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString()));
        assertThat(sel.excludeTags()).containsExactly("slow");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void cli_blank_value_clears_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--exclude-tags", ""));
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void all_clears_baseline_tags_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--all"));
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.includeTags()).isEmpty();
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void all_composes_with_explicit_cli_tags(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--all", "--exclude-tags", "bench"));
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.excludeTags()).containsExactly("bench");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void silent_run_stays_unresolved(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString()));
        assertThat(sel.includeTags()).isEmpty();
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isFalse();
    }
}
