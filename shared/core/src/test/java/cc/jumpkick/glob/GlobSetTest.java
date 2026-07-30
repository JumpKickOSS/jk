// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.glob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared glob semantics every manifest path key relies on. The behaviours that matter most are
 * the ones that keep a naive {@code **} from being wrong: default exclusions, gitignore awareness,
 * deterministic order, and a hard failure when a pattern matches nothing.
 */
class GlobSetTest {

    private static Path write(Path root, String rel, String body) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    /** A workspace shaped like jk's: modules alongside a plugins tree. */
    private static Path workspace(@TempDir Path tmp) throws Exception {
        write(tmp, "plugins/spring-boot/jk-plugin.toml", "id = 'spring-boot'");
        write(tmp, "plugins/android/jk-plugin.toml", "id = 'android'");
        write(tmp, "plugins/protobuf/jk-plugin.toml", "id = 'protobuf'");
        write(tmp, "plugins/spring-boot/scaffold/app.g8", "template");
        write(tmp, "plugins/spring-boot/scaffold/nested/more.g8", "template");
        Files.createDirectories(tmp.resolve("shared/core"));
        return tmp;
    }

    @Test
    void a_wildcard_segment_matches_across_siblings_in_sorted_order(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches =
                GlobSet.resolve(module, ws, "../../plugins/*/jk-plugin.toml", List.of(), false);

        assertThat(matches).hasSize(3);
        assertThat(matches.stream().map(m -> m.file().getFileName().toString()))
                .containsExactly("jk-plugin.toml", "jk-plugin.toml", "jk-plugin.toml");
        // Sorted by full path — android, protobuf, spring-boot.
        assertThat(matches.stream().map(m -> m.file().getParent().getFileName().toString()))
                .containsExactly("android", "protobuf", "spring-boot");
    }

    @Test
    void the_wildcard_capture_drives_renaming(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        List<String> renamed = GlobSet.resolve(module, ws, "../../plugins/*/jk-plugin.toml", List.of(), false).stream()
                .map(m -> GlobSet.applyCaptures("{1}.jk-plugin.toml", m.captures()))
                .toList();

        assertThat(renamed)
                .containsExactly("android.jk-plugin.toml", "protobuf.jk-plugin.toml", "spring-boot.jk-plugin.toml");
    }

    @Test
    void a_double_star_crosses_directories_and_preserves_tree_shape(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches =
                GlobSet.resolve(module, ws, "../../plugins/spring-boot/scaffold/**", List.of(), false);

        assertThat(matches.stream().map(GlobSet.Match::relative))
                .containsExactly("app.g8", "nested/more.g8");
    }

    @Test
    void build_outputs_and_tooling_dirs_are_skipped_by_default(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        write(ws, "plugins/spring-boot/target/classes/jk-plugin.toml", "stale build output");
        write(ws, "plugins/spring-boot/build/jk-plugin.toml", "stale build output");
        write(ws, "plugins/.git/jk-plugin.toml", "vcs internals");
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches = GlobSet.resolve(module, ws, "../../plugins/**/jk-plugin.toml", List.of(), false);

        assertThat(matches.stream().map(Object::toString))
                .noneMatch(s -> s.contains("/target/") || s.contains("/build/") || s.contains("/.git/"));
        assertThat(matches).hasSize(3);
    }

    @Test
    void gitignored_files_are_not_build_inputs(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        // The case that motivated this: an ignored directory holding whole stale repo copies.
        write(ws, ".gitignore", "worktrees/\n");
        write(ws, "plugins/worktrees/old/jk-plugin.toml", "stale copy");
        write(ws, "worktrees/copy/jk-plugin.toml", "stale copy");
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches = GlobSet.resolve(module, ws, "../../plugins/**/jk-plugin.toml", List.of(), false);

        assertThat(matches.stream().map(Object::toString)).noneMatch(s -> s.contains("worktrees"));
        assertThat(matches).hasSize(3);
    }

    @Test
    void a_negated_gitignore_rule_puts_a_file_back(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        write(ws, ".gitignore", "*.gen.toml\n!keep.gen.toml\n");
        write(ws, "plugins/a/drop.gen.toml", "x");
        write(ws, "plugins/a/keep.gen.toml", "x");
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches = GlobSet.resolve(module, ws, "../../plugins/**/*.gen.toml", List.of(), false);

        assertThat(matches.stream().map(m -> m.file().getFileName().toString()))
                .containsExactly("keep.gen.toml");
    }

    @Test
    void an_explicit_exclude_removes_matches(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches = GlobSet.resolve(
                module, ws, "../../plugins/*/jk-plugin.toml", List.of("../../plugins/android/**"), false);

        assertThat(matches.stream().map(m -> m.file().getParent().getFileName().toString()))
                .containsExactly("protobuf", "spring-boot");
    }

    @Test
    void matching_nothing_is_an_error_unless_optional(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        assertThatThrownBy(() -> GlobSet.resolve(module, ws, "../../plugins/*/nope.toml", List.of(), false))
                .isInstanceOf(GlobSet.GlobException.class)
                .hasMessageContaining("matched no files");

        assertThat(GlobSet.resolve(module, ws, "../../plugins/*/nope.toml", List.of(), true))
                .isEmpty();
    }

    @Test
    void a_pattern_cannot_escape_the_project_root(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        assertThatThrownBy(() -> GlobSet.resolve(module, ws, "../../../../etc/passwd", List.of(), false))
                .isInstanceOf(GlobSet.GlobException.class)
                .hasMessageContaining("outside the project root");
    }

    @Test
    void a_literal_file_path_still_works(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches =
                GlobSet.resolve(module, ws, "../../plugins/android/jk-plugin.toml", List.of(), false);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).relative()).isEqualTo("jk-plugin.toml");
    }

    @Test
    void a_literal_directory_expands_to_its_files(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        List<GlobSet.Match> matches =
                GlobSet.resolve(module, ws, "../../plugins/spring-boot/scaffold", List.of(), false);

        assertThat(matches.stream().map(GlobSet.Match::relative)).containsExactly("app.g8", "nested/more.g8");
    }

    @Test
    void a_single_star_does_not_cross_a_directory_boundary(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path module = ws.resolve("shared/core");

        // scaffold/nested/more.g8 is two levels down, so a single * must not reach it.
        List<GlobSet.Match> matches =
                GlobSet.resolve(module, ws, "../../plugins/spring-boot/scaffold/*", List.of(), false);

        assertThat(matches.stream().map(GlobSet.Match::relative)).containsExactly("app.g8");
    }
}
