// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class PluginProcessPipeTest {

    @Test
    void detects_pipe_closed_messages() {
        assertThat(PluginProcess.isPipeClosed(new IOException("closed"))).isTrue();
        assertThat(PluginProcess.isPipeClosed(new IOException("Stream closed"))).isTrue();
        assertThat(PluginProcess.isPipeClosed(new IOException("Broken pipe"))).isTrue();
        assertThat(PluginProcess.isPipeClosed(new IOException("something else")))
                .isFalse();
    }
}
