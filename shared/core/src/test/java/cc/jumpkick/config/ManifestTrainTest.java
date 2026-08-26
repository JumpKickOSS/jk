// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestTrainTest {

    @TempDir
    Path dir;

    /**
     * Every case goes through {@link JkBuildParser#trainConfig(Path)} rather than a private
     * {@code [train]} reader: the point of the fold is that {@code [train]} is read by the manifest
     * owner, so these must break if the owner's document policy changes.
     */
    private TrainConfig train(String toml) throws IOException {
        Path file = dir.resolve("jk.toml");
        Files.writeString(file, toml);
        return JkBuildParser.trainConfig(file);
    }

    @Test
    void empty_when_no_train_table() throws IOException {
        assertThat(train("name = \"a\"\n")).isEqualTo(TrainConfig.EMPTY);
    }

    @Test
    void absent_manifest_is_empty() {
        assertThat(JkBuildParser.trainConfig(dir.resolve("nope.toml"))).isEqualTo(TrainConfig.EMPTY);
    }

    /**
     * The owner's {@link Interpolation} whitelist reaches {@code [train]}. Before the fold this
     * parser had its own {@code Toml.parse} and no guard at all, so {@code jk train} happily ran a
     * command the build itself refuses to read.
     */
    @Test
    void interpolation_outside_the_whitelist_is_rejected() {
        Path file = dir.resolve("jk.toml");
        assertThatThrownBy(() -> {
                    Files.writeString(file, """
                            [train]
                            command = "./smoke.sh ${DEPLOY_TOKEN}"
                            """);
                    JkBuildParser.trainConfig(file);
                })
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("train.command");
    }

    /** A manifest that exists and does not parse is an error, not "no [train] table". */
    @Test
    void malformed_manifest_is_not_silently_empty() {
        Path file = dir.resolve("jk.toml");
        assertThatThrownBy(() -> {
                    Files.writeString(file, "[train\n");
                    JkBuildParser.trainConfig(file);
                })
                .isInstanceOf(JkBuildParseException.class);
    }

    @Test
    void parses_profiles_and_flags() throws IOException {
        String toml = """
                [train]
                command = "./smoke.sh"
                commit-to = "src/train/metadata"
                require-fresh = true
                aot-cache = true

                [[train.profile]]
                name = "default"

                [[train.profile]]
                name = "prod"
                properties = { "env" = "prod" }
                env = { "X" = "1" }
                args = ["--flag"]
                """;
        TrainConfig c = train(toml);
        assertThat(c.hasCommand()).isTrue();
        assertThat(c.command()).isEqualTo("./smoke.sh");
        assertThat(c.requireFresh()).isTrue();
        assertThat(c.aotCache()).isTrue();
        assertThat(c.commitTo()).isEqualTo("src/train/metadata");
        assertThat(c.profiles()).hasSize(2);
        assertThat(c.select("prod")).hasSize(1);
        assertThat(c.select("prod").getFirst().properties()).containsEntry("env", "prod");
        assertThat(c.select("prod").getFirst().env()).containsEntry("X", "1");
        assertThat(c.select("prod").getFirst().args()).containsExactly("--flag");
    }

    @Test
    void select_unknown_profile_fails() throws IOException {
        String toml = """
                [train]
                [[train.profile]]
                name = "a"
                """;
        TrainConfig c = train(toml);
        assertThatThrownBy(() -> c.select("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effective_profiles_default_when_none_declared() throws IOException {
        String toml = """
                [train]
                aot-cache = false
                """;
        TrainConfig c = train(toml);
        assertThat(c.effectiveProfiles()).hasSize(1);
        assertThat(c.effectiveProfiles().getFirst().name()).isEqualTo("default");
    }

    @Test
    void profiles_token_is_order_independent_for_env_and_properties() {
        var a = new TrainConfig(
                null,
                null,
                false,
                false,
                List.of(new TrainConfig.Profile(
                        "p",
                        new LinkedHashMap<>(Map.of("B", "2", "A", "1")),
                        new LinkedHashMap<>(Map.of("y", "2", "x", "1")),
                        List.of())));
        var b = new TrainConfig(
                null,
                null,
                false,
                false,
                List.of(new TrainConfig.Profile(
                        "p",
                        new LinkedHashMap<>(Map.of("A", "1", "B", "2")),
                        new LinkedHashMap<>(Map.of("x", "1", "y", "2")),
                        List.of())));
        org.assertj.core.api.Assertions.assertThat(a.profilesToken()).isEqualTo(b.profilesToken());
    }
}
