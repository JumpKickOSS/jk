// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.ProvisionRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** {@code jk tool install <tool>:<version>} on the wire: the named distribution and the consent it may carry. */
class EngineHostedToolRequestTest {

    @Test
    void a_tool_install_request_carries_the_accept_unverified_consent() {
        ProvisionRequest accepted =
                ProvisionRequest.decode(EngineHosted.toolRequest("maven", "3.6.3", Path.of("/store/tools"), true, true)
                        .encode());
        assertThat(accepted.tool()).isEqualTo("maven");
        assertThat(accepted.version()).isEqualTo("3.6.3");
        assertThat(accepted.toolsRoot()).isEqualTo("/store/tools");
        assertThat(accepted.noDiscover()).isTrue();
        assertThat(accepted.acceptUnverified()).isTrue();
        assertThat(accepted.gradle()).isFalse();

        ProvisionRequest plain = ProvisionRequest.decode(
                EngineHosted.toolRequest("kotlin", "latest", Path.of("/store/tools"), false, false)
                        .encode());
        assertThat(plain.acceptUnverified()).isFalse();
    }
}
