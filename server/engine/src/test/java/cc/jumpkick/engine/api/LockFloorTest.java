// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lock's {@code jk-min} is a floor, never a pin: a newer jk always runs; an older jk
 * refuses with the upgrade error.
 */
class LockFloorTest {

    @Test
    void newer_jk_always_runs_and_older_jk_is_refused(@TempDir Path dir) throws IOException {
        writeLock(dir, "jk-min = \"0.12.0\"");
        // Newer (and equal) jk: the floor is satisfied — never spawn or fetch an older engine.
        assertThat(LockFloor.requiredNewer(dir, "0.15.0")).isNull();
        assertThat(LockFloor.requiredNewer(dir, "0.12.0")).isNull();
        assertThatCode(() -> LockFloor.refuseIfBelow(dir, EngineProtocol.BUILD_REQUEST, "0.15.0"))
                .doesNotThrowAnyException();
        // Older jk: typed upgrade refusal.
        assertThat(LockFloor.requiredNewer(dir, "0.11.0")).isEqualTo("0.12.0");
        assertThatThrownBy(() -> LockFloor.refuseIfBelow(dir, EngineProtocol.BUILD_REQUEST, "0.11.0"))
                .isInstanceOf(LockFloor.LockFloorRefused.class)
                .hasMessageContaining("0.12.0")
                .hasMessageContaining("self update");
    }

    @Test
    void reads_and_unguarded_kinds_pass_regardless(@TempDir Path dir) throws IOException {
        writeLock(dir, "jk-min = \"9.9.9\"");
        // lock/tree/why stay served — re-locking with a current jk is how a checkout moves on.
        assertThatCode(() -> LockFloor.refuseIfBelow(dir, EngineProtocol.LOCK_REQUEST, "0.12.0"))
                .doesNotThrowAnyException();
        assertThatCode(() -> LockFloor.refuseIfBelow(dir, EngineProtocol.TREE_REQUEST, "0.12.0"))
                .doesNotThrowAnyException();
    }

    @Test
    void no_lock_or_no_floor_means_no_refusal(@TempDir Path dir) throws IOException {
        assertThat(LockFloor.requiredNewer(dir, "0.12.0")).isNull(); // no lockfile at all
        writeLock(dir, "");
        assertThat(LockFloor.requiredNewer(dir, "0.12.0")).isNull(); // lock without a floor
    }

    private static void writeLock(Path dir, String jkLine) throws IOException {
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                %s
                """.formatted(jkLine));
        LockfileReader.clearCache();
    }
}
