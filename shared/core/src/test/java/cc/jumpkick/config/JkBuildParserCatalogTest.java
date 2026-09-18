// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static cc.jumpkick.config.JkBuildParserFixtures.TEST_CATALOG;
import static cc.jumpkick.config.JkBuildParserFixtures.gitSourceOf;
import static cc.jumpkick.config.JkBuildParserFixtures.pathSourceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkBuildParserCatalogTest {

    @Test
    void catalog_key_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse("catalog = \"bundled\"\n" + PROJECT))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("catalog = … was removed");
    }

    @Test
    void libraries_table_in_jk_toml_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [libraries]
                picocli = "io.fork:picocli"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jk-libs.toml");
    }

    @Test
    void shorthand_relative_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                my-lib = "./some/local/path"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(pathSourceOf(dep).rawPath()).isEqualTo("./some/local/path");
        assertThat(dep.module()).isEqualTo("path:my-lib");
    }

    @Test
    void shorthand_parent_relative_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared = "../shared-utils"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(pathSourceOf(dep).rawPath()).isEqualTo("../shared-utils");
    }

    @Test
    void shorthand_absolute_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared = "/opt/libs/shared"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(pathSourceOf(dep).rawPath()).isEqualTo("/opt/libs/shared");
    }

    @Test
    void shorthand_https_url_defaults_to_main_branch() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = "https://github.com/psf/requests"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(gitSourceOf(dep).ref()).isEqualTo(new GitRefSpec.Branch("main"));
        assertThat(gitSourceOf(dep).shallow()).isFalse();
        assertThat(dep.module()).isEqualTo("git:requests");
    }

    @Test
    void shorthand_git_url_with_embedded_branch() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jin = "git://github.com/jin-tonic/jin@mybranch"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(gitSourceOf(dep).ref()).isEqualTo(new GitRefSpec.Branch("mybranch"));
        assertThat(gitSourceOf(dep).shallow()).isFalse();
    }

    @Test
    void shorthand_git_url_with_embedded_tag() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = "https://github.com/psf/requests.git@v1.2.3"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(gitSourceOf(dep).ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(gitSourceOf(dep).shallow()).isFalse(); // URL-embedded → always deep
    }

    @Test
    void shorthand_git_url_with_embedded_sha() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                repo = "git://github.com/user/repo#8f3a1b2c4d5e6f"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(gitSourceOf(dep).ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
    }

    @Test
    void shorthand_git_url_with_subdir() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = "https://github.com/user/repo!components/auth@main"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(gitSourceOf(dep).path()).isEqualTo("components/auth");
        assertThat(gitSourceOf(dep).ref()).isEqualTo(new GitRefSpec.Branch("main"));
    }

    @Test
    void shorthand_ambiguous_string_is_unknown_library() {
        // A string that isn't a version spec, keyword, git URL, or explicit path
        // prefix is never guessed at as a path dep — it's an unknown short name.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                my-dep = "some-dir"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_string_with_slash_is_unknown_library() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = "components/auth"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_reserved_keyword_latest_is_always_catalog() {
        // "latest" is reserved — never statted, always a version lookup.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "latest"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_reserved_keyword_stable_is_always_catalog() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "stable"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_version_starting_with_digit_is_catalog() {
        // "1.2.3" starts with a digit → treated as version spec, not a path.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "1.2.3"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_caret_version_is_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = "^2.18.0"
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isFalse();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    /** {@code name = "managed"} is the catalog spelling of a version a platform supplies. */
    @Test
    void shorthand_managed_keyword_is_a_platform_managed_catalog_dependency() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = "managed"
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPlatformManaged()).isTrue();
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
        assertThat(dep.library()).isEqualTo("picocli");
    }

    @Test
    void shorthand_managed_keyword_needs_a_catalog_name() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "managed"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void isVersionSpecOrKeyword_covers_all_reserved_words() {
        assertThat(JkBuildParser.isVersionSpecOrKeyword("managed")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("latest")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("stable")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("lts")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("preview")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("^2.0.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("~1.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("=1.2.3")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword(">=1.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("1.2.3")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("some-dir")).isFalse();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("components/auth")).isFalse();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("my-library")).isFalse();
    }

    @Test
    void shorthand_string_value_resolves_through_catalog() {
        // The cargo-add experience: `name = "1.0.0"` looks up the coord in
        // the catalog and pins that version.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = "2.18.2"
                """, TEST_CATALOG);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps).hasSize(1);
        assertThat(deps.getFirst().library()).isEqualTo("jackson-databind");
        assertThat(deps.getFirst().module()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(deps.getFirst().version()).isEqualTo(new VersionSelector.Exact("2.18.2", "2.18.2"));
    }

    @Test
    void shorthand_table_without_group_resolves_through_catalog() {
        // A dep table that lists only `version` (no `group`) falls back to
        // the catalog the same way the string shorthand does.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = { version = "4.7.7" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void explicit_group_overrides_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = { group = "io.fork", version = "2.18.2" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("io.fork:jackson-databind");
    }

    @Test
    void unknown_shorthand_string_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                some-unknown-thing = "1.0.0"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void unknown_shorthand_surfaces_did_you_mean_for_split_families() {
        // The parser's unknown-library message includes suggestions drawn
        // from the catalog. For the Jackson family this means typing the
        // unprefixed name surfaces both major-version flavors.
        LibraryCatalog splitFamily = LibraryCatalog.of(Map.of(
                "jackson2-databind", new LibraryCatalog.Module("com.fasterxml.jackson.core", "jackson-databind"),
                "jackson3-databind", new LibraryCatalog.Module("tools.jackson.core", "jackson-databind")));

        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = "2.18.2"
                """, splitFamily))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name `jackson-databind`")
                .hasMessageContaining("Did you mean:")
                .hasMessageContaining("jackson2-databind")
                .hasMessageContaining("jackson3-databind");
    }

    @Test
    void unknown_table_without_group_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                also-unknown = { version = "1.0.0" }
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set a `group`");
    }

    @Test
    void project_jk_libs_toml_overrides_passed_in_catalog(@TempDir Path tmp) throws Exception {
        // Workspace-root jk-libs.toml is the top of the lookup chain when parsing from disk.
        Files.writeString(tmp.resolve("jk.toml"), PROJECT + """
                        [dependencies]
                        picocli = "4.7.7"
                        """);
        Files.writeString(tmp.resolve("jk-libs.toml"), """
                [libraries]
                picocli = "io.fork:picocli"
                """);
        JkBuild parsed = JkBuildParser.parse(tmp.resolve("jk.toml"));
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("io.fork:picocli");
    }

    @Test
    void project_jk_libs_toml_can_introduce_a_brand_new_short_name(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), PROJECT + """
                        [dependencies]
                        internal-widget = "0.1.0"
                        """);
        Files.writeString(tmp.resolve("jk-libs.toml"), """
                [libraries]
                internal-widget = "com.acme:internal-widget"
                """);
        JkBuild parsed = JkBuildParser.parse(tmp.resolve("jk.toml"));
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("com.acme:internal-widget");
    }

    @Test
    void project_jk_libs_toml_with_versioned_coord_is_ignored_with_warning(@TempDir Path tmp) throws Exception {
        // Malformed project layer is fail-soft (warn + skip), same as a bad global layer.
        Files.writeString(tmp.resolve("jk.toml"), PROJECT + """
                        [dependencies]
                        picocli = "4.7.7"
                        """);
        Files.writeString(tmp.resolve("jk-libs.toml"), """
                [libraries]
                bad = "com.acme:bad:1.0.0"
                """);
        // picocli resolves through the system catalog (bundled), not the skipped project layer.
        JkBuild parsed = JkBuildParser.parse(tmp.resolve("jk.toml"));
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void module_may_not_have_jk_libs_toml(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path mod = root.resolve("lib");
        Files.createDirectories(mod);
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["lib"]
                """);
        Files.writeString(mod.resolve("jk.toml"), """
                name = "lib"
                """);
        Files.writeString(mod.resolve("jk-libs.toml"), """
                [libraries]
                x = "com.acme:x"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(mod.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("only allowed at the workspace root");
    }
}
