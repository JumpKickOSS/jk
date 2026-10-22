// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The fast tier's JVMs carry {@link EnginePaths#NO_SPAWN_ENV}: asking for an engine where none serves fails at once. */
class EngineSpawnWallTest {

    @Test
    void a_fast_tier_jvm_cannot_start_an_engine(@TempDir Path state) {
        assumeTrue(
                System.getenv(EnginePaths.NO_SPAWN_ENV) != null, "runs in the fast tier, where the launcher sets it");
        long start = System.nanoTime();
        assertThatThrownBy(() -> EngineSpawn.ensure(EnginePaths.resolve(state), "0.0.0-test"))
                .isInstanceOf(EngineSpawnForbiddenException.class)
                .hasMessageContaining("@Tag(\"integration\")");
        assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
    }
}
