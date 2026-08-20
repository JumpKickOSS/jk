// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.PluginConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

class PluginTableRegistryTest {

    @Test
    void built_in_spring_boot_manifest_loads_and_owns_its_table() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        assertThat(manifest.id()).isEqualTo("spring-boot");
        assertThat(manifest.schema()).containsKeys("version", "aot", "build-info", "include-tools", "aot-args");
        assertThat(manifest.schema().get("version").required()).isTrue();
        assertThat(manifest.schema().get("aot").defaultValue()).isNull(); // tri-state
        // A table nobody owns stays unowned. Named so that shipping the next built-in plugin
        // does not turn this assertion red — it used to name `micronaut`, which then shipped.
        assertThat(PluginTableRegistry.byTable("not-a-plugin-table")).isEmpty();
    }

    @Test
    void validate_applies_defaults_and_keeps_tristate_absent() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        var table = Toml.parse("version = \"4.0.0\"");
        PluginConfig config = PluginTableRegistry.validate(manifest, table);
        assertThat(config.string("version")).isEqualTo("4.0.0");
        assertThat(config.bool("aot")).isEmpty();
        assertThat(config.bool("build-info", true)).isFalse(); // schema default false wins
        assertThat(config.bool("include-tools", false)).isTrue(); // schema default true wins
        assertThat(config.stringList("aot-args")).isEmpty();
    }

    @Test
    void validate_enforces_required_with_example_and_hint() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        // Assert against the schema's own example/hint, not copies of them: the invariant is
        // "the error surfaces what the manifest says".
        var version = manifest.schema().get("version");
        assertThat(version.example()).isNotBlank();
        assertThat(version.hint()).isNotBlank();
        assertThatThrownBy(() -> PluginTableRegistry.validate(manifest, Toml.parse("build-info = true")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[spring-boot].version is required")
                .hasMessageContaining("e.g. version = \"" + version.example() + "\"")
                .hasMessageContaining(version.hint());
    }

    @Test
    void validate_rejects_wrongly_typed_values() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        assertThatThrownBy(() ->
                        PluginTableRegistry.validate(manifest, Toml.parse("version = \"4.0.0\"\naot-args = [1, 2]")))
                .hasMessageContaining("[spring-boot].aot-args must be an array of strings");
        assertThatThrownBy(
                        () -> PluginTableRegistry.validate(manifest, Toml.parse("version = \"4.0.0\"\naot = \"yes\"")))
                .hasMessageContaining("[spring-boot].aot must be a boolean");
    }

    @Test
    void manifest_parser_rejects_bad_manifests() {
        assertThatThrownBy(() -> PluginDescriptors.parse("[schema]\nx = { type = \"string\" }", "p.toml"))
                .hasMessageContaining("missing the required [plugin] table");
        assertThatThrownBy(() -> PluginDescriptors.parse(
                        "[plugin]\nid = \"x\"\ntable = \"x\"\n[schema]\nk = { type = \"nope\" }", "p.toml"))
                .hasMessageContaining("unknown schema type");
        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "x"
                        table = "x"
                        [scaffold]
                        flag = "spring"
                        """, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[scaffold]")
                .hasMessageContaining("templates/<lang>/<kind>/");
    }

    @org.junit.jupiter.api.Test
    void shipped_manifest_declares_import_rules() {
        var boot = PluginTableRegistry.manifests().stream()
                .filter(m -> m.id().equals("spring-boot"))
                .findFirst()
                .orElseThrow();

        org.assertj.core.api.Assertions.assertThat(boot.gradleImports())
                .anyMatch(r -> r.id().equals("org.springframework.boot")
                        && "version".equals(r.versionTo())
                        && r.missingVersionWarning() != null)
                .anyMatch(r -> r.id().equals("io.spring.dependency-management") && r.versionTo() == null);
    }

    @Test
    void built_in_grails_manifest_loads_with_packaging_roots() {
        var grails = PluginTableRegistry.byTable("grails").orElseThrow();
        assertThat(grails.id()).isEqualTo("grails");
        assertThat(grails.schema()).containsKeys("version", "boot-version");
        assertThat(grails.schema().get("version").required()).isTrue();
        assertThat(grails.schema().get("boot-version").defaultValue()).isEqualTo("4");
        assertThat(grails.code().worker()).isEqualTo("jk-grails");

        var packaging = grails.packaging();
        assertThat(packaging.packager()).isEqualTo("grails-jar");
        assertThat(packaging.execMode()).isEqualTo("jar");
        assertThat(packaging.selfContained()).isTrue();
        assertThat(packaging.classesRun()).isTrue();
        assertThat(packaging.mainScan()).isTrue();
        assertThat(packaging.layeredImage()).isTrue();

        assertThat(grails.contributions().sourceRoots())
                .extracting(PluginDescriptor.SourceRoot::dir)
                .containsExactly(
                        "grails-app/domain",
                        "grails-app/controllers",
                        "grails-app/services",
                        "grails-app/taglib",
                        "grails-app/init",
                        "grails-app/jobs",
                        "grails-app/conf",
                        "grails-app/i18n",
                        "grails-app/views");
    }

    @Test
    void resourceText_reads_from_self_describing_jar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("plug.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("jk-plugin.toml"));
            out.write("""
                    [plugin]
                    id = "zip-plug"
                    table = "zip-plug"
                    version = "1"
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("templates/hello.txt"));
            out.write("hi from zip".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        PluginDescriptor d = PluginDescriptors.parse("""
                [plugin]
                id = "zip-plug"
                table = "zip-plug"
                version = "1"
                """, "zip-plug.jk-plugin.toml");
        PluginTableRegistry.putBuiltIn(d, jar);
        assertThat(PluginTableRegistry.byTable("zip-plug")).isPresent();
        assertThat(PluginTableRegistry.resourceText(d, "templates/hello.txt")).isEqualTo("hi from zip");
    }
}
