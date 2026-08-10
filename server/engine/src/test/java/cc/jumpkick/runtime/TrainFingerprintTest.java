// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TrainConfig;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1748: the train fingerprint must cover the profiles the run actually observed. A
 * {@code --profile smoke} run merges one profile's surface and must not satisfy the full-set
 * freshness check (`require-fresh`, and the up-to-date shortcut).
 */
class TrainFingerprintTest {

    @Test
    void filtered_profile_selection_changes_the_fingerprint(@TempDir Path tmp) throws Exception {
        Path toml = tmp.resolve("jk.toml");
        Files.writeString(toml, "[project]\nname = \"app\"\ngroup = \"g\"\nversion = \"1\"\n");
        JkBuild project = JkBuildParser.parse(toml);
        TrainConfig config = new TrainConfig(
                null,
                null,
                true,
                false,
                List.of(
                        new TrainConfig.Profile("smoke", Map.of(), Map.of(), List.of()),
                        new TrainConfig.Profile("full", Map.of(), Map.of(), List.of())));
        Path lock = tmp.resolve("jk-lock.toml");
        Path jar = tmp.resolve("app.jar");

        String all = TrainRunner.fingerprint(project, lock, jar, config, config.effectiveProfiles());
        String smokeOnly = TrainRunner.fingerprint(project, lock, jar, config, config.select("smoke"));

        assertThat(smokeOnly).isNotEqualTo(all);
        assertThat(TrainRunner.fingerprint(project, lock, jar, config, config.effectiveProfiles()))
                .isEqualTo(all);
    }
}
