// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A released client learns between two passes of a long-lived command that jk updated itself: the
 * command stops and the entry point hands its arguments to the installed client, instead of every
 * later pass failing against an engine newer than itself.
 */
class ClientUpdateTest {

    @AfterEach
    void clear() {
        ClientUpdate.takeHandOff();
    }

    @Test
    void a_newer_install_stops_the_loop_and_asks_for_a_hand_off() {
        assertThat(ClientUpdate.updatedBetweenPasses(Optional.of("9.9.9"))).isTrue();
        assertThat(ClientUpdate.takeHandOff()).contains("9.9.9");
        assertThat(ClientUpdate.takeHandOff()).as("handed off once").isEmpty();
    }

    @Test
    void a_current_install_keeps_the_loop_going() {
        assertThat(ClientUpdate.updatedBetweenPasses(Optional.empty())).isFalse();
        assertThat(ClientUpdate.takeHandOff()).isEmpty();
    }

    @Test
    void only_a_released_client_older_than_the_install_is_superseded() {
        assertThat(ClientUpdate.newerInstall("1.0.0", true, () -> Optional.of("1.1.0")))
                .contains("1.1.0");
        assertThat(ClientUpdate.newerInstall("1.1.0", true, () -> Optional.of("1.1.0")))
                .isEmpty();
        assertThat(ClientUpdate.newerInstall("1.0.0", false, () -> Optional.of("1.1.0")))
                .as("a development client")
                .isEmpty();
        assertThat(ClientUpdate.newerInstall("1.0.0-SNAPSHOT", true, () -> Optional.of("1.1.0")))
                .isEmpty();
    }
}
