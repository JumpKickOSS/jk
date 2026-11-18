// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite's read list as it may leave the machine: workspace files by workspace path, never a
 * checkout path, and a list from elsewhere held to the same spellings.
 */
class SuiteReadListsTest {

    @Test
    void a_list_names_workspace_files_by_workspace_path_and_reads_back_on_another_checkout(@TempDir Path tmp)
            throws Exception {
        Path a = workspace(tmp.resolve("a"));
        Path b = workspace(tmp.resolve("elsewhere/b"));
        SuiteReads.Discovered read =
                new SuiteReads.Discovered(Set.of(a.resolve("data/fixture.txt")), Set.of(), Set.of());

        List<String> names = requireNonNull(SuiteReadLists.portable(a.resolve("m"), read));

        assertThat(names).containsExactly("ws:data/fixture.txt");
        String real = tmp.toRealPath().toString();
        assertThat(names).noneMatch(n -> n.contains(tmp.toString()) || n.contains(real));
        assertThat(requireNonNull(SuiteReadLists.fromPortable(b.resolve("m"), names))
                        .files())
                .containsExactly(b.resolve("data/fixture.txt"));
    }

    @Test
    void reads_no_name_can_carry_have_no_list(@TempDir Path tmp) throws Exception {
        Path a = workspace(tmp.resolve("a"));
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "x").toRealPath();
        assertThat(SuiteReadLists.portable(
                        a.resolve("m"), new SuiteReads.Discovered(Set.of(outside), Set.of(), Set.of())))
                .as("a file outside the workspace and jk's roots")
                .isNull();
        assertThat(SuiteReadLists.portable(
                        a.resolve("m"),
                        new SuiteReads.Discovered(Set.of(a.resolve("data/fixture.txt")), Set.of("git"), Set.of())))
                .as("a program the suite started")
                .isNull();
        assertThat(SuiteReadLists.portable(a.resolve("m"), SuiteReads.Discovered.NONE))
                .isNull();
    }

    @Test
    void a_list_from_elsewhere_names_nothing_outside_the_workspace_and_jks_roots(@TempDir Path tmp) throws Exception {
        Path a = workspace(tmp.resolve("a"));
        for (String bad : List.of(
                "ws:../secret",
                "ws:data/../../secret",
                "/etc/passwd",
                "ws:/etc/passwd",
                "C:/Windows/win.ini",
                "ws:data\\\\fixture.txt",
                "home:.ssh/id_rsa",
                "ws:",
                "ws:data//fixture.txt")) {
            assertThat(SuiteReadLists.fromPortable(a.resolve("m"), List.of("ws:data/fixture.txt", bad)))
                    .as(bad)
                    .isNull();
        }
        assertThat(SuiteReadLists.fromPortable(a.resolve("m"), List.of("store:repos/central/x.jar")))
                .isNotNull();
    }

    @Test
    void a_stand_in_list_is_what_the_module_reads_while_a_key_is_taken(@TempDir Path tmp) throws Exception {
        Path a = workspace(tmp.resolve("a"));
        SuiteReads.Discovered read =
                new SuiteReads.Discovered(Set.of(a.resolve("data/fixture.txt")), Set.of(), Set.of());
        SuiteReads.Discovered seen =
                SuiteReadLists.as(a.resolve("m"), read, () -> SuiteReads.load(a.resolve("m"), TestSelection.DEFAULT));
        assertThat(seen).isEqualTo(read);
        SuiteReads.Discovered other =
                SuiteReadLists.as(a.resolve("m"), read, () -> SuiteReads.load(a.resolve("n"), TestSelection.DEFAULT));
        assertThat(other).as("another module's").isNotEqualTo(read);
    }

    private static Path workspace(Path ws) throws Exception {
        Files.createDirectories(ws.resolve("m"));
        Files.createDirectories(ws.resolve("n"));
        Files.createDirectories(ws.resolve("data"));
        Files.writeString(ws.resolve("data/fixture.txt"), "one");
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["m", "n"]
                """);
        Files.writeString(ws.resolve("m/jk.toml"), "group = \"com.example\"\nname = \"m\"\nversion = \"1.0.0\"\n");
        Files.writeString(ws.resolve("n/jk.toml"), "group = \"com.example\"\nname = \"n\"\nversion = \"1.0.0\"\n");
        return ws.toRealPath();
    }
}
