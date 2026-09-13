// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the listener is registered and ran before this test: the property must already name
 * this JVM in every test JVM — a registration slip would otherwise show up only as
 * engines left behind after a crashed worker.
 */
class EngineOwnerListenerTest {

    @Test
    void this_test_jvm_names_itself_as_engine_owner_before_any_test_runs() {
        assertThat(System.getProperty(EngineSpawn.OWNER_PID_PROPERTY))
                .isEqualTo(Long.toString(ProcessHandle.current().pid()));
    }
}
