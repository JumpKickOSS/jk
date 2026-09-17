// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.taglib;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code [taglib]} table as the generator entry it expands to: no tool, the worker's own main. */
class TaglibPresetPlanTest {

    private static final Path SHIM = Path.of("/shelf/jk-taglib.jar");

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new TaglibPreset().manifest();
        assertThat(manifest.id()).isEqualTo("jk-taglib");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKTL:");
    }

    /** jenkins core's table: an empty one over the main resources. */
    @Test
    void the_defaults_scan_main_resources_with_no_tool() {
        GeneratorEntry entry = TaglibPreset.entry(new PluginConfig("taglib", Map.of()), List.of(SHIM));

        assertThat(entry.name()).isEqualTo("taglib");
        assertThat(entry.stepName()).isEqualTo("generate-taglib");
        assertThat(entry.toolArtifact()).isNull();
        assertThat(entry.toolCoordinate()).isNull();
        assertThat(entry.main()).isEqualTo("cc.jumpkick.taglib.TaglibMain");
        assertThat(entry.inputs()).containsExactly("src/main/resources/**/*.jelly");
        assertThat(entry.args()).containsExactly("--out", "${out}", "${module.dir}/src/main/resources");
        assertThat(entry.contributes()).isEqualTo(GeneratorEntry.Contribution.SOURCES);
        assertThat(entry.out()).isEqualTo("generated/taglib");
        assertThat(entry.classpath()).containsExactly(SHIM);
    }

    @Test
    void every_key_reaches_the_main() {
        GeneratorEntry entry = TaglibPreset.entry(
                new PluginConfig(
                        "taglib",
                        Map.of(
                                "resources",
                                List.of("src/main/resources", "src/filter/resources"),
                                "encoding",
                                "ISO-8859-1")),
                List.of(SHIM));

        assertThat(entry.inputs()).containsExactly("src/main/resources/**/*.jelly", "src/filter/resources/**/*.jelly");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--encoding",
                        "ISO-8859-1",
                        "${module.dir}/src/main/resources",
                        "${module.dir}/src/filter/resources");
    }

    @Test
    void the_worker_names_its_own_code_source() {
        assertThat(TaglibPreset.ownJar()).singleElement().satisfies(p -> assertThat(p)
                .exists());
    }
}
