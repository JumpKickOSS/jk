// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The host's stdout pump reads the child's pipe, a native read that blocks the thread making it.
 * On a virtual thread that read holds a carrier for as long as the script is silent, and a test
 * fork with one carrier has no other: every virtual thread of the JVM waits behind the pump. The
 * pump is therefore a platform thread, and it still delivers everything the child wrote.
 */
class BuildLogicGroovyPumpTest {

    @Test
    void the_pump_drains_the_child_on_a_platform_thread() throws Exception {
        String java = System.getProperty("java.home") + "/bin/java";
        Process child =
                new ProcessBuilder(java, "-version").redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        Thread pump = BuildLogicGroovyHost.startPump(child, captured);

        assertThat(pump.isVirtual())
                .as("the pump holds no virtual-thread carrier")
                .isFalse();
        assertThat(pump.isDaemon()).as("the pump never keeps the engine alive").isTrue();
        assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
        pump.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("version");
    }
}
