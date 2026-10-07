// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** When the engine does not come up, the client repeats the engine's own reason from its log. */
class EngineSpawnStartFailureTest {

    @Test
    void the_last_reason_the_engine_logged_is_named(@TempDir Path tmp) throws Exception {
        Path log = Files.writeString(tmp.resolve("k.log"), """
                21:18:16.833 INFO jk engine: worker containment cgroup
                21:18:17.148 ERROR jk engine: failed to start: Address already in use
                21:18:17.549 ERROR jk engine: failed to start: Unix domain path too long
                """);
        assertThat(EngineSpawn.lastStartFailure(log)).isEqualTo("Unix domain path too long");
    }

    @Test
    void a_log_without_a_reason_or_no_log_names_none(@TempDir Path tmp) throws Exception {
        Path log = Files.writeString(tmp.resolve("k.log"), "21:18:16.833 INFO jk engine: listening\n");
        assertThat(EngineSpawn.lastStartFailure(log)).isNull();
        assertThat(EngineSpawn.lastStartFailure(tmp.resolve("absent.log"))).isNull();
    }
}
