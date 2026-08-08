// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The declarative-contribution layer (build-plugins P2), driven through the real spring-boot manifest. */
class PluginContributionsTest {

    private static JkBuild boot(String extra) {
        return JkBuildParser.parse("""
                [project]
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
        // JK-1545: the contribution lands as written, not exactified with a leading `=`, so a
        // `version = "4"` floor floats within the Boot 4 line at lock.
        assertThat(build.dependencies().of(Scope.PLATFORM).getFirst().version())
                .isInstanceOf(cc.jumpkick.model.VersionSelector.Caret.class);
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
                [project]
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
                [project]
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

        JkBuild.Project thin =
                JkBuild.Project.builder("g", "m", "1.0").jdkMajor(25).java(21).build();
        assertThat(PluginContributions.platformDependencies(thin, false, configs, List.of(manifest)))
                .as("no kotlin → condition false")
                .isEmpty();

        JkBuild.Project resolved = JkBuild.Project.builder("g", "m", "1.0")
                .jdkMajor(25)
                .java(21)
                .kotlin(cc.jumpkick.model.VersionSelector.parse("=2.4.0"))
                .build();
        assertThat(PluginContributions.platformDependencies(resolved, false, configs, List.of(manifest)))
                .extracting(PluginContributions.PlatformDep::module)
                .containsExactly("com.acme:kt-bom");
    }
}
