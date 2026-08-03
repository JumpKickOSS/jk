// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildRecordSyntheticTest {

    @Test
    void syntheticTriggers() {
        assertThat(BuildRecord.running(1, "build", "/tmp/x", "g:a", 1L, "1", "optimize").synthetic())
                .isTrue();
        assertThat(BuildRecord.running(1, "build", "/tmp/x", "g:a", 1L, "1", "calibrate").synthetic())
                .isTrue();
        assertThat(BuildRecord.running(1, "build", "/tmp/x", "g:a", 1L, "1", "SYNTHETIC").synthetic())
                .isTrue();
        assertThat(BuildRecord.running(1, "build", "/tmp/x", "g:a", 1L, "1", "cli").synthetic())
                .isFalse();
        assertThat(BuildRecord.running(1, "build", "/tmp/x", "g:a", 1L, "1", "web").synthetic())
                .isFalse();
        assertThat(BuildRecord.running(1, "build", "/tmp/x", "g:a", 1L, "1", null).synthetic())
                .isFalse();
    }
}
