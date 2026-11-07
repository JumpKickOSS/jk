// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A client with no engine of its version names {@code jk snapshot}, never an install of a {@code jk
 * build} dist: that dist is release-versioned, and installed into the default home it hides every
 * later snapshot of the same version.
 */
class EngineSpawnNoEngineTest {

    @Test
    void the_missing_engine_advice_names_jk_snapshot_and_no_dist_install() {
        String advice = EngineSpawn.noEngine("1.2.3-SNAPSHOT");

        assertThat(advice).startsWith("no build engine for jk 1.2.3-SNAPSHOT");
        assertThat(advice).contains("`jk snapshot`", "`jk self materialize …`", "`jk self update`", "JK_ENGINE_EXE");
        assertThat(advice).doesNotContain("install.sh", "target/dist");
    }
}
