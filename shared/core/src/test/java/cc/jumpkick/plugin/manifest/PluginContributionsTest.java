// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The declarative-contribution layer (build-plugins P2), driven through the real spring-boot manifest. */
class PluginContributionsTest {

    private static JkBuild boot(String extra) {
        return JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                jdk = "25"

                [spring-boot]
                version = "4.0.0"
                """ + extra);
    }

    // ---- [[contribute.platform-dependency]] — the BOM acceptance ------------------------------

    @Test
    void bom_injects_with_interpolated_config_version() {
        JkBuild build = boot("");
        assertThat(build.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module, d -> d.version().raw())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "org.springframework.boot:spring-boot-dependencies", "4.0.0"));
        // The contribution lands as written, not exactified with a leading `=`, so a
        // `version = "4"` floor floats within the Boot 4 line at lock.
        assertThat(build.dependencies().of(Scope.PLATFORM).getFirst().version())
                .isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void user_declared_bom_wins_over_the_contribution() {
        JkBuild build = boot("""
                [platform-dependencies]
                boot-bom = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "3.9.9" }
                """);
        assertThat(build.dependencies().of(Scope.PLATFORM)).hasSize(1);
        assertThat(build.dependencies().of(Scope.PLATFORM).getFirst().version().raw())
                .contains("3.9.9");
    }

    // ---- [[contribute.compiler-args]] ----------------------------------------------------------

    @Test
    void boot_contributes_parameters_for_both_compilers() {
        JkBuild build = boot("");
        assertThat(PluginContributions.javacArgs(build, null, Set.of())).containsExactly("-parameters");
        assertThat(PluginContributions.kotlinArgs(build, null, Set.of())).containsExactly("-java-parameters");
    }

    @Test
    void non_boot_project_contributes_nothing() {
        JkBuild build = JkBuildParser.parse("""
                name = "plain"
                group = "com.example"
                version = "1.0.0"
                jdk = "25"
                """);
        assertThat(PluginContributions.javacArgs(build, null, Set.of())).isEmpty();
        assertThat(PluginContributions.kotlinPlugins(build, null, "2.3.0", Set.of()))
                .isEmpty();
        assertThat(build.dependencies().of(Scope.PLATFORM)).isEmpty();
    }

    // ---- [[contribute.kotlin-plugin]] — the noarg classpath-has acceptance --------------------

    @Test
    void allopen_always_contributes_and_noarg_keys_on_jpa_classpath() {
        JkBuild build = boot("");

        var withoutJpa = PluginContributions.kotlinPlugins(build, null, "2.3.0", Set.of());
        assertThat(withoutJpa)
                .extracting(PluginContributions.KotlinPluginUse::id)
                .containsExactly("org.jetbrains.kotlin.allopen");

        var withJpa = PluginContributions.kotlinPlugins(
                build, null, "2.3.0", Set.of("jakarta.persistence:jakarta.persistence-api"));
        assertThat(withJpa)
                .extracting(PluginContributions.KotlinPluginUse::id)
                .containsExactly("org.jetbrains.kotlin.allopen", "org.jetbrains.kotlin.noarg");

        var noarg = withJpa.get(1);
        assertThat(noarg.group()).isEqualTo("org.jetbrains.kotlin");
        assertThat(noarg.artifact()).isEqualTo("kotlin-noarg-compiler-plugin-embeddable");
        assertThat(noarg.version()).isEqualTo("2.3.0"); // ${kotlin.version} interpolated
        assertThat(noarg.options()).containsExactly("preset=jpa");
    }

    // ---- the grails manifest: groovy compiler lane + source roots -----------------------------

    private static JkBuild grails(String extra) {
        return JkBuildParser.parse("""
                name = "gapp"
                group = "com.example"
                version = "1.0.0"
                jdk = "25"
                groovy = "5.0.7"

                [grails]
                version = "8.0.0-M4"
                """ + extra);
    }

    @Test
    void grails_injects_the_apache_bom_and_parameter_args() {
        JkBuild build = grails("");
        assertThat(build.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module, d -> d.version().raw())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("org.apache.grails:grails-bom", "8.0.0-M4"));
        assertThat(PluginContributions.javacArgs(build, null, Set.of())).containsExactly("-parameters");
        assertThat(PluginContributions.groovyArgs(build, null, Set.of())).containsExactly("--parameters");
        assertThat(PluginContributions.kotlinArgs(build, null, Set.of())).isEmpty();
    }

    @Test
    void grails_contributes_the_grails_app_roots() {
        assertThat(PluginContributions.sourceRoots(grails(""), null))
                .containsExactly(
                        new PluginContributions.SourceRoot("grails-app/domain", false),
                        new PluginContributions.SourceRoot("grails-app/controllers", false),
                        new PluginContributions.SourceRoot("grails-app/services", false),
                        new PluginContributions.SourceRoot("grails-app/taglib", false),
                        new PluginContributions.SourceRoot("grails-app/init", false),
                        new PluginContributions.SourceRoot("grails-app/jobs", false),
                        new PluginContributions.SourceRoot("grails-app/conf", true),
                        new PluginContributions.SourceRoot("grails-app/i18n", true),
                        new PluginContributions.SourceRoot("grails-app/views", true));
        assertThat(PluginContributions.sourceRoots(boot(""), null)).isEmpty();
    }

    // ---- [[contribute.source-roots]] parse validation ------------------------------------------

    @Test
    void source_roots_reject_absolute_escaping_or_untyped_dirs() {
        String base = """
                [plugin]
                id = "p"
                table = "p"

                [[contribute.source-roots]]
                %s
                """;
        assertThatThrownBy(() ->
                        PluginDescriptors.parse(base.formatted("dir = \"/abs/path\"\nkind = \"source\""), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be module-relative");
        assertThatThrownBy(() ->
                        PluginDescriptors.parse(base.formatted("dir = \"../outside\"\nkind = \"source\""), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must not escape the module");
        assertThatThrownBy(() ->
                        PluginDescriptors.parse(base.formatted("dir = \"a/../../b\"\nkind = \"source\""), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must not escape the module");
        assertThatThrownBy(() -> PluginDescriptors.parse(base.formatted("dir = \"grails-app/domain\""), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("kind must be \"source\" or \"resource\"");
        assertThatThrownBy(() -> PluginDescriptors.parse(
                        base.formatted("dir = \"x\"\nkind = \"source\"\nwhen = { classpath-has = \"a:b\" }"), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("classpath-has cannot gate a source-root");
    }

    @Test
    void source_roots_parse_dir_kind_and_condition() {
        var parsed = PluginDescriptors.parse("""
                [plugin]
                id = "p"
                table = "p"

                [[contribute.source-roots]]
                dir = "extra/src"
                kind = "source"

                [[contribute.source-roots]]
                dir = "extra/res"
                kind = "resource"
                when = { native-declared = true }
                """, "p.toml");
        assertThat(parsed.contributions().sourceRoots())
                .containsExactly(
                        new PluginDescriptor.SourceRoot("extra/src", false, null),
                        new PluginDescriptor.SourceRoot(
                                "extra/res", true, new PluginDescriptor.Condition.NativeDeclared()));
    }

    // ---- [[contribute.command-dependency]] — the command-only tool lane ------------------------

    @Test
    void command_dependencies_parse_into_their_own_lane() {
        var parsed = PluginDescriptors.parse("""
                [plugin]
                id = "p"
                table = "p"

                [[contribute.step-dependency]]
                artifact = "aapt2"
                coordinate = "com.acme:aapt2:1.0.0"

                [[contribute.command-dependency]]
                artifact = "adb"
                sdk-component = "platform-tools"
                sdk-path = "platform-tools/adb"

                [[contribute.command-dependency]]
                artifact = "helper"
                coordinate = "com.acme:helper:1.0.0"
                """, "p.toml");
        assertThat(parsed.contributions().stepDependencies())
                .extracting(PluginDescriptor.StepDependency::artifact)
                .containsExactly("aapt2");
        assertThat(parsed.contributions().commandDependencies())
                .extracting(PluginDescriptor.StepDependency::artifact)
                .containsExactly("adb", "helper");
    }

    @Test
    void command_dependency_shares_the_step_lane_entry_rules() {
        String base = """
                [plugin]
                id = "p"
                table = "p"

                [[contribute.command-dependency]]
                %s
                """;
        assertThatThrownBy(() -> PluginDescriptors.parse(
                        base.formatted("artifact = \"x\"\ncoordinate = \"a:b:1\"\nsdk-component = \"tools\""),
                        "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("needs exactly one of");
        assertThatThrownBy(() -> PluginDescriptors.parse(
                        base.formatted(
                                "artifact = \"x\"\ncoordinate = \"a:b:1\"\n" + "when = { classpath-has = \"g:a\" }"),
                        "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("classpath-has cannot gate a command-dependency");
    }

    @Test
    void an_artifact_cannot_sit_in_both_tool_lanes() {
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "p"
                        table = "p"

                        [[contribute.step-dependency]]
                        artifact = "bundletool"
                        coordinate = "com.acme:bundletool:1.0.0"

                        [[contribute.command-dependency]]
                        artifact = "bundletool"
                        coordinate = "com.acme:bundletool:1.0.0"
                        """, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("already a [[contribute.step-dependency]]")
                .hasMessageContaining("declare it once, in the step lane");
    }

    @Test
    void provided_classpath_refuses_a_command_only_tool() {
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "p"
                        table = "p"

                        [[contribute.command-dependency]]
                        artifact = "adb"
                        sdk-component = "platform-tools"

                        [[contribute.provided-classpath]]
                        dependency = "adb"
                        """, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("never joins a compile classpath");
    }

    @Test
    void command_dependencies_evaluate_apart_from_the_step_lane() {
        PluginDescriptor manifest = PluginDescriptors.parse("""
                [plugin]
                id = "cmdlane-fixture"
                table = "cmdlane-fixture"

                [[contribute.step-dependency]]
                artifact = "aapt2"
                coordinate = "com.acme:aapt2:1.0.0"

                [[contribute.command-dependency]]
                artifact = "adb"
                sdk-component = "platform-tools"
                sdk-path = "platform-tools/adb"
                """, "cmdlane-fixture.toml");
        PluginTableRegistry.putBuiltIn(manifest, null);
        JkBuild build = JkBuildParser.parse("""
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                jdk = "25"

                [cmdlane-fixture]
                """);

        assertThat(PluginContributions.stepDependencies(build, null))
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("aapt2");
        var commandDeps = PluginContributions.commandDependencies(build, null);
        assertThat(commandDeps)
                .extracting(PluginContributions.StepDep::artifact)
                .containsExactly("adb");
        assertThat(commandDeps.getFirst().sdkComponent()).isEqualTo("platform-tools");
        assertThat(commandDeps.getFirst().sdkPath()).isEqualTo("platform-tools/adb");
    }

    // ---- manifest-load validation --------------------------------------------------------------

    @Test
    void unknown_interpolation_variable_fails_at_manifest_load() {
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "p"
                        table = "p"

                        [schema]
                        version = { type = "string", required = true }

                        [[contribute.platform-dependency]]
                        coordinate = "a:b:${config.versoin}"
                        """, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("${config.versoin}")
                .hasMessageContaining("declares no `versoin`");
    }

    @Test
    void classpath_has_cannot_gate_a_platform_dependency() {
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "p"
                        table = "p"

                        [[contribute.platform-dependency]]
                        coordinate = "a:b:1"
                        when = { classpath-has = "x:y" }
                        """, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("classpath-has cannot gate a platform-dependency");
    }

    @Test
    void when_predicates_parse_and_reject_unknown_or_stacked() {
        String base = """
                [plugin]
                id = "p"
                table = "p"

                [[contribute.compiler-args]]
                javac = ["-x"]
                when = { %s }
                """;
        assertThat(PluginDescriptors.parse(base.formatted("native-declared = true"), "p.toml")
                        .contributions()
                        .compilerArgs()
                        .getFirst()
                        .when())
                .isInstanceOf(PluginDescriptor.Condition.NativeDeclared.class);
        assertThat(PluginDescriptors.parse(base.formatted("kotlin-project = true"), "p.toml")
                        .contributions()
                        .compilerArgs()
                        .getFirst()
                        .when())
                .isInstanceOf(PluginDescriptor.Condition.KotlinProject.class);
        assertThat(PluginDescriptors.parse(base.formatted("config = \"aot\", equals = \"true\""), "p.toml")
                        .contributions()
                        .compilerArgs()
                        .getFirst()
                        .when())
                .isEqualTo(new PluginDescriptor.Condition.ConfigEquals("aot", "true"));
        assertThatThrownBy(() -> PluginDescriptors.parse(base.formatted("frobnicates = true"), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("no known predicate");
        assertThatThrownBy(() -> PluginDescriptors.parse(
                        base.formatted("native-declared = true, kotlin-project = true"), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one predicate");
    }

    @Test
    void kotlin_project_condition_gates_platform_deps_on_the_resolved_project() {
        // The condition must be evaluated against the RESOLVED project — a thin workspace
        // member inherits kotlin from its root, so the pre-inheritance fold sees kotlin
        // absent and would drop this contribution (WorkspaceResolve re-folds post-inherit).
        PluginDescriptor manifest = PluginDescriptors.parse("""
                [plugin]
                id = "ktextra"
                table = "ktextra"

                [[contribute.platform-dependency]]
                coordinate = "com.acme:kt-bom:1.0.0"
                when = { kotlin-project = true }
                """, "p.toml");
        var configs = Map.of("ktextra", PluginTableRegistry.validate(manifest, org.tomlj.Toml.parse("")));

        Project thin = Project.builder("g", "m", "1.0").jdkMajor(25).java(21).build();
        assertThat(PluginContributions.platformDependencies(thin, false, configs, List.of(manifest)))
                .as("no kotlin → condition false")
                .isEmpty();

        Project resolved = Project.builder("g", "m", "1.0")
                .jdkMajor(25)
                .java(21)
                .kotlin(VersionSelector.parse("=2.4.0"))
                .build();
        assertThat(PluginContributions.platformDependencies(resolved, false, configs, List.of(manifest)))
                .extracting(PluginContributions.PlatformDep::module)
                .containsExactly("com.acme:kt-bom");
    }
}
