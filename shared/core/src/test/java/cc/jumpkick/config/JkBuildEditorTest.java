// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.util.MinimalToml;
import org.junit.jupiter.api.Test;

/**
 * Tests the surgical text editor against the v0.7 name-as-key sub-table format. Each test
 * round-trips the edited content through {@link JkBuildParser} to confirm the file is both valid
 * TOML and a well-formed {@code jk.toml}.
 */
class JkBuildEditorTest {

    private static final String BASE = """
            group    = "com.example"
            name     = "widget"
            version  = "0.1.0"
            jdk      = 25
            java     = 25
            """;

    @Test
    void add_to_file_without_dependencies_table_creates_sub_scope() {
        String result = JkBuildEditor.addDependency(
                BASE, Scope.MAIN, "jackson-databind", "com.fasterxml.jackson.core", "jackson-databind", "=2.18.2");

        assertThat(result).contains("[dependencies]");
        assertThat(result)
                .contains("jackson-databind = { group = \"com.fasterxml.jackson.core\", version = \"=2.18.2\" }");

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.library()).isEqualTo("jackson-databind");
            assertThat(d.module()).isEqualTo("com.fasterxml.jackson.core:jackson-databind");
            assertThat(d.version()).isInstanceOf(VersionSelector.Exact.class);
        });
    }

    @Test
    void add_omits_artifact_when_it_matches_name() {
        // `acme-thing` is deliberately not in the bundled catalog — that
        // way this test exercises the structured-form artifact-omission
        // branch, not the catalog-shorthand branch tested below.
        String result = JkBuildEditor.addDependency(BASE, Scope.MAIN, "acme-thing", "com.acme", "acme-thing", "1.0.0");

        assertThat(result).contains("acme-thing = { group = \"com.acme\", version = \"1.0.0\" }");
        assertThat(result).doesNotContain(", name =");
    }

    @Test
    void add_emits_shorthand_for_catalog_known_names() {
        // picocli is in the bundled catalog → cargo-style one-liner.
        String result = JkBuildEditor.addDependency(BASE, Scope.MAIN, "picocli", "info.picocli", "picocli", "4.7.7");

        assertThat(result).contains("picocli = \"4.7.7\"");
        assertThat(result).doesNotContain("group = \"info.picocli\"");

        // Round-trips through the parser to the same coord.
        JkBuild parsed = JkBuildParser.parse(result);
        Dependency d = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(d.library()).isEqualTo("picocli");
        assertThat(d.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void add_uses_structured_form_when_group_disagrees_with_catalog() {
        // Same name as a catalog entry but a deliberately different group:
        // the user is overriding the catalog, so the structured form is
        // emitted (the shorthand would lie about the resolved coord).
        String result = JkBuildEditor.addDependency(BASE, Scope.MAIN, "picocli", "io.fork", "picocli", "4.7.7");

        assertThat(result).contains("picocli = { group = \"io.fork\", version = \"4.7.7\" }");
    }

    @Test
    void add_emits_artifact_when_it_differs_from_name() {
        String result = JkBuildEditor.addDependency(
                BASE, Scope.MAIN, "spring-web", "org.springframework.boot", "spring-boot-starter-web", "3.4.0");

        assertThat(result)
                .contains("spring-web = { group = \"org.springframework.boot\", "
                        + "name = \"spring-boot-starter-web\", version = \"3.4.0\" }");

        JkBuild parsed = JkBuildParser.parse(result);
        Dependency d = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(d.library()).isEqualTo("spring-web");
        assertThat(d.module()).isEqualTo("org.springframework.boot:spring-boot-starter-web");
    }

    @Test
    void add_to_existing_scope_appends_under_header() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(start, Scope.MAIN, "tomlj", "org.tomlj", "tomlj", "1.1.1");

        assertThat(result).contains("picocli = { group = \"info.picocli\", version = \"4.7.7\" }");
        assertThat(result).contains("tomlj = \"1.1.1\"");
        // Both entries appear under [dependencies]; no second header.
        int firstHeader = result.indexOf("[dependencies]");
        int lastHeader = result.lastIndexOf("[dependencies]");
        assertThat(firstHeader).isEqualTo(lastHeader);
    }

    @Test
    void add_test_scope_alongside_main_creates_separate_sub_table() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(
                start, Scope.TEST, "junit-jupiter", "org.junit.jupiter", "junit-jupiter", "6.1.0");

        assertThat(result).contains("[dependencies]");
        assertThat(result).contains("[test-dependencies]");
        // The new sub-table comes after main, and the entry is under it.
        int testHeader = result.indexOf("[test-dependencies]");
        int junitLine = result.indexOf("junit-jupiter =");
        assertThat(junitLine).isGreaterThan(testHeader);

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void add_treats_bare_dependencies_as_main_shorthand() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(start, Scope.MAIN, "tomlj", "org.tomlj", "tomlj", "1.1.1");

        // Should extend the flat table, not create a second [dependencies] header.
        assertThat(result.indexOf("[dependencies]")).isEqualTo(result.lastIndexOf("[dependencies]"));
        assertThat(result).contains("tomlj = \"1.1.1\"");

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(2);
    }

    @Test
    void add_test_dep_when_bare_dependencies_is_flat_shorthand() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(
                start, Scope.TEST, "junit-jupiter", "org.junit.jupiter", "junit-jupiter", "6.1.0");

        assertThat(result).contains("[test-dependencies]");
        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void add_rejects_duplicate_name_in_same_scope() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        assertThatThrownBy(() ->
                        JkBuildEditor.addDependency(start, Scope.MAIN, "picocli", "info.picocli", "picocli", "5.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already contains \"picocli\"");
    }

    @Test
    void add_allows_same_name_in_different_scope() {
        String start = BASE + """

                [provided-dependencies]
                lombok = { group = "org.projectlombok", version = "1.18.34" }
                """;

        // The same short name is fine in a different scope (e.g., lombok
        // is both `processor` and `provided`).
        String result =
                JkBuildEditor.addDependency(start, Scope.PROCESSOR, "lombok", "org.projectlombok", "lombok", "1.18.34");

        assertThat(result).contains("[provided-dependencies]");
        assertThat(result).contains("[processor-dependencies]");
    }

    @Test
    void remove_drops_the_named_entry() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                tomlj   = { group = "org.tomlj",    version = "1.1.1" }
                """;

        String result = JkBuildEditor.removeDependency(start, Scope.MAIN, "picocli");

        assertThat(result).doesNotContain("picocli");
        assertThat(result).contains("tomlj");

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.MAIN).getFirst().library()).isEqualTo("tomlj");
    }

    @Test
    void remove_last_entry_leaves_sub_table_in_place() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.removeDependency(start, Scope.MAIN, "picocli");

        // We don't try to remove the now-empty sub-table; minimal blast radius.
        assertThat(result).contains("[dependencies]");
        assertThat(result).doesNotContain("picocli");

        // An empty sub-table is still valid TOML.
        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void remove_drops_workspace_shorthand_entry() {
        String start = BASE + """

                [test-dependencies]
                junit-jupiter.workspace = true
                assertj-core.workspace  = true
                """;

        String result = JkBuildEditor.removeDependency(start, Scope.TEST, "junit-jupiter");

        assertThat(result).doesNotContain("junit-jupiter");
        assertThat(result).contains("assertj-core.workspace");
    }

    @Test
    void remove_when_scope_missing_throws_clear_error() {
        assertThatThrownBy(() -> JkBuildEditor.removeDependency(BASE, Scope.TEST, "anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-dependencies not found");
    }

    @Test
    void remove_when_name_missing_throws_clear_error() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        assertThatThrownBy(() -> JkBuildEditor.removeDependency(start, Scope.MAIN, "tomlj"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"tomlj\" not found");
    }

    @Test
    void add_preserves_user_comments_and_blank_lines() {
        String start = """
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                jdk      = 25
                java     = 25

                # User comment above deps.
                [dependencies]
                # Inline comment about picocli.
                picocli = { group = "info.picocli", version = "4.7.7" }

                # Trailing comment block.
                [profiles.dev]
                javac = ["-g"]
                """;

        String result = JkBuildEditor.addDependency(start, Scope.MAIN, "tomlj", "org.tomlj", "tomlj", "1.1.1");

        assertThat(result).contains("# User comment above deps.");
        assertThat(result).contains("# Inline comment about picocli.");
        assertThat(result).contains("# Trailing comment block.");
        assertThat(result).contains("[profiles.dev]");
        assertThat(result).contains("tomlj = \"1.1.1\"");
    }

    // --- workspace modules -------------------------------------------------

    private static final String WS = """
            group    = "cc.jumpkick"
            name     = "jk"
            version  = "0.1.0"

            [workspace]
            modules = ["core", "io"]
            """;

    @Test
    void add_module_appends_to_inline_array() {
        String result = JkBuildEditor.addWorkspaceModule(WS, "cli");
        assertThat(result).contains("modules = [\"core\", \"io\", \"cli\"]");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_is_idempotent() {
        String result = JkBuildEditor.addWorkspaceModule(WS, "io");
        assertThat(result).isEqualTo(WS);
    }

    @Test
    void add_module_to_empty_inline_array() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "core");
        assertThat(result).contains("modules = [\"core\"]");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core");
    }

    @Test
    void add_module_to_multiline_array_preserves_shape_and_comments() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                # the workspace
                [workspace]
                modules = [
                    "core",
                    "io",
                ]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(result).contains("# the workspace");
        assertThat(result).contains("    \"cli\",");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_to_multiline_array_without_trailing_comma() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = [
                    "core",
                    "io"
                ]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_when_no_modules_key_creates_one() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "core");
        assertThat(result).contains("modules = [\"core\"]");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core");
    }

    @Test
    void add_module_without_workspace_table_throws() {
        assertThatThrownBy(() -> JkBuildEditor.addWorkspaceModule(BASE, "core"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no [workspace] table");
    }

    @Test
    void remove_module_from_inline_array() {
        assertThat(JkBuildParser.parse(JkBuildEditor.removeWorkspaceModule(WS, "core"))
                        .workspace()
                        .modules())
                .containsExactly("io");
        assertThat(JkBuildParser.parse(JkBuildEditor.removeWorkspaceModule(WS, "io"))
                        .workspace()
                        .modules())
                .containsExactly("core");
    }

    @Test
    void remove_module_from_multiline_array_drops_the_element_line() {
        String start = """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                # the workspace
                [workspace]
                modules = [
                    "core",
                    "io",
                ]
                """;
        String result = JkBuildEditor.removeWorkspaceModule(start, "core");
        assertThat(result).contains("# the workspace").doesNotContain("\"core\"");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("io");
    }

    @Test
    void remove_module_is_idempotent_for_absent_paths_and_missing_tables() {
        assertThat(JkBuildEditor.removeWorkspaceModule(WS, "not-a-module")).isEqualTo(WS);
        assertThat(JkBuildEditor.removeWorkspaceModule(BASE, "core")).isEqualTo(BASE);
    }

    @Test
    void remove_last_module_leaves_an_empty_array() {
        String one = JkBuildEditor.removeWorkspaceModule(WS, "core");
        String none = JkBuildEditor.removeWorkspaceModule(one, "io");
        assertThat(JkBuildParser.parse(none).workspace().modules()).isEmpty();
    }

    @Test
    void register_module_creates_the_workspace_table_for_a_plain_project() {
        String result = JkBuildEditor.registerWorkspaceModule(BASE, "core");
        assertThat(result).contains("[workspace]");
        assertThat(result).contains("modules = [\"core\"]");
        // The original project identity keys are preserved.
        assertThat(result).contains("name     = \"widget\"");
        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().modules()).containsExactly("core");
    }

    @Test
    void register_module_appends_to_an_existing_workspace_table() {
        String once = JkBuildEditor.registerWorkspaceModule(BASE, "core");
        String twice = JkBuildEditor.registerWorkspaceModule(once, "cli");
        assertThat(JkBuildParser.parse(twice).workspace().modules()).containsExactly("core", "cli");
        // Idempotent — re-registering an existing module is a no-op.
        assertThat(JkBuildEditor.registerWorkspaceModule(twice, "core")).isEqualTo(twice);
    }

    @Test
    void name_validation_rejects_bad_characters() {
        assertThatThrownBy(() -> JkBuildEditor.addDependency(BASE, Scope.MAIN, "has spaces", "g", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dependency name must match");
        assertThatThrownBy(() -> JkBuildEditor.addDependency(BASE, Scope.MAIN, "9starts-with-digit", "g", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JkBuildEditor.addDependency(BASE, Scope.MAIN, "", "g", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void set_artifacts_requires_application_main() {
        assertThatThrownBy(() -> JkBuildEditor.setArtifacts(BASE, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[application].main");
    }

    @Test
    void set_artifacts_writes_assembly_on_an_existing_application() {
        String start = BASE + "[application]\nmain = \"demo.App\"\n";
        String fat = JkBuildEditor.setArtifacts(start, true, false);
        assertThat(fat).contains("[application]").contains("assembly = true").doesNotContain("minified");
        assertThat(JkBuildParser.parse(fat).assembly()).isTrue();
        assertThat(JkBuildParser.parse(fat).minified()).isFalse();
    }

    @Test
    void minified_writes_both_keys_because_artifacts_are_additive() {
        String min = JkBuildEditor.setArtifacts(BASE + "[application]\nmain = \"demo.App\"\n", false, true);
        assertThat(min).contains("assembly = true").contains("minified = true");

        JkBuild parsed = JkBuildParser.parse(min);
        assertThat(parsed.minified()).isTrue();
        assertThat(parsed.assembly())
                .as("a minified build ships the fat jar too")
                .isTrue();
    }

    @Test
    void set_artifacts_preserves_main_and_other_keys() {
        String start = BASE + """
                        [application]
                        main = "demo.App"
                        # keep me
                        """;
        String result = JkBuildEditor.setArtifacts(start, false, true);
        assertThat(result).contains("main = \"demo.App\"").contains("# keep me").contains("minified = true");
        assertThat(JkBuildParser.parse(result).mainClass()).isEqualTo("demo.App");
    }

    @Test
    void set_artifacts_replaces_and_removes() {
        String start = BASE + "[application]\nmain = \"demo.App\"\nassembly = true\n";
        String min = JkBuildEditor.setArtifacts(start, false, true);
        assertThat(min).contains("assembly = true").contains("minified = true");

        String off = JkBuildEditor.setArtifacts(min, false, false);
        assertThat(off).doesNotContain("assembly =").doesNotContain("minified =");
        assertThat(off).contains("main = \"demo.App\"");
        assertThat(JkBuildParser.parse(off).assembly()).isFalse();
    }

    @Test
    void the_old_shrink_spelling_is_rejected_with_the_replacement() {
        assertThatThrownBy(() -> JkBuildParser.parse(BASE + "[application]\nassembly = \"shrink\"\n"))
                .hasMessageContaining("minified = true");
    }

    // ───────────────────────────────────────────────────────────────
    // One renderer: every scalar this writer emits goes through MinimalToml
    // ───────────────────────────────────────────────────────────────

    /**
     * A value carrying TOML metacharacters survives render → parse unchanged. The editor's own
     * escaper left every character below {@code 0x20} raw, and a raw control character is not a
     * legal TOML basic string — so {@link JkBuildEditor}'s own {@code validated} rejected the file
     * it had just written, and the user saw "edit produced invalid TOML" for a legal input.
     */
    @Test
    void a_metacharacter_bearing_value_round_trips_through_the_writer() {
        String nasty = "com.acme\\weird\"quoted\tand\u0007bell";
        String edited = JkBuildEditor.addDependency(BASE, Scope.MAIN, "widget-lib", nasty, "widget-lib", "1.2.3");
        JkBuild parsed = JkBuildParser.parse(edited);
        assertThat(parsed.dependencies().of(Scope.MAIN).getFirst().group()).isEqualTo(nasty);
    }

    @Test
    void a_metacharacter_bearing_module_path_round_trips() {
        String weird = "mods/a\"b";
        String edited = JkBuildEditor.registerWorkspaceModule(BASE, weird);
        assertThat(JkBuildParser.parse(edited).workspace().modules()).containsExactly(weird);
        // …and removing it finds the same element it wrote.
        assertThat(JkBuildEditor.removeWorkspaceModule(edited, weird)).doesNotContain("a\\\"b");
    }

    // ───────────────────────────────────────────────────────────────
    // setRootScalar
    // ───────────────────────────────────────────────────────────────

    @Test
    void set_root_scalar_replaces_in_place_and_keeps_indent() {
        String edited = JkBuildEditor.setRootScalar(BASE + "[application]\nmain = \"demo.App\"\n", "java", "21");
        assertThat(edited).contains("java     = 21").doesNotContain("java     = 25");
        assertThat(JkBuildParser.parse(edited).project().javaRelease()).isEqualTo(21);
    }

    /** A bare key after a header would land inside that table, so a new one goes before the first. */
    @Test
    void set_root_scalar_inserts_before_the_first_table() {
        String start = """
                group   = "com.example"
                name    = "widget"
                version = "0.1.0"
                jdk     = 25

                [application]
                main = "demo.App"
                """;
        String edited = JkBuildEditor.setRootScalar(start, "java", "21");
        assertThat(edited.indexOf("java = 21")).isLessThan(edited.indexOf("[application]"));
        assertThat(JkBuildParser.parse(edited).project().javaRelease()).isEqualTo(21);
        assertThat(JkBuildParser.parse(edited).mainClass()).isEqualTo("demo.App");
    }

    @Test
    void set_root_scalar_appends_when_there_is_no_table_at_all() {
        String edited = JkBuildEditor.setRootScalar(BASE, "java", "21");
        assertThat(JkBuildParser.parse(edited).project().javaRelease()).isEqualTo(21);
    }

    /** A string value is TOML-encoded by the caller through the one encoder, and survives. */
    @Test
    void set_root_scalar_round_trips_a_quoted_value() {
        String weird = "wid\"get";
        String edited = JkBuildEditor.setRootScalar(BASE, "name", MinimalToml.quote(weird));
        assertThat(JkBuildParser.parse(edited).project().name()).isEqualTo(weird);
    }

    /**
     * The value form is not the regex's business. {@code McpManifest} used to match only
     * {@code java\s*=\s*\d+\s*$}, so a commented or quoted value missed — and the miss fell
     * through to the "insert a new root key" branch, writing a second {@code java =} line. Two
     * root keys of the same name is invalid TOML, and nothing validated the result before it hit
     * the disk.
     */
    @Test
    void set_root_scalar_replaces_an_annotated_value_instead_of_duplicating_the_key() {
        String start = """
                name    = "widget"
                group   = "com.example"
                version = "0.1.0"
                jdk     = 25
                java    = 17  # bumped when the CI image moves

                [dependencies]
                """;
        String edited = JkBuildEditor.setRootScalar(start, "java", "21");
        assertThat(edited).containsOnlyOnce("java    =");
        assertThat(edited).contains("# bumped when the CI image moves");
        assertThat(JkBuildParser.parse(edited).project().javaRelease()).isEqualTo(21);
    }

    /** A {@code #} inside a quoted value is part of the value, not the start of a comment. */
    @Test
    void set_root_scalar_does_not_mistake_a_hash_inside_a_string_for_a_comment() {
        String start = BASE.replace("name     = \"widget\"", "name     = \"wid#get\"");
        String edited = JkBuildEditor.setRootScalar(start, "name", MinimalToml.quote("plain"));
        assertThat(edited).doesNotContain("#");
        assertThat(JkBuildParser.parse(edited).project().name()).isEqualTo("plain");
    }

    @Test
    void set_root_scalar_refuses_a_key_that_would_need_quoting() {
        assertThatThrownBy(() -> JkBuildEditor.setRootScalar(BASE, "not a key", "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
