// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static cc.jumpkick.config.JkBuildParserFixtures.gitSourceOf;
import static cc.jumpkick.config.JkBuildParserFixtures.pathSourceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JkBuildParserDependencyTest {

    @Test
    void parses_name_as_key_dep_with_full_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                slf4j-api = { group = "org.slf4j", name = "slf4j-api", version = "2.0.16" }
                picocli   = { group = "info.picocli", name = "picocli", version = "^4.7.7" }

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = ">=5.10, <6" }
                """);

        var mainDeps = parsed.dependencies().of(Scope.MAIN);
        assertThat(mainDeps).hasSize(2);

        // A bare version is an exact pin; `^` is the opt-in float.
        var slf4j = mainDeps.get(0);
        assertThat(slf4j.library()).isEqualTo("slf4j-api");
        assertThat(slf4j.module()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(slf4j.version()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(slf4j.pinned()).isTrue();

        var picocli = mainDeps.get(1);
        assertThat(picocli.library()).isEqualTo("picocli");
        assertThat(picocli.module()).isEqualTo("info.picocli:picocli");
        assertThat(picocli.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(picocli.pinned()).isFalse();

        var testDeps = parsed.dependencies().of(Scope.TEST);
        assertThat(testDeps).hasSize(1);
        assertThat(testDeps.getFirst().pinned()).isFalse();
        assertThat(testDeps.getFirst().version()).isInstanceOf(VersionSelector.Range.class);
    }

    @Test
    void an_unknown_key_in_a_dependency_table_is_refused_naming_the_key_and_the_handle() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [dependencies]
                        guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre", excludes = ["a:b"] }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("dependencies.guava unknown key `excludes`")
                .hasMessageContaining("expected one of:")
                .hasMessageContaining("exclude");
    }

    @Test
    void an_unknown_key_is_refused_in_every_scope_table() {
        for (String table : new String[] {
            "test-dependencies", "managed-dependencies", "platform-dependencies", "provided-dependencies"
        }) {
            assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                            [%s]
                            leaf = { group = "com.acme", name = "leaf", version = "1.0", verison = "2.0" }
                            """.formatted(table)))
                    .as(table)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining(table + ".leaf unknown key `verison`");
        }
    }

    @Test
    void every_documented_key_is_a_known_key() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                natives = { group = "org.lwjgl", name = "lwjgl", version = "3.3.4", classifier = "natives-linux", optional = true, exclude = ["a:b"], features = ["x"], default-features = false }
                lib = { git = "https://example.com/acme/lib.git", tag = "v1.0.0", path = "lib", submodules = false, verify-signed = false }

                [test-dependencies]
                helpers = { workspace = true, kind = "tests", fixtures = true }
                blob = { group = "com.acme", name = "blob", version = "1.0", sha256 = "%s" }
                """.formatted("0".repeat(64)));
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(2);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(2);
    }

    @Test
    void artifact_defaults_to_key_name() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.library()).isEqualTo("picocli");
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void equals_selector_pins_dep() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = { group = "com.example", version = "=1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) dep.version()).version()).isEqualTo("1.2.3");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void bare_version_is_an_exact_pin() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = { group = "com.example", version = "1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) dep.version()).version()).isEqualTo("1.2.3");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void caret_version_floats() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = { group = "com.example", version = "^1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(((VersionSelector.Caret) dep.version()).version()).isEqualTo("1.2.3");
        assertThat(dep.pinned()).isFalse();
    }

    @Test
    void latest_selector_floats() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = { group = "com.example", version = "latest" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Latest.class);
        assertThat(dep.pinned()).isFalse();
    }

    @Test
    void default_scope_shorthand_treats_dependencies_as_main() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                slf4j-api = { group = "org.slf4j", version = "2.0.16" }
                picocli   = { group = "info.picocli", version = "4.7.7" }
                """);

        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(2);
        assertThat(parsed.dependencies().of(Scope.TEST)).isEmpty();
    }

    @Test
    void main_and_test_dependencies_can_coexist_as_separate_top_level_tables() {
        // [dependencies] (MAIN) and [test-dependencies] (TEST) are independent
        // top-level sections; they may both appear in the same jk.toml.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                stray = { group = "com.example", version = "1.0" }

                [test-dependencies]
                junit = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void inline_path_dependency_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared-utils = { path = "../shared-utils" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(pathSourceOf(dep).rawPath()).isEqualTo("../shared-utils");
        assertThat(dep.module()).isEqualTo("path:shared-utils");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void inline_path_dependency_rejects_an_explicit_coordinate() {
        // Like git, a path dep discovers its coordinate from the target — declaring
        // group/name/version here is an error (named specifically, not a multi-source complaint).
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = { path = "../picocli", group = "info.picocli" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("with `path` must not set `group`");
    }

    @Test
    void git_source_inline_on_dep_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                codec = { git = "https://github.com/acme/codec", tag = "v0.9.1" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(gitSourceOf(dep).originalUrl()).isEqualTo("https://github.com/acme/codec");
        assertThat(gitSourceOf(dep).ref()).isInstanceOf(GitRefSpec.Tag.class);
        assertThat(dep.module()).isEqualTo("git:codec");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void git_source_must_set_exactly_one_ref() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                codec = { git = "https://github.com/acme/codec", tag = "v1", branch = "main" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void git_source_discovers_coordinate_from_repo() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        // Discovery: a placeholder module the resolver rewrites once the repo's
        // project coordinate is known.
        assertThat(dep.module()).isEqualTo("git:mylib");
        assertThat(gitSourceOf(dep).ref()).isEqualTo(new GitRefSpec.Tag("v1.4.0"));
    }

    @Test
    void git_source_rejects_group_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", group = "com.acme" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`group`");
    }

    @Test
    void git_source_rejects_name_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", name = "widgets" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`name`");
    }

    @Test
    void git_source_rejects_version_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", tag = "v1.0", version = "1.0.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`version`");
    }

    @Test
    void git_source_rejects_fetch_field() {
        // `fetch` (the branch-tip freshness window) is gone — every git dep is pinned
        // in jk-lock.toml and only moves on an explicit `jk update --git` / `jk fetch`.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", fetch = "48h" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("fetch");
    }

    @Test
    void git_source_requires_tag_branch_rev_or_embedded_ref() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/widgets" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set `tag`, `branch`, or `rev`");
    }

    @Test
    void git_source_url_embedded_branch_via_at() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/jin-tonic/jin@main" }
                """);
        var src = gitSourceOf(parsed.dependencies().of(Scope.MAIN).getFirst());
        assertThat(src.originalUrl()).isEqualTo("https://github.com/jin-tonic/jin");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Branch("main"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_embedded_tag_via_at_is_deep_clone() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = { git = "https://github.com/psf/requests.git@v1.2.3" }
                """);
        var src = gitSourceOf(parsed.dependencies().of(Scope.MAIN).getFirst());
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse(); // URL-embedded → always deep
    }

    @Test
    void git_source_explicit_tag_is_shallow_clone() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
                """);
        var src = gitSourceOf(parsed.dependencies().of(Scope.MAIN).getFirst());
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.4.0"));
        assertThat(src.shallow()).isTrue(); // explicit tag = → shallow
    }

    @Test
    void git_source_url_embedded_rev_via_hash() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/user/repo#8f3a1b2c4d5e6f" }
                """);
        var src = gitSourceOf(parsed.dependencies().of(Scope.MAIN).getFirst());
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_embedded_ref_and_explicit_ref_conflict() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/widgets@main", branch = "main" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("URL-embedded ref");
    }

    @Test
    void git_source_subdir_via_bang_before_ref() {
        // url!subdir@ref form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo!components/auth@v1.2.3" }
                """);
        var src = gitSourceOf(parsed.dependencies().of(Scope.MAIN).getFirst());
        assertThat(src.originalUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_subdir_via_bang_after_ref() {
        // url@ref!subdir form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "git://github.com/user/repo@v1.2.3!components/auth" }
                """);
        var src = gitSourceOf(parsed.dependencies().of(Scope.MAIN).getFirst());
        assertThat(src.originalUrl()).isEqualTo("git://github.com/user/repo");
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_subdir_via_bang_with_sha() {
        // url#sha!subdir form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo#8f3a1b2c4d5e6f!components/auth" }
                """);
        var src = gitSourceOf(parsed.dependencies().of(Scope.MAIN).getFirst());
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_subdir_and_explicit_path_conflict() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo!components/auth", branch = "main", path = "components/auth" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("sub-directory");
    }

    @Test
    void multiple_sources_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/bad", branch = "main", version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void no_source_on_dep_parses_as_platform_managed() {
        // Contract change (spring-boot plan §3.1): group-only = version supplied by an
        // imported [platform-dependencies] BOM at resolve time. Resolve errors clearly
        // when no BOM covers the module.
        JkBuild b = JkBuildParser.parse(PROJECT + """
                [dependencies]
                widget = { group = "com.example" }
                """);
        assertThat(b.dependencies().of(Scope.MAIN).get(0).isPlatformManaged()).isTrue();
    }

    @Test
    void missing_group_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("group");
    }

    @Test
    void a_coordinate_as_the_key_is_an_unknown_short_name() {
        // The key is the local handle; the coordinate belongs in the value.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                "org.foo:bar" = "1.0"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name")
                .hasMessageContaining("group:artifact:1.2.3");
    }

    @Test
    void gav_string_is_a_maven_coordinate_with_an_exact_version() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = "com.acme:mylib:1.2.3"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.library()).isEqualTo("mylib");
        assertThat(dep.module()).isEqualTo("com.acme:mylib");
        assertThat(dep.version()).isEqualTo(new VersionSelector.Exact("1.2.3", "1.2.3"));
        assertThat(dep.pinned()).isTrue();
        assertThat(dep.isPlatformManaged()).isFalse();
    }

    @Test
    void gav_string_key_is_the_handle_not_the_artifact() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [test-dependencies]
                jupiter = "org.junit.jupiter:junit-jupiter:6.0.0"
                """);
        var dep = parsed.dependencies().of(Scope.TEST).getFirst();
        assertThat(dep.library()).isEqualTo("jupiter");
        assertThat(dep.name()).isEqualTo("junit-jupiter");
        assertThat(dep.group()).isEqualTo("org.junit.jupiter");
    }

    @Test
    void versionless_gav_string_is_platform_managed() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                web = "org.springframework.boot:spring-boot-starter-web"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("org.springframework.boot:spring-boot-starter-web");
        assertThat(dep.isPlatformManaged()).isTrue();
    }

    @Test
    void gav_string_third_slot_takes_any_selector() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                caret = "com.acme:caret:^1.2"
                tilde = "com.acme:tilde:~1.2.3"
                range = "com.acme:range:>=1.2,<2"
                latest = "com.acme:latest:latest"
                """);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps.get(0).version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(deps.get(1).version()).isInstanceOf(VersionSelector.Tilde.class);
        assertThat(deps.get(2).version()).isInstanceOf(VersionSelector.Range.class);
        assertThat(deps.get(2).version().raw()).isEqualTo(">=1.2,<2");
        assertThat(deps.get(3).version()).isInstanceOf(VersionSelector.Latest.class);
    }

    @Test
    void gav_string_with_a_classifier_names_the_inline_table() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                foo = "com.acme:foo:1.0:linux-x86_64"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("classifier")
                .hasMessageContaining("{ group = \"com.acme\", name = \"foo\"");
    }

    @Test
    void gav_string_with_an_empty_version_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                foo = "com.acme:foo:"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("empty version");
    }

    @Test
    void windows_drive_path_is_a_path_not_a_coordinate() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                lib = 'C:\\work\\lib'
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(pathSourceOf(dep).rawPath()).isEqualTo("C:\\work\\lib");
    }

    @Test
    void ssh_and_scp_git_urls_are_git_shorthand() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                a = "ssh://git@github.com/acme/a.git@main"
                b = "git@github.com:acme/b.git@main"
                """);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps.get(0).isGit()).isTrue();
        assertThat(deps.get(1).isGit()).isTrue();
        assertThat(gitSourceOf(deps.get(1)).ref()).isEqualTo(new GitRefSpec.Branch("main"));
    }

    @Test
    void parses_optional_dependency_flag() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre", optional = true }
                jackson-databind = { group = "com.fasterxml.jackson.core", version = "2.18.2" }
                """);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps)
                .filteredOn(d -> d.library().equals("guava"))
                .singleElement()
                .satisfies(d -> assertThat(d.optional()).isTrue());
        assertThat(deps)
                .filteredOn(d -> d.library().equals("jackson-databind"))
                .singleElement()
                .satisfies(d -> assertThat(d.optional()).isFalse());
    }

    @Test
    void parses_features_block_with_dep_names() {
        // Feature `deps` are now dep names (not coord strings). Resolution
        // happens at activation time, against [dependencies.*].
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [features]
                default = ["postgres"]

                [features.postgres]
                deps = ["postgres-jdbc", "hikari"]
                """);
        assertThat(parsed.features().defaults()).containsExactly("postgres");
        assertThat(parsed.features().byName()).containsKey("postgres");
        assertThat(Objects.requireNonNull(parsed.features().byName().get("postgres"))
                        .deps())
                .containsExactly("postgres-jdbc", "hikari");
    }

    // ── splitEmbeddedUrl unit tests ──────────────────────────────────────────

    @Test
    void split_url_no_embedded() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isNull();
    }

    @Test
    void split_url_at_ref_only() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo@main");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isEqualTo("@main");
    }

    @Test
    void split_url_hash_ref_only() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo#8f3a1b");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_ref_then_subdir() {
        var p = JkBuildParser.splitEmbeddedUrl("git://github.com/user/repo@v1.2.3!components/auth");
        assertThat(p.baseUrl()).isEqualTo("git://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("@v1.2.3");
    }

    @Test
    void split_url_subdir_then_ref() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo!components/auth@v1.2.3");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("@v1.2.3");
    }

    @Test
    void split_url_hash_then_subdir() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo#8f3a1b!components/auth");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_subdir_then_hash() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo!components/auth#8f3a1b");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_git_at_host_not_confused_for_ref() {
        // "git@github.com" is userinfo, not an embedded ref
        var p = JkBuildParser.splitEmbeddedUrl("git@github.com:user/repo");
        assertThat(p.baseUrl()).isEqualTo("git@github.com:user/repo");
        assertThat(p.refSpec()).isNull();
    }

    @Test
    void dev_and_test_dev_scope_tables_parse() {
        // Dev-loop scopes (spring-boot plan §3.2): run-only / run+test, never packaged.
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [dev-dependencies]
                devtools = { group = "org.springframework.boot", name = "spring-boot-devtools", version = "4.0.0" }

                [test-dev-dependencies]
                testcontainers = { group = "org.springframework.boot", name = "spring-boot-testcontainers", version = "4.0.0" }
                """);
        assertThat(b.dependencies().of(Scope.DEV)).hasSize(1);
        assertThat(b.dependencies().of(Scope.DEV).get(0).module())
                .isEqualTo("org.springframework.boot:spring-boot-devtools");
        assertThat(b.dependencies().of(Scope.TEST_DEV)).hasSize(1);
    }

    @Test
    void a_test_processor_table_parses_into_its_own_scope() {
        // A processor compile-test alone runs; compile-main keeps the shared table.
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [processor-dependencies]
                lombok = { group = "org.projectlombok", name = "lombok", version = "1.18.42" }

                [test-processor-dependencies]
                mapstruct-processor = { group = "org.mapstruct", name = "mapstruct-processor", version = "1.6.3" }
                """);
        assertThat(b.dependencies().of(Scope.PROCESSOR))
                .extracting(Dependency::module)
                .containsExactly("org.projectlombok:lombok");
        assertThat(b.dependencies().of(Scope.TEST_PROCESSOR))
                .extracting(Dependency::module)
                .containsExactly("org.mapstruct:mapstruct-processor");
    }

    @Test
    void versionless_dep_with_group_parses_as_platform_managed() {
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.0" }

                [dependencies]
                starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                """);
        var dep = b.dependencies().of(Scope.MAIN).get(0);
        assertThat(dep.isPlatformManaged()).isTrue();
        assertThat(dep.module()).isEqualTo("org.springframework.boot:spring-boot-starter-webmvc");
    }

    @Test
    void versionless_dep_without_group_still_errors() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [dependencies]
                mystery = { optional = false }
                """)).hasMessageContaining("must set exactly one of");
    }

    @Test
    void platform_dependencies_keep_their_declaration_order() {
        // The first BOM that manages a module wins, so the table's order is meaning, not style.
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [platform-dependencies]
                spring-boot-dependencies = { group = "org.springframework.boot", version = "3.5.15" }
                knife4j-dependencies = { group = "com.github.xiaoymin", version = "4.5.0" }
                activemq-bom = { group = "org.apache.activemq", version = "6.1.8" }
                """);
        assertThat(b.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::library)
                .containsExactly("spring-boot-dependencies", "knife4j-dependencies", "activemq-bom");
    }

    @Test
    void runtime_and_platform_scope_tables_parse() {
        // [platform-dependencies] (BOM imports) and [runtime-dependencies] are not importer-only:
        // hand-written manifests must be able to declare them (Boot BOM flow).
        JkBuild b = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.0" }

                [runtime-dependencies]
                postgres-jdbc = { group = "org.postgresql", name = "postgresql", version = "42.7.4" }
                """);
        assertThat(b.dependencies().of(Scope.PLATFORM)).hasSize(1);
        assertThat(b.dependencies().of(Scope.RUNTIME)).hasSize(1);
    }
}
