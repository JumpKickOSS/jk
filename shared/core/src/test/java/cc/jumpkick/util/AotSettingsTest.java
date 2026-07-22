// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AotSettingsTest {

    @AfterEach
    void clear() {
        System.clearProperty("jk.aot.train");
        System.clearProperty("jk.worker.aot");
    }

    @Test
    void training_defaults_on_and_honors_off() {
        System.clearProperty("jk.aot.train");
        assertThat(AotSettings.trainingEnabled()).isTrue();
        System.setProperty("jk.aot.train", "off");
        assertThat(AotSettings.trainingEnabled()).isFalse();
        System.setProperty("jk.aot.train", "false");
        assertThat(AotSettings.trainingEnabled()).isFalse();
        System.setProperty("jk.aot.train", "on");
        assertThat(AotSettings.trainingEnabled()).isTrue();
    }

    @Test
    void worker_aot_defaults_on_and_honors_off() {
        System.clearProperty("jk.worker.aot");
        assertThat(AotSettings.workerAotEnabled()).isTrue();
        System.setProperty("jk.worker.aot", "off");
        assertThat(AotSettings.workerAotEnabled()).isFalse();
    }
}
