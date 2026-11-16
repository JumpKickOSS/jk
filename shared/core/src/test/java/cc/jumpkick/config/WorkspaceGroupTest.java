// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A root {@code jk.toml} that groups self-contained workspaces: its members, exclusions with their
 * reasons, the profiles that add excluded members back, the order {@code path} dependencies give,
 * and what it refuses (JK-WC9HW).
 */
class WorkspaceGroupTest {

    @TempDir
    Path root;

    private void write(String rel, String text) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void project(String dir, String name) throws IOException {
        write(dir + "/jk.toml", "group = \"com.example\"\nname = \"" + name + "\"\nversion = \"1.0.0\"\n");
    }

    private List<String> paths(List<WorkspaceGroup.Member> members) {
        return members.stream().map(WorkspaceGroup.Member::path).toList();
    }

    @Test
    void members_expand_globs_and_carry_their_exclusion_reasons() throws IOException {
        project("cache", "acme-cache");
        project("examples/android", "android-app");
        project("examples/cli", "cli");
        write("jk.toml", """
                [workspaces]
                members = ["cache", "examples/*"]
                exclude = [{ path = "examples/android", reason = "downloads the Android SDK" }]

                [profiles.all.workspaces]
                include = ["examples/*"]
                """);

        WorkspaceGroup group = WorkspaceGroup.load(root);

        assertThat(paths(group.members())).containsExactly("cache", "examples/android", "examples/cli");
        assertThat(group.members().get(1).excludedBecause()).isEqualTo("downloads the Android SDK");
        assertThat(group.members().get(0).name()).isEqualTo("acme-cache");
        assertThat(group.profiles()).containsKey("all");

        WorkspaceGroup.Selection plain = group.select(null, List.of());
        assertThat(paths(plain.run())).containsExactly("cache", "examples/cli");
        assertThat(paths(plain.skipped())).containsExactly("examples/android");
        assertThat(group.includedBy(plain.skipped().getFirst())).isEqualTo("all");

        WorkspaceGroup.Selection all = group.select("all", List.of());
        assertThat(paths(all.run())).containsExactly("cache", "examples/android", "examples/cli");
        assertThat(all.skipped()).isEmpty();
    }

    @Test
    void a_selection_by_name_or_path_runs_what_it_depends_on_first() throws IOException {
        project("libs", "acme-libs");
        write("libs/jk.toml", """
                group = "com.example"
                name = "acme-libs"
                version = "1.0.0"

                [workspace]
                modules = ["core"]
                """);
        project("libs/core", "core");
        write("apps/web/jk.toml", """
                group = "com.example"
                name = "acme-web"
                version = "1.0.0"

                [dependencies]
                core = { path = "../../libs/core" }
                """);
        project("tools", "acme-tools");
        write("jk.toml", """
                [workspaces]
                members = ["apps/web", "libs", "tools"]
                """);
        WorkspaceGroup group = WorkspaceGroup.load(root);

        assertThat(paths(group.select(null, List.of()).run())).containsExactly("libs", "apps/web", "tools");
        assertThat(paths(group.select(null, List.of("acme-web")).run())).containsExactly("libs", "apps/web");
        assertThat(paths(group.select(null, List.of("tools")).run())).containsExactly("tools");
        assertThatThrownBy(() -> group.select(null, List.of("nope")))
                .hasMessageContaining("no member named `nope`")
                .hasMessageContaining("apps/web, libs, tools");
    }

    @Test
    void a_cycle_between_members_is_refused_naming_them() throws IOException {
        write("a/jk.toml", "name = \"a\"\n[dependencies]\nb = { path = \"../b\" }\n");
        write("b/jk.toml", "name = \"b\"\n[dependencies]\na = { path = \"../a\" }\n");
        write("jk.toml", "[workspaces]\nmembers = [\"a\", \"b\"]\n");

        assertThatThrownBy(() -> WorkspaceGroup.load(root).select(null, List.of()))
                .hasMessageContaining("a, b form a cycle");
    }

    @Test
    void every_exclusion_needs_a_reason_and_must_name_a_member() throws IOException {
        project("a", "a");
        write("jk.toml", "[workspaces]\nmembers = [\"a\"]\nexclude = [{ path = \"a\" }]\n");
        assertThatThrownBy(() -> WorkspaceGroup.load(root)).hasMessageContaining("`a` needs a reason");

        write("jk.toml", "[workspaces]\nmembers = [\"a\"]\nexclude = [{ path = \"b\", reason = \"x\" }]\n");
        assertThatThrownBy(() -> WorkspaceGroup.load(root)).hasMessageContaining("`b` names no member");

        write("jk.toml", """
                [workspaces]
                members = ["a"]

                [profiles.all.workspaces]
                include = ["a"]
                """);
        assertThatThrownBy(() -> WorkspaceGroup.load(root)).hasMessageContaining("names no excluded member");
    }

    @Test
    void the_root_holds_nothing_a_member_could_inherit() throws IOException {
        project("a", "a");
        write("jk.toml", "java = 25\n[workspaces]\nmembers = [\"a\"]\n");
        assertThatThrownBy(() -> WorkspaceGroup.load(root))
                .hasMessageContaining("`java` belongs in a member's jk.toml");

        write("jk.toml", "[workspaces]\nmembers = [\"a\"]\n[profiles.strict]\njavac = [\"-Werror\"]\n");
        assertThatThrownBy(() -> WorkspaceGroup.load(root))
                .hasMessageContaining("[profiles.strict] `javac`: a group root's profile only adds members back");
    }

    @Test
    void a_group_root_inside_a_group_root_is_refused() throws IOException {
        project("inner/a", "a");
        write("inner/jk.toml", "[workspaces]\nmembers = [\"a\"]\n");
        write("jk.toml", "[workspaces]\nmembers = [\"inner\"]\n");

        assertThatThrownBy(() -> WorkspaceGroup.load(root))
                .hasMessageContaining("`inner` is a group root itself; list its members here instead");
    }

    @Test
    void a_listed_member_with_no_project_is_reported_missing() throws IOException {
        project("a", "a");
        write("jk.toml", "[workspaces]\nmembers = [\"a\", \"gone\"]\n");

        WorkspaceGroup group = WorkspaceGroup.load(root);

        assertThat(paths(group.members())).containsExactly("a");
        assertThat(group.missing()).containsExactly("gone");
    }

    @Test
    void a_group_root_read_as_a_project_says_where_the_projects_are() throws IOException {
        project("cache", "acme-cache");
        write("jk.toml", "[workspaces]\nmembers = [\"cache\"]\n");

        assertThatThrownBy(() -> JkBuildParser.parse(root.resolve("jk.toml")))
                .hasMessageContaining("groups workspaces (cache) and is not a project")
                .hasMessageContaining("jk -C cache");
    }

    @Test
    void only_a_listing_root_owns_a_member() throws IOException {
        project("cache", "acme-cache");
        project("stray", "stray");
        write("jk.toml", "[workspaces]\nmembers = [\"cache\"]\n");

        assertThat(WorkspaceGroup.listing(root.resolve("cache")))
                .isEqualTo(root.toAbsolutePath().normalize());
        assertThat(WorkspaceGroup.listing(root.resolve("stray"))).isNull();
        assertThat(WorkspaceGroup.declares(root)).isTrue();
        assertThat(WorkspaceGroup.declares(root.resolve("cache"))).isFalse();
    }

    @Test
    void a_listed_workspace_tags_its_artifact_name_and_a_standalone_one_does_not() throws IOException {
        write("cache/jk.toml", "group = \"com.example\"\nname = \"acme-cache\"\nversion = \"1.2.0\"\n");
        Path manifest = root.resolve("cache/jk.toml");
        assertThat(JkBuildParser.releaseTable(manifest).tag()).isEqualTo("v{version}");

        write("jk.toml", "[workspaces]\nmembers = [\"cache\"]\n");
        assertThat(JkBuildParser.releaseTable(manifest).tag()).isEqualTo("acme-cache-v{version}");

        write("cache/jk.toml", """
                group = "com.example"
                name = "acme-cache"
                version = "1.2.0"

                [release]
                tag = "cache/{version}"
                """);
        assertThat(JkBuildParser.releaseTable(manifest).tag()).isEqualTo("cache/{version}");
    }

    @Test
    void a_new_member_is_listed_in_the_file_s_own_layout() throws IOException {
        assertThat(WorkspaceGroup.withMember("[workspaces]\nmembers = [\"a\"]\n", "b"))
                .isEqualTo("[workspaces]\nmembers = [\"a\", \"b\"]\n");
        assertThat(WorkspaceGroup.withMember("""
                [workspaces]
                members = [
                    "a",  # the first
                    "libs/*"
                ]
                """, "c")).isEqualTo("""
                        [workspaces]
                        members = [
                            "a",  # the first
                            "libs/*",
                            "c",
                        ]
                        """);
        assertThat(WorkspaceGroup.withMember("[workspaces]\nmembers = [\n  \"a\" # x\n]\n", "c"))
                .isEqualTo("[workspaces]\nmembers = [\n  \"a\", # x\n  \"c\",\n]\n");
        String covered = "[workspaces]\nmembers = [\"libs/*\"]\n";
        assertThat(WorkspaceGroup.withMember(covered, "libs/new")).isEqualTo(covered);

        project("a", "a");
        project("b", "b");
        write("jk.toml", "[workspaces]\nmembers = [\"a\"]\n");
        assertThat(WorkspaceGroup.addMember(root, "b")).isTrue();
        assertThat(WorkspaceGroup.load(root).members())
                .extracting(WorkspaceGroup.Member::path)
                .containsExactly("a", "b");
    }
}
