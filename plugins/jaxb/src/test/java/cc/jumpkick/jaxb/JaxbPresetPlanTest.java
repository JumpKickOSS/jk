// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jaxb;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code [jaxb]} table as the generator entry it expands to. */
class JaxbPresetPlanTest {

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new JaxbPreset().manifest();
        assertThat(manifest.id()).isEqualTo("jk-jaxb");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKJAXB:");
    }

    /** An empty table: every schema under src/main/xsd, the packages from the namespaces. */
    @Test
    void the_defaults_generate_from_the_maven_layout() {
        GeneratorEntry entry = JaxbPreset.entry(new PluginConfig("jaxb", Map.of()));

        assertThat(entry.name()).isEqualTo("jaxb");
        assertThat(entry.stepName()).isEqualTo("generate-jaxb");
        assertThat(entry.toolArtifact()).isEqualTo("jaxb-xjc");
        assertThat(entry.toolCoordinate()).isEqualTo("org.glassfish.jaxb:jaxb-xjc");
        assertThat(entry.main()).isEqualTo("com.sun.tools.xjc.Driver");
        assertThat(entry.inputs()).containsExactly("src/main/xsd/**/*.xsd");
        assertThat(entry.args()).containsExactly("-d", "${out}", "-no-header", "-quiet", "${module.dir}/src/main/xsd");
        assertThat(entry.contributes()).isEqualTo(GeneratorEntry.Contribution.SOURCES);
        assertThat(entry.out()).isEqualTo("generated/jaxb");
        assertThat(entry.classpath()).isEmpty();
    }

    @Test
    void every_key_reaches_xjc_and_the_bindings_join_the_inputs() {
        GeneratorEntry entry = JaxbPreset.entry(new PluginConfig(
                "jaxb",
                Map.of(
                        "src",
                        "schemas",
                        "package",
                        "com.acme.schema",
                        "bindings",
                        List.of("src/main/xjb", "bindings/global.xjb"),
                        "encoding",
                        "ISO-8859-1",
                        "extension",
                        true,
                        "arguments",
                        List.of("-npa", "-mark-generated"))));

        assertThat(entry.inputs()).containsExactly("schemas/**/*.xsd", "src/main/xjb/**/*.xjb", "bindings/global.xjb");
        assertThat(entry.args())
                .containsExactly(
                        "-d",
                        "${out}",
                        "-no-header",
                        "-quiet",
                        "-p",
                        "com.acme.schema",
                        "-b",
                        "${module.dir}/src/main/xjb",
                        "-b",
                        "${module.dir}/bindings/global.xjb",
                        "-encoding",
                        "ISO-8859-1",
                        "-extension",
                        "-npa",
                        "-mark-generated",
                        "${module.dir}/schemas");
    }
}
