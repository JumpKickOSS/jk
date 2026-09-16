// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JavadocMode;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.SourcesMode;
import cc.jumpkick.model.TestJvm;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import java.net.URI;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JkBuildRendererTest {

    @Test
    void renders_a_minimal_project_block() {
        JkBuild model = new JkBuild(new Project("com.example", "widget", "1.0.0", 25), JkBuild.Dependencies.empty());
        String out = JkBuildRenderer.render(model);
        assertThat(out).isEqualTo("""
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25"
                java     = 25
                """);
    }

    @Test
    void renders_application_and_native_blocks_when_set() {
        JkBuild model = JkBuild.builder(Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(25)
                        .kotlin(VersionSelector.parse("2.3.21"))
                        .build())
                .application(new JkBuild.Application("com.example.App", true))
                .nativeConfig(new JkBuild.NativeConfig(null, null, List.of(), null, JkBuild.NativeMode.SUPPORTED, null))
                .build();
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("kotlin   = \"2.3.21\"");
        assertThat(out).contains("[application]");
        assertThat(out).contains("main       = \"com.example.App\"");
        assertThat(out).contains("assembly = true");
        assertThat(out).contains("[native]");
    }

    @Test
    void graal_native_spec_round_trips_but_graalvm_default_is_elided() {
        // : "native" is a distinct legal spec — eliding it re-parses as "graalvm".
        JkBuild base = JkBuild.builder(Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(25)
                        .build())
                .nativeConfig(
                        new JkBuild.NativeConfig(null, null, List.of(), "native", JkBuild.NativeMode.SUPPORTED, null))
                .build();
        assertThat(JkBuildRenderer.render(base)).contains("graal      = \"native\"");

        JkBuild dflt = JkBuild.builder(Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(25)
                        .build())
                .nativeConfig(
                        new JkBuild.NativeConfig(null, null, List.of(), "graalvm", JkBuild.NativeMode.SUPPORTED, null))
                .build();
        assertThat(JkBuildRenderer.render(dflt)).doesNotContain("graal      =");
    }

    /**
     * The reachability-metadata pin round-trips for the same reason {@code graal} does:
     * eliding a value that re-parses as something else is how a rendered manifest quietly changes
     * the build. Only the parser's own default is elided.
     */
    @Test
    void metadata_repository_round_trips_and_only_the_default_is_elided() {
        JkBuild pinned = JkBuild.builder(Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(25)
                        .build())
                .nativeConfig(new JkBuild.NativeConfig(
                        null, null, List.of(), null, JkBuild.NativeMode.SUPPORTED, VersionSelector.parse("1.1.4")))
                .build();
        String rendered = JkBuildRenderer.render(pinned);
        assertThat(rendered).contains("metadata-repository = \"1.1.4\"");
        assertThat(JkBuildParser.parse(rendered).nativeConfigOpt().orElseThrow().metadataRepository())
                .isEqualTo(VersionSelector.parse("1.1.4"));

        JkBuild dflt = JkBuild.builder(Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(25)
                        .build())
                .nativeConfig(new JkBuild.NativeConfig(null, null, List.of(), null, JkBuild.NativeMode.SUPPORTED, null))
                .build();
        assertThat(JkBuildRenderer.render(dflt)).doesNotContain("metadata-repository");
    }

    @Test
    void renders_description_when_set() {
        JkBuild model = new JkBuild(
                Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(25)
                        .java(25)
                        .description("A tiny widget library.")
                        .build(),
                JkBuild.Dependencies.empty());
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("description = \"A tiny widget library.\"");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().description()).isEqualTo("A tiny widget library.");
    }

    @Test
    void m2integration_opt_out_round_trips() {
        JkBuild model = JkBuild.of(Project.builder("com.example", "widget", "1.0.0")
                .jdkMajor(25)
                .java(25)
                .m2integration(false)
                .build());
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[m2]").contains("integration = false");
        assertThat(out).doesNotContain("install = false");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().m2integration()).isFalse();
        assertThat(reparsed.project().m2install()).isTrue();
    }

    @Test
    void m2install_opt_out_round_trips() {
        JkBuild model = JkBuild.of(Project.builder("com.example", "widget", "1.0.0")
                .jdkMajor(25)
                .java(25)
                .m2install(false)
                .build());
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[m2]").contains("install = false");
        assertThat(out).doesNotContain("integration = false");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().m2install()).isFalse();
        assertThat(reparsed.project().m2integration()).isTrue();
    }

    @Test
    void application_presence_alone_drives_is_application() {
        JkBuild withApplication = JkBuild.builder(Project.builder("com.example", "app", "1.0.0")
                        .jdkMajor(25)
                        .java(25)
                        .build())
                .application(new JkBuild.Application("com.example.App", false))
                .build();
        assertThat(JkBuildRenderer.render(withApplication)).contains("[application]");
        assertThat(JkBuildParser.parse(JkBuildRenderer.render(withApplication)).isApplication())
                .isTrue();

        // No [application] at all → isApplication() false, nothing emitted.
        JkBuild lib = JkBuild.of(new Project("com.example", "lib", "1.0.0", 25));
        assertThat(JkBuildRenderer.render(lib)).doesNotContain("[application]");
        assertThat(JkBuildParser.parse(JkBuildRenderer.render(lib)).isApplication())
                .isFalse();
    }

    @Test
    void renders_and_round_trips_manifest_table() {
        var manifest = new LinkedHashMap<String, String>();
        manifest.put("Implementation-Title", "jk-test-runner");
        manifest.put("Implementation-Version", "1.0.0");
        JkBuild model =
                JkBuild.of(new Project("com.example", "widget", "1.0.0", 21)).withManifest(manifest);

        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[manifest]");
        assertThat(out).contains("\"Implementation-Title\" = \"jk-test-runner\"");
        assertThat(out).contains("\"Implementation-Version\" = \"1.0.0\"");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.manifest())
                .containsExactly(
                        Map.entry("Implementation-Title", "jk-test-runner"),
                        Map.entry("Implementation-Version", "1.0.0"));
    }

    @Test
    void resolve_policies_render_only_when_they_differ_from_the_defaults() {
        JkBuild plain = JkBuild.builder(
                        Project.builder("com.example", "widget", "1.0.0").build())
                .build();
        assertThat(JkBuildRenderer.render(plain)).doesNotContain("[resolve]");

        JkBuild nearest = JkBuild.builder(
                        Project.builder("com.example", "widget", "1.0.0").build())
                .build(JkBuild.Build.EMPTY.withPinPolicy(PinPolicy.NEAREST))
                .build();
        String out = JkBuildRenderer.render(nearest);
        assertThat(out).contains("[resolve]\npins = \"nearest\"").doesNotContain("platform =");
        assertThat(JkBuildParser.parse(out).build().pinPolicy()).isEqualTo(PinPolicy.NEAREST);
    }

    @Test
    void test_jvm_args_and_system_properties_round_trip() {
        JkBuild model = JkBuild.builder(
                        Project.builder("com.example", "widget", "1.0.0").build())
                .build(JkBuild.Build.EMPTY.withTestJvm(new TestJvm(
                        List.of("-Xmx1g", "--add-opens", "java.base/java.lang=ALL-UNNAMED"),
                        new LinkedHashMap<>(Map.of("spring.profiles.active", "test")))))
                .build();

        String out = JkBuildRenderer.render(model);
        assertThat(out)
                .contains("[test]\njvm-args = [\"-Xmx1g\", \"--add-opens\", \"java.base/java.lang=ALL-UNNAMED\"]\n"
                        + "system-properties = { \"spring.profiles.active\" = \"test\" }\n");
        assertThat(JkBuildParser.parse(out).build().testJvm())
                .isEqualTo(model.build().testJvm());
    }

    @Test
    void features_profiles_javac_source_roots_and_sources_round_trip() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(new Dependency("org.postgresql:postgresql", VersionSelector.parse("42.7.4"))
                        .withOptional(true)));
        JkBuild model = JkBuild.builder(Project.builder("com.example", "widget", "1.0.0")
                        .java(21)
                        .sourcesMode(SourcesMode.ALWAYS)
                        .javadocMode(JavadocMode.STRICT)
                        .build())
                .dependencies(new JkBuild.Dependencies(byScope))
                .features(new Features(
                        Map.of("postgres", new Feature("postgres", List.of("postgresql"), List.of())), List.of()))
                .profiles(new Profiles(Map.of(
                        "preview",
                        new Profile("preview", null, List.of("--enable-preview"), List.of("--enable-preview")))))
                .build(JkBuild.Build.EMPTY
                        .withJavac(new JavacConfig(Map.of(), List.of("-parameters")))
                        .withExtraSrc(List.of("src/main/generated"))
                        .withTestExtraSrc(List.of("src/it/java")))
                .build();

        String out = JkBuildRenderer.render(model);
        assertThat(out)
                .contains("sources  = \"always\"")
                .contains("javadoc  = \"strict\"")
                .contains("[build]\nextra-src = [\"src/main/generated\"]")
                .contains("[test]\nextra-src = [\"src/it/java\"]")
                .contains("[javac]\nargs = [\"-parameters\"]")
                .contains("[profiles.preview]\njavac = [\"--enable-preview\"]\njvm-args = [\"--enable-preview\"]")
                .contains("[features.postgres]\ndeps = [\"postgresql\"]")
                .doesNotContain("[features]\n")
                .contains("postgresql = { group = \"org.postgresql\", version = \"42.7.4\", optional = true }")
                .doesNotContain("jdk");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().sourcesMode()).isEqualTo(SourcesMode.ALWAYS);
        assertThat(reparsed.project().javadocMode()).isEqualTo(JavadocMode.STRICT);
        assertThat(reparsed.build().extraSrc()).containsExactly("src/main/generated");
        assertThat(reparsed.build().testExtraSrc()).containsExactly("src/it/java");
        assertThat(reparsed.build().javac().args()).containsExactly("-parameters");
        assertThat(requireNonNull(reparsed.profiles().byName().get("preview")).javacArgs())
                .containsExactly("--enable-preview");
        assertThat(requireNonNull(reparsed.features().byName().get("postgres")).deps())
                .containsExactly("postgresql");
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .singleElement()
                .extracting(Dependency::optional)
                .isEqualTo(true);
    }

    @Test
    void renders_deps_grouped_by_scope_sorted_within() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency(
                                "org.springframework.boot:spring-boot-starter-web", VersionSelector.parse("3.4.0")),
                        new Dependency(
                                "com.fasterxml.jackson.core:jackson-databind", VersionSelector.parse("2.18.2"))));
        byScope.put(
                Scope.TEST,
                List.of(new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("5.11.0"))));

        JkBuild model =
                new JkBuild(new Project("com.example", "widget", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        // Main scope sub-table appears before the test sub-table.
        int mainIdx = out.indexOf("[dependencies]");
        int testIdx = out.indexOf("[test-dependencies]");
        assertThat(mainIdx).isLessThan(testIdx).isGreaterThan(0);

        // Inline-table format with name-as-key. `artifact` field omitted when
        // the artifactId matches the key.
        assertThat(out).contains("jackson-databind = { group = \"com.fasterxml.jackson.core\", version = \"2.18.2\" }");
        assertThat(out)
                .contains("spring-boot-starter-web = { group = \"org.springframework.boot\", version = \"3.4.0\" }");
        assertThat(out).contains("junit-jupiter = { group = \"org.junit.jupiter\", version = \"5.11.0\" }");

        // Within a scope, sort by short name (alphabetical): jackson before spring.
        int jacksonIdx = out.indexOf("jackson-databind");
        int springIdx = out.indexOf("spring-boot-starter-web");
        assertThat(jacksonIdx).isLessThan(springIdx);
    }

    @Test
    void git_source_renders_without_group_or_name_and_round_trips() {
        // JkBuildParser rejects `group`/`name` alongside `git` — the coordinate is pure
        // discovery from the cloned repo's own jk.toml — so the renderer must never emit them there.
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        GitSource git = GitSource.of(
                "github.com/acme/widgets", "https://github.com/acme/widgets", new GitRefSpec.Tag("v1.0.0"));
        byScope.put(Scope.MAIN, List.of(Dependency.git("widgets", "git:widgets", git)));

        JkBuild model =
                new JkBuild(new Project("com.example", "widget", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        assertThat(out).contains("widgets = { git = \"https://github.com/acme/widgets\", tag = \"v1.0.0\" }");
        assertThat(out).doesNotContain("group =");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.dependencies().of(Scope.MAIN)).hasSize(1);
    }

    @Test
    void renders_repositories_block_when_present() {
        JkBuild model = new JkBuild(
                new Project("com.example", "widget", "1.0.0", 21),
                JkBuild.Dependencies.empty(),
                List.of(new RepositorySpec(
                        "sonatype", URI.create("https://s01.oss.sonatype.org/content/repositories/snapshots/"))));
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[repositories]");
        assertThat(out).contains("sonatype = \"https://s01.oss.sonatype.org/content/repositories/snapshots/\"");
    }

    @Test
    void renders_workspace_block() {
        JkBuild model = JkBuild.builder(new Project("com.example", "widget-parent", "1.0.0", 21))
                .workspace(new Workspace(List.of("core", "app")))
                .build();
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[workspace]");
        assertThat(out).contains("modules = [\"core\", \"app\"]");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.isWorkspaceRoot()).isTrue();
        assertThat(requireNonNull(reparsed.workspace()).modules()).containsExactly("core", "app");
    }

    @Test
    void workspace_tests_kind_renders_as_table() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(Dependency.workspace("lib")));
        byScope.put(Scope.TEST, List.of(Dependency.workspace("lib", DependencyKind.TESTS)));
        JkBuild model = new JkBuild(new Project("com.example", "app", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("lib.workspace = true");
        assertThat(out).contains("lib = { workspace = true, kind = \"tests\" }");
    }

    @Test
    void external_tests_kind_renders_kind_key() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.TEST,
                List.of(Dependency.of("helpers", "com.acme:helpers", VersionSelector.parse("=1.2.3"))
                        .withKind(DependencyKind.TESTS)));
        JkBuild model = new JkBuild(new Project("com.example", "app", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("kind = \"tests\"");
        assertThat(out).contains("com.acme");
    }

    @Test
    void pinned_and_floating_deps_render_with_distinct_version_literals() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency("com.example:pinned", VersionSelector.parse("=1.0.0")),
                        new Dependency("com.example:floating", VersionSelector.parse("^2.0.0"))));

        JkBuild model =
                new JkBuild(new Project("com.example", "widget", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        // An exact pin is the bare version, however it was spelled; a caret keeps its `^`.
        assertThat(out).contains("pinned = { group = \"com.example\", version = \"1.0.0\" }");
        assertThat(out).contains("floating = { group = \"com.example\", version = \"^2.0.0\" }");

        // Round-trip: re-parsing yields the same selector kinds.
        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .extracting(Dependency::version)
                .anyMatch(v -> v instanceof VersionSelector.Exact)
                .anyMatch(v -> v instanceof VersionSelector.Caret);
    }

    @Test
    void platform_dependencies_render_in_declaration_order_while_other_tables_sort() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.PLATFORM,
                List.of(
                        Dependency.of(
                                "spring-boot-dependencies",
                                "org.springframework.boot:spring-boot-dependencies",
                                VersionSelector.parse("3.5.15")),
                        Dependency.of(
                                "knife4j-dependencies",
                                "com.github.xiaoymin:knife4j-dependencies",
                                VersionSelector.parse("4.5.0"))));
        byScope.put(
                Scope.MAIN,
                List.of(
                        Dependency.of("zeta", "com.example:zeta", VersionSelector.parse("1.0")),
                        Dependency.of("alpha", "com.example:alpha", VersionSelector.parse("1.0"))));
        JkBuild model =
                new JkBuild(new Project("com.example", "widget", "1.0.0", 25), new JkBuild.Dependencies(byScope));

        String out = JkBuildRenderer.render(model);

        assertThat(out.indexOf("spring-boot-dependencies")).isLessThan(out.indexOf("knife4j-dependencies"));
        assertThat(out.indexOf("alpha = ")).isLessThan(out.indexOf("zeta = "));
        assertThat(JkBuildParser.parse(out).dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::library)
                .containsExactly("spring-boot-dependencies", "knife4j-dependencies");
    }

    @Test
    void output_round_trips_through_the_parser() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(new Dependency("com.example:lib", VersionSelector.parse("1.0.0"))));
        byScope.put(
                Scope.PLATFORM,
                List.of(new Dependency(
                        "org.springframework.boot:spring-boot-dependencies", VersionSelector.parse("3.4.0"))));

        JkBuild model = new JkBuild(
                new Project("com.example", "widget", "1.0.0", 21),
                new JkBuild.Dependencies(byScope),
                List.of(new RepositorySpec(
                        "internal", URI.create("https://nexus.example.com/repository/maven-public/"))));
        String out = JkBuildRenderer.render(model);

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().group()).isEqualTo("com.example");
        assertThat(reparsed.project().name()).isEqualTo("widget");
        assertThat(reparsed.project().version()).isEqualTo("1.0.0");
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.example:lib");
        // [platform-dependencies] is a first-class parser table now (spring-boot plan §3.3) —
        // rendered BOM entries survive the render → reparse round trip.
        assertThat(reparsed.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-dependencies");
        assertThat(reparsed.repositories()).extracting(RepositorySpec::name).containsExactly("internal");
    }
}
