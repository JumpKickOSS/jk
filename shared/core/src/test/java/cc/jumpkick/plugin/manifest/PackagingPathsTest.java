// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A {@code [packaging]} path names something under a directory jk owns (the packager step's
 * output, the module's {@code target/}, the app tree), so one that is absolute, empty or climbs
 * out with {@code ..} is refused when the manifest is read.
 */
class PackagingPathsTest {

    private static final List<String> REFUSED = List.of("/etc", "", ".", "..", "../x", "a/../../x", "./..");

    @ParameterizedTest
    @ValueSource(strings = {"native-image-sources", "app-dir", "app-jar"})
    void a_path_outside_its_root_is_refused(String key) {
        for (String bad : REFUSED) {
            assertThatThrownBy(() -> PluginDescriptors.parse(manifest(key + " = \"" + bad + "\""), "p.toml"))
                    .as("%s = \"%s\"", key, bad)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("p.toml.packaging." + key)
                    .hasMessageContaining("got: \"" + bad + "\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native-image-sources", "app-dir", "app-jar"})
    void a_variant_is_held_to_the_same_rule(String key) {
        String toml = manifest("") + """

                [[packaging.variant]]
                when = { config = "mode", equals = "x" }
                %s = "../out"
                """.formatted(key);
        assertThatThrownBy(() -> PluginDescriptors.parse(toml, "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("p.toml.packaging." + key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"native-image-sources", "app-dir", "app-jar"})
    void a_name_or_a_path_below_its_root_is_kept(String key) {
        for (String good : List.of("native-sources", "out/app", "./quarkus-run.jar")) {
            var packaging =
                    Objects.requireNonNull(PluginDescriptors.parse(manifest(key + " = \"" + good + "\""), "p.toml")
                            .packaging());
            String read =
                    switch (key) {
                        case "native-image-sources" -> packaging.nativeImageSources();
                        case "app-dir" -> packaging.appDir();
                        default -> packaging.appJar();
                    };
            assertThat(read).isEqualTo(good);
        }
    }

    private static String manifest(String line) {
        return """
                [plugin]
                id = "p"
                table = "p"
                version = "1.0.0"

                [packaging]
                packager = "p-jar"
                %s
                """.formatted(line);
    }
}
