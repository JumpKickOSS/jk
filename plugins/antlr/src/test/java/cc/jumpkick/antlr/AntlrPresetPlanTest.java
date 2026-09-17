// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.antlr;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code [antlr]} table as the generator entry it expands to. */
class AntlrPresetPlanTest {

    private static final Path SHIM = Path.of("/shelf/jk-antlr.jar");

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new AntlrPreset().manifest();
        assertThat(manifest.id()).isEqualTo("jk-antlr");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKANTLR:");
    }

    /** jenkins core's table: an empty one, the grammars under src/main/antlr4 with a package per directory. */
    @Test
    void the_defaults_generate_from_the_maven_layout() {
        GeneratorEntry entry = AntlrPreset.entry(new PluginConfig("antlr", Map.of()), List.of(SHIM));

        assertThat(entry.name()).isEqualTo("antlr");
        assertThat(entry.stepName()).isEqualTo("generate-antlr");
        assertThat(entry.toolArtifact()).isEqualTo("antlr");
        assertThat(entry.toolCoordinate()).isEqualTo("org.antlr:antlr4");
        assertThat(entry.main()).isEqualTo("cc.jumpkick.antlr.AntlrMain");
        assertThat(entry.inputs()).containsExactly("src/main/antlr4/**/*.g4");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--src",
                        "${module.dir}/src/main/antlr4",
                        "--lib",
                        "${module.dir}/src/main/antlr4/imports",
                        "${inputs}");
        assertThat(entry.contributes()).isEqualTo(GeneratorEntry.Contribution.SOURCES);
        assertThat(entry.out()).isEqualTo("generated/antlr");
        assertThat(entry.classpath()).containsExactly(SHIM);
    }

    @Test
    void every_key_reaches_the_main() {
        GeneratorEntry entry = AntlrPreset.entry(
                new PluginConfig(
                        "antlr",
                        Map.of(
                                "src",
                                "src/main/antlr",
                                "lib",
                                "src/main/antlr-lib",
                                "package",
                                "com.acme.parser",
                                "listener",
                                false,
                                "visitor",
                                true,
                                "encoding",
                                "ISO-8859-1",
                                "options",
                                Map.of("superClass", "com.acme.Base"),
                                "arguments",
                                List.of("-Werror", "-Xexact-output-dir"))),
                List.of(SHIM));

        assertThat(entry.inputs()).containsExactly("src/main/antlr/**/*.g4");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--src",
                        "${module.dir}/src/main/antlr",
                        "--lib",
                        "${module.dir}/src/main/antlr-lib",
                        "--package",
                        "com.acme.parser",
                        "--no-listener",
                        "--visitor",
                        "--encoding",
                        "ISO-8859-1",
                        "--arg",
                        "-DsuperClass=com.acme.Base",
                        "--arg",
                        "-Werror",
                        "--arg",
                        "-Xexact-output-dir",
                        "${inputs}");
    }

    @Test
    void the_worker_names_its_own_code_source() {
        assertThat(AntlrPreset.ownJar()).singleElement().satisfies(p -> assertThat(p)
                .exists());
    }
}
