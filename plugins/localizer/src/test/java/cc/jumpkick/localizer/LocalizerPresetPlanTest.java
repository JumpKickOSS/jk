// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.localizer;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code [localizer]} table as the generator entry it expands to. */
class LocalizerPresetPlanTest {

    private static final Path SHIM = Path.of("/shelf/jk-localizer.jar");

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new LocalizerPreset().manifest();
        assertThat(manifest.id()).isEqualTo("jk-localizer");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKLZ:");
    }

    /** jenkins core's table: the default mask over the default resources, annotations on. */
    @Test
    void the_defaults_scan_main_resources_for_the_bundles() {
        GeneratorEntry entry = LocalizerPreset.entry(
                new PluginConfig("localizer", Map.of("access-modifier-annotations", true)), List.of(SHIM));

        assertThat(entry.name()).isEqualTo("localizer");
        assertThat(entry.stepName()).isEqualTo("generate-localizer");
        assertThat(entry.toolArtifact()).isEqualTo("localizer");
        assertThat(entry.toolCoordinate()).isEqualTo("org.jvnet.localizer:localizer-maven-plugin");
        assertThat(entry.main()).isEqualTo("cc.jumpkick.localizer.LocalizerMain");
        assertThat(entry.inputs()).containsExactly("src/main/resources/**/Messages.properties");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--mask",
                        "Messages.properties",
                        "--access-modifier-annotations",
                        "${module.dir}/src/main/resources");
        assertThat(entry.contributes()).isEqualTo(GeneratorEntry.Contribution.SOURCES);
        assertThat(entry.out()).isEqualTo("generated/localizer");
        assertThat(entry.classpath()).containsExactly(SHIM);
    }

    @Test
    void every_key_reaches_the_shim() {
        GeneratorEntry entry = LocalizerPreset.entry(
                new PluginConfig(
                        "localizer",
                        Map.of(
                                "mask", "*.properties",
                                "resources", List.of("src/main/resources", "src/main/i18n"),
                                "encoding", "ISO-8859-1",
                                "key-pattern", "[a-z.]+",
                                "strict-types", true)),
                List.of(SHIM));

        assertThat(entry.inputs())
                .containsExactly("src/main/resources/**/*.properties", "src/main/i18n/**/*.properties");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--mask",
                        "*.properties",
                        "--encoding",
                        "ISO-8859-1",
                        "--key-pattern",
                        "[a-z.]+",
                        "--strict-types",
                        "${module.dir}/src/main/resources",
                        "${module.dir}/src/main/i18n");
    }

    @Test
    void the_worker_names_its_own_code_source() {
        assertThat(LocalizerPreset.ownJar()).singleElement().satisfies(p -> assertThat(p)
                .exists());
    }
}
