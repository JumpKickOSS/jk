// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BspServerTest {

    @Test
    void extractTargetUris_finds_fragments() {
        String json =
                """
                {"params":{"targets":[{"uri":"file:///tmp/ws#api"},{"uri":"file:///tmp/ws#worker"}]}}
                """;
        assertThat(BspServer.extractTargetUris(json))
                .contains("file:///tmp/ws#api", "file:///tmp/ws#worker");
    }
}
