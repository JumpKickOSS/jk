// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [image]} is read by the manifest owner. Every case here goes through
 * {@link JkBuildParser#imageConfig(Path)} rather than a private {@code [image]} parser, so the
 * owner's document policy — the syntax-error message and {@link Interpolation}'s whitelist — decides
 * what these cases.
 */
class ManifestImageTest {

    @TempDir
    Path dir;

    private ManifestImage.ImageConfigData image(String toml) throws IOException {
        Path file = dir.resolve("jk.toml");
        Files.writeString(file, toml);
        return JkBuildParser.imageConfig(file);
    }

    @Test
    void reads_the_table() throws IOException {
        var data = image("""
                name = "demo"

                [image]
                base = "eclipse-temurin:{java-major-version}-jre"
                name = "acme-demo"
                registry = "ghcr.io/acme"
                ports = [8080, 9990]
                platforms = ["linux/amd64"]
                aot-cache = true
                env = { LANG = "C.UTF-8" }
                """);
        assertThat(data.base()).isEqualTo("eclipse-temurin:{java-major-version}-jre");
        assertThat(data.name()).isEqualTo("acme-demo");
        assertThat(data.registry()).isEqualTo("ghcr.io/acme");
        assertThat(data.ports()).containsExactly(8080, 9990);
        assertThat(data.platforms()).containsExactly("linux/amd64");
        assertThat(data.aotCache()).isTrue();
        assertThat(data.env()).containsEntry("LANG", "C.UTF-8");
    }

    @Test
    void absent_table_and_absent_file_are_both_empty() throws IOException {
        assertThat(image("name = \"demo\"\n")).isEqualTo(ManifestImage.ImageConfigData.EMPTY);
        assertThat(JkBuildParser.imageConfig(dir.resolve("nope.toml"))).isEqualTo(ManifestImage.ImageConfigData.EMPTY);
    }

    /**
     * The owner's interpolation whitelist reaches {@code [image]}. It has to: {@code image.registry}
     * is where a built image is pushed, and an environment-dependent one means the same commit
     * publishes to different registries on different machines. The private parser had no guard.
     */
    @Test
    void interpolation_outside_the_whitelist_is_rejected() {
        assertThatThrownBy(() -> image("""
                        [image]
                        registry = "${MY_REGISTRY}"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("image.registry");
    }

    /** A manifest that exists and does not parse is an error, not "no [image] table". */
    @Test
    void malformed_manifest_is_not_silently_empty() {
        assertThatThrownBy(() -> image("[image\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("failed to parse jk.toml");
    }

    @Test
    void project_layer_wins_over_global_field_by_field() {
        var project = new ManifestImage.ImageConfigData(
                "project-base",
                null,
                null,
                List.of(),
                Map.of("A", "project"),
                Map.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null);
        var global = new ManifestImage.ImageConfigData(
                "global-base",
                "global-name",
                "nobody",
                List.of(8080),
                Map.of("A", "global", "B", "global"),
                Map.of(),
                "ghcr.io/acme",
                null,
                List.of(),
                null,
                null,
                null,
                true);
        var merged = ManifestImage.merge(project, global);
        assertThat(merged.base()).isEqualTo("project-base");
        assertThat(merged.name())
                .as("an unset project name falls back to the global one")
                .isEqualTo("global-name");
        assertThat(merged.user()).isEqualTo("nobody");
        assertThat(merged.registry()).isEqualTo("ghcr.io/acme");
        assertThat(merged.ports()).containsExactly(8080);
        assertThat(merged.env()).containsEntry("A", "project").containsEntry("B", "global");
        assertThat(merged.aotCache()).isTrue();
    }
}
