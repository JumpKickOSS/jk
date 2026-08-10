// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TrainConfigParserTest {

    @Test
    void empty_when_no_train_table() {
        assertThat(TrainConfigParser.parse("project = {}\n", "t.toml")).isEqualTo(TrainConfig.EMPTY);
    }

    @Test
    void parses_profiles_and_flags() {
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
        TrainConfig c = TrainConfigParser.parse(toml, "t.toml");
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
    void select_unknown_profile_fails() {
        String toml = """
                [train]
                [[train.profile]]
                name = "a"
                """;
        TrainConfig c = TrainConfigParser.parse(toml, "t.toml");
        assertThatThrownBy(() -> c.select("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effective_profiles_default_when_none_declared() {
        String toml = """
                [train]
                aot-cache = false
                """;
        TrainConfig c = TrainConfigParser.parse(toml, "t.toml");
        assertThat(c.effectiveProfiles()).hasSize(1);
        assertThat(c.effectiveProfiles().getFirst().name()).isEqualTo("default");
    }
}
