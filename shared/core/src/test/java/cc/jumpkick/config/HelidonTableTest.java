// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.LibDir;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The built-in {@code [helidon]} table against the shipped {@code plugins/helidon/jk-plugin.toml}:
 * the platform BOM, the processors, the test config profile and the {@code libs/} layout it
 * implies, its schema, and the Helidon 27 and Java 27 floors.
 */
class HelidonTableTest {

    private static final String SERVICE_REGISTRY = "io.helidon.service:helidon-service-registry";

    private static final String APP = """
            name    = "quickstart"
            group   = "com.example"
            version = "1.0.0"
            java    = 27

            [application]
            main = "com.example.Main"
            """;

    @Test
    void the_version_alone_implies_the_bom_the_test_profile_and_the_libs_layout() {
        JkBuild b = JkBuildParser.parse(APP + """

                [helidon]
                version = "27.0.1"
                """);

        var platform = b.dependencies().of(Scope.PLATFORM);
        assertThat(platform).singleElement().satisfies(dep -> {
            assertThat(dep.module()).isEqualTo("io.helidon:helidon-dependencies");
            assertThat(dep.version().raw()).isEqualTo("27.0.1");
            assertThat(dep.impliedBy()).isEqualTo("helidon");
        });
        assertThat(b.build().testJvm().systemProperties()).containsExactly(Map.entry("helidon.config.profile", "test"));
        assertThat(b.libDir()).isEqualTo(LibDir.defaults());
        assertThat(b.pluginConfig("helidon").orElseThrow().values())
                .as("binding stays unset: on when a class carries @Service.GenerateBinding")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "version", "27.0.1",
                        "generate-main", false,
                        "validate", true,
                        "test-binding", false,
                        "layout", "libs",
                        "declarative", false));
    }

    @Test
    void every_key_reads_as_written() {
        JkBuild b = JkBuildParser.parse(APP + """

                [helidon]
                version       = "^27"
                binding       = false
                generate-main = true
                validate      = false
                test-binding  = true
                layout        = "jar"
                declarative   = true
                """);

        assertThat(b.pluginConfig("helidon").orElseThrow().values())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "version", "^27",
                        "binding", false,
                        "generate-main", true,
                        "validate", false,
                        "test-binding", true,
                        "layout", "jar",
                        "declarative", true));
        assertThat(b.libDir()).as("layout = \"jar\" is the plain jar").isNull();
    }

    @Test
    void the_module_s_own_test_profile_wins_over_the_plugin_s() {
        JkBuild properties = JkBuildParser.parse(APP + """

                [helidon]
                version = "27.0.1"

                [test]
                system-properties = { "helidon.config.profile" = "ci", "other" = "1" }
                """);
        assertThat(properties.build().testJvm().systemProperties())
                .containsExactly(Map.entry("helidon.config.profile", "ci"), Map.entry("other", "1"));

        JkBuild flag = JkBuildParser.parse(APP + """

                [helidon]
                version = "27.0.1"

                [test]
                jvm-args = ["-Dhelidon.config.profile=ci"]
                """);
        assertThat(flag.build().testJvm().systemProperties()).isEmpty();
        assertThat(flag.build().testJvm().flags()).containsExactly("-Dhelidon.config.profile=ci");
    }

    @Test
    void a_written_lib_dir_wins_and_the_shaping_keys_shape_the_implied_layout() {
        assertThat(JkBuildParser.parse(withApplicationKeys("lib-dir = false")).libDir())
                .isNull();
        assertThat(JkBuildParser.parse(withApplicationKeys("lib-dir = \"lib\"")).libDir())
                .isEqualTo(new LibDir("lib", true, List.of()));
        assertThat(JkBuildParser.parse(withApplicationKeys("lib-versions = false\nlib-exclude = [\"okio\"]"))
                        .libDir())
                .as("the implied layout reads lib-versions and lib-exclude without lib-dir")
                .isEqualTo(new LibDir("libs", false, List.of("okio")));
    }

    @Test
    void a_module_without_an_application_gets_no_layout() {
        JkBuild library = JkBuildParser.parse("""
                name    = "shared"
                group   = "com.example"
                version = "1.0.0"
                java    = 27

                [helidon]
                version = "27.0.1"
                """);
        assertThat(library.libDir()).isNull();
    }

    @Test
    void the_processors_follow_the_service_registry_and_declarative() {
        JkBuild plain = JkBuildParser.parse(APP + "\n[helidon]\nversion = \"27.0.1\"\n");
        Map<String, String> pins = Map.of("io.helidon:helidon-dependencies", "27.0.1");

        assertThat(PluginContributions.processorDependencies(plain, null, pins, Set.of(SERVICE_REGISTRY)))
                .extracting(dep -> dep.closure().coordinateSpec(), dep -> dep.main(), dep -> dep.test())
                .containsExactly(
                        tuple("io.helidon.codegen:helidon-codegen-apt:27.0.1", true, true),
                        tuple("io.helidon.service:helidon-service-codegen:27.0.1", true, true));
        assertThat(PluginContributions.processorDependencies(plain, null, pins, Set.of()))
                .isEmpty();

        JkBuild declarative = JkBuildParser.parse(APP + "\n[helidon]\nversion = \"27.0.1\"\ndeclarative = true\n");
        assertThat(PluginContributions.processorDependencies(declarative, null, pins, Set.of(SERVICE_REGISTRY)))
                .extracting(dep -> dep.closure().coordinateSpec())
                .containsExactly(
                        "io.helidon.codegen:helidon-codegen-apt:27.0.1",
                        "io.helidon.service:helidon-service-codegen:27.0.1",
                        "io.helidon.bundles:helidon-bundles-apt:27.0.1");
    }

    @Test
    void the_version_is_required_and_helidon_4_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(APP + "\n[helidon]\nlayout = \"libs\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[helidon]")
                .hasMessageContaining("version");
        for (String old : List.of("4.3.0", "^4", "~4.2")) {
            assertThatThrownBy(() -> JkBuildParser.parse(APP + "\n[helidon]\nversion = \"" + old + "\"\n"))
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessage("[helidon].version = \"" + old + "\" is older than 27, the oldest release jk"
                            + " supports here — set version = \"27.0.1\"");
        }
        assertThat(JkBuildParser.parse(APP + "\n[helidon]\nversion = \"latest\"\n")
                        .pluginConfig("helidon"))
                .isPresent();
    }

    @Test
    void a_layout_jk_does_not_know_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(APP + "\n[helidon]\nversion = \"27.0.1\"\nlayout = \"war\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[helidon].layout = \"war\" must be one of \"libs\", \"jar\"");
    }

    @Test
    void a_module_below_java_27_is_refused_with_the_line_that_fixes_it(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("old"));
        Files.writeString(project.resolve("jk.toml"), APP.replace("java    = 27", "java    = 25") + """

                [helidon]
                version = "27.0.1"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(project.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[helidon] needs java 27 or later, and module `quickstart` builds for java 25 — set java"
                        + " = 27 in its jk.toml");

        Files.writeString(project.resolve("jk.toml"), APP.replace("java    = 27\n", "") + """

                [helidon]
                version = "27.0.1"
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(project.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("names no java level — set java = 27");
    }

    @Test
    void a_member_is_judged_by_the_java_level_it_inherits(@TempDir Path dir) throws Exception {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                name    = "ws"
                group   = "com.example"
                version = "1.0.0"
                java    = 27

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                name = "app"

                [application]
                main = "com.example.Main"

                [helidon]
                version = "27.0.1"
                """);
        assertThat(JkBuildParser.parse(app.resolve("jk.toml")).project().javaRelease())
                .isEqualTo(27);
        assertThat(WorkspaceLoader.loadModules(ws, JkBuildParser.parseLocal(ws.resolve("jk.toml"))))
                .hasSize(1);

        Files.writeString(
                ws.resolve("jk.toml"), Files.readString(ws.resolve("jk.toml")).replace("27", "25"));
        assertThatThrownBy(() -> JkBuildParser.parse(app.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("module `app` builds for java 25");
        assertThatThrownBy(() -> WorkspaceLoader.loadModules(ws, JkBuildParser.parseLocal(ws.resolve("jk.toml"))))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("module `app` builds for java 25");
    }

    private static String withApplicationKeys(String keys) {
        return APP + keys + """

                [helidon]
                version = "27.0.1"
                """;
    }
}
