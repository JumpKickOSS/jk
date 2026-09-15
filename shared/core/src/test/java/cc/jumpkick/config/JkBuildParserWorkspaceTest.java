// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static cc.jumpkick.config.JkBuildParserFixtures.TEST_CATALOG;
import static cc.jumpkick.config.JkBuildParserFixtures.workspaceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.ProjectInherit;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildParserWorkspaceTest {

    @Test
    void missing_version_on_standalone_marks_workspace_inherit() {
        // Non-root omit of version is inheritance (member-shaped); not a hard parse error.
        JkBuild parsed = JkBuildParser.parse("""
                group    = "com.example"
                name     = "widget"
                """);
        assertThat(parsed.project().inheritsVersionFromWorkspace()).isTrue();
        assertThat(parsed.project().requiresWorkspaceRoot()).isTrue();
    }

    @Test
    void workspace_root_still_requires_concrete_version() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group    = "com.example"
                name     = "root"

                [workspace]
                modules = ["lib"]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version");
    }

    @Test
    void module_shaped_only_name_is_valid_parse() {
        JkBuild parsed = JkBuildParser.parse("""
                name = "foo"
                """);
        assertThat(parsed.project().name()).isEqualTo("foo");
        assertThat(parsed.project().inherits(ProjectInherit.GROUP)).isTrue();
        assertThat(parsed.project().inherits(ProjectInherit.VERSION)).isTrue();
        assertThat(parsed.project().inherits(ProjectInherit.JAVA)).isTrue();
        assertThat(parsed.project().inherits(ProjectInherit.DESCRIPTION)).isFalse();
        assertThat(parsed.project().description()).isNull();
    }

    @Test
    void version_workspace_true_parses_as_inheritance_sentinel() {
        // Dotted key form (Cargo-style).
        JkBuild dotted = JkBuildParser.parse("""
                group   = "com.example"
                name    = "mod"
                version.workspace = true
                jdk     = 25
                java    = 25
                """);
        assertThat(dotted.project().inheritsVersionFromWorkspace()).isTrue();
        assertThat(dotted.project().version()).isEqualTo(Project.VERSION_FROM_WORKSPACE);

        // Inline table form.
        JkBuild inline = JkBuildParser.parse("""
                group   = "com.example"
                name    = "mod"
                version = { workspace = true }
                jdk     = 25
                java    = 25
                """);
        assertThat(inline.project().inheritsVersionFromWorkspace()).isTrue();
    }

    @Test
    void project_field_workspace_inheritance_parses_multiple_fields() {
        JkBuild parsed = JkBuildParser.parse("""
                group.workspace = true
                name    = "mod"
                version.workspace = true
                java.workspace = true
                jdk.workspace = true
                description.workspace = true
                """);
        var p = parsed.project();
        assertThat(p.inheritsFromWorkspace()).isTrue();
        assertThat(p.inherits(ProjectInherit.GROUP)).isTrue();
        assertThat(p.inherits(ProjectInherit.VERSION)).isTrue();
        assertThat(p.inherits(ProjectInherit.JAVA)).isTrue();
        assertThat(p.inherits(ProjectInherit.JDK)).isTrue();
        assertThat(p.inherits(ProjectInherit.DESCRIPTION)).isTrue();
        assertThat(p.name()).isEqualTo("mod");
    }

    @Test
    void name_workspace_inheritance_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group   = "com.example"
                name.workspace = true
                version = "1.0.0"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("workspace");
    }

    @Test
    void version_workspace_true_rejected_on_workspace_root() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group   = "com.example"
                name    = "root"
                version.workspace = true

                [workspace]
                modules = ["lib"]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace root");
    }

    @Test
    void version_workspace_must_be_true() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group   = "com.example"
                name    = "mod"
                version = { workspace = false }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("workspace");
    }

    @Test
    void parses_workspace_dependencies_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a", "b"]

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.0" }
                assertj-core  = { group = "org.assertj",       name = "assertj-core",  version = "3.27.7" }
                """);
        assertThat(workspaceOf(parsed).dependencies()).hasSize(2).containsKey("junit-jupiter");
        var jj = Objects.requireNonNull(workspaceOf(parsed).dependencies().get("junit-jupiter"));
        assertThat(jj.group()).isEqualTo("org.junit.jupiter");
        assertThat(jj.artifact()).isEqualTo("junit-jupiter");
        assertThat(jj.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(jj.version()).isInstanceOf(VersionSelector.Exact.class);
    }

    @Test
    void workspace_dependencies_gav_string_is_a_maven_coordinate() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                jupiter = "org.junit.jupiter:junit-jupiter:6.1.0"
                """);
        var jj = Objects.requireNonNull(workspaceOf(parsed).dependencies().get("jupiter"));
        assertThat(jj.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(jj.version()).isEqualTo(new VersionSelector.Exact("6.1.0", "6.1.0"));
    }

    @Test
    void workspace_dependencies_gav_string_needs_a_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                jupiter = "org.junit.jupiter:junit-jupiter"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("no version");
    }

    @Test
    void workspace_dependencies_artifact_defaults_to_key() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """);
        var pico = Objects.requireNonNull(workspaceOf(parsed).dependencies().get("picocli"));
        assertThat(pico.artifact()).isEqualTo("picocli");
    }

    @Test
    void workspace_dependencies_string_shorthand_resolves_through_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                jackson-databind = "2.18.2"
                """, TEST_CATALOG);
        var jd = Objects.requireNonNull(workspaceOf(parsed).dependencies().get("jackson-databind"));
        assertThat(jd.module()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(jd.version()).isInstanceOf(VersionSelector.Exact.class);
    }

    @Test
    void workspace_dependencies_table_without_group_resolves_through_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                picocli = { version = "4.7.7" }
                """, TEST_CATALOG);
        var pico = Objects.requireNonNull(workspaceOf(parsed).dependencies().get("picocli"));
        assertThat(pico.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void workspace_dependencies_unknown_shorthand_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                some-unknown-thing = "1.0.0"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void workspace_dependencies_string_shorthand_rejects_git_url() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                shared = "https://github.com/acme/shared"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version spec");
    }

    @Test
    void workspace_dependencies_rejects_path_source() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                shared-lib = { group = "com.acme", path = "../shared" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[workspace] modules");
    }

    @Test
    void workspace_true_resolves_against_workspace_dependencies() {
        // A workspace = true dep with a matching [workspace.dependencies]
        // entry materializes that entry's coord directly during parse.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = []

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.0" }

                [test-dependencies]
                junit-jupiter.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.TEST).getFirst();
        assertThat(dep.library()).isEqualTo("junit-jupiter");
        assertThat(dep.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(dep.version()).isInstanceOf(VersionSelector.Exact.class);
    }

    @Test
    void workspace_true_without_match_emits_placeholder_for_merge() {
        // Without a [workspace.dependencies] match the parser emits a
        // placeholder that WorkspaceMerge resolves against the sibling
        // list. The single-file parser cannot fail here because it does
        // not own the sibling roster.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jk-core.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.library()).isEqualTo("jk-core");
        assertThat(dep.module()).startsWith("workspace:");
    }

    @Test
    void workspace_true_cannot_combine_with_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = true, version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void workspace_true_cannot_combine_with_group() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = true, group = "com.example" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must not set `group`");
    }

    @Test
    void workspace_false_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = false }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be `true`");
    }

    @Test
    void parses_workspace_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["core", "io"]
                """);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(workspaceOf(parsed).modules()).containsExactly("core", "io");
        assertThat(workspaceOf(parsed).dependencies()).isEmpty();
    }

    @Test
    void parses_profiles_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [profiles.dev]
                javac = ["-g", "-parameters"]
                jvm-args = ["-Xshare:auto"]

                [profiles.ci]
                inherits = "dev"
                javac = ["-Werror"]
                """);
        assertThat(parsed.profiles().byName()).containsKeys("dev", "ci");
        assertThat(Objects.requireNonNull(parsed.profiles().byName().get("dev")).javacArgs())
                .contains("-g");
        assertThat(Objects.requireNonNull(parsed.profiles().byName().get("ci")).inherits())
                .isEqualTo("dev");
    }

    @Test
    void profiles_tag_keys_record_presence_including_empty_lists() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [profiles.local]
                exclude-tags = ["slow", "bench"]

                [profiles.ci]
                exclude-tags = []
                include-tags = ["smoke"]
                """);
        assertThat(parsed.profiles().byName()).containsKeys("local", "ci");
        var local = Objects.requireNonNull(parsed.profiles().byName().get("local"));
        assertThat(local.excludeTagsSet()).isTrue();
        assertThat(local.includeTagsSet()).isFalse();
        assertThat(local.excludeTags()).containsExactly("slow", "bench");

        var ci = Objects.requireNonNull(parsed.profiles().byName().get("ci"));
        assertThat(ci.excludeTagsSet()).isTrue();
        assertThat(ci.excludeTags()).isEmpty();
        assertThat(ci.includeTagsSet()).isTrue();
        assertThat(ci.includeTags()).containsExactly("smoke");
    }

    @Test
    void parse_test_tags_reads_include_and_exclude(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), PROJECT + """
                        [test]
                        include-tags = ["unit"]
                        exclude-tags = ["slow", "bench"]
                        """);
        var tags = JkBuildParser.parseTestTags(dir.resolve("jk.toml"));
        assertThat(tags.includeTags()).containsExactly("unit");
        assertThat(tags.excludeTags()).containsExactly("slow", "bench");
    }

    /**
     * `jk test --profile integration` is the documented pre-merge command, and it died on the
     * first member that did not declare the profile itself — against a root whose jk.toml does.
     * The tag half of the rule was already right (the CLI rehomes to the root before scanning
     * profile tags); the table a member carries was not.
     */
    @Test
    void a_member_carries_the_workspace_roots_profiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = ["lib"]

                [profiles.integration]
                include-tags = ["integration"]
                """);
        Path lib = Files.createDirectories(dir.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"
                """);

        var member = JkBuildParser.parse(lib.resolve("jk.toml"));
        assertThat(member.profiles().byName().keySet()).contains("integration");
        assertThat(member.profiles().resolve("integration").includeTags()).containsExactly("integration");
    }

    /** A member's own profile wins by name — the root is a base, not an override. */
    @Test
    void a_members_own_profile_beats_the_roots(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = ["lib"]

                [profiles.integration]
                include-tags = ["integration"]

                [profiles.slow]
                include-tags = ["slow"]
                """);
        Path lib = Files.createDirectories(dir.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"

                [profiles.integration]
                include-tags = ["member-only"]
                """);

        var member = JkBuildParser.parse(lib.resolve("jk.toml"));
        assertThat(member.profiles().resolve("integration").includeTags()).containsExactly("member-only");
        assertThat(member.profiles().contains("slow"))
                .as("the root's others still arrive")
                .isTrue();
    }

    @Test
    void parse_test_tags_is_empty_when_the_manifest_is_absent(@TempDir Path dir) {
        assertThat(JkBuildParser.parseTestTags(dir.resolve("jk.toml"))).isEqualTo(JkBuildParser.TestTomlTags.EMPTY);
    }

    /**
     * A manifest that exists and does not parse is an error. {@code TestTomlTags.EMPTY} means
     * "this project filters no tags"; a swallowed parse error would silently widen the suite.
     */
    @Test
    void parse_test_tags_refuses_a_malformed_manifest(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), PROJECT + "[test\n");
        assertThatThrownBy(() -> JkBuildParser.parseTestTags(dir.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("failed to parse jk.toml");
    }

    /** The owner's interpolation whitelist reaches the test-tag baseline like every other table. */
    @Test
    void parse_test_tags_rejects_interpolation_outside_the_whitelist(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), PROJECT + """
                        [test]
                        exclude-tags = ["${SKIP_TAG}"]
                        """);
        assertThatThrownBy(() -> JkBuildParser.parseTestTags(dir.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("test.exclude-tags");
    }
}
