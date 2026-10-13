// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.system.EngineStopCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code jk engine stop} with no process holding this directory's election state is a clean
 * "not running". A silent holder's kill-or-refuse rule is covered by {@code EngineStopCommandTest}.
 */
class EngineStopNotRunningTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jks-");

    private String prevState;

    @BeforeEach
    void isolateState() throws Exception {
        prevState = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty(
                "jk.env.JK_STATE_DIR",
                Files.createDirectories(tempDirs.create()).toString());
    }

    @AfterEach
    void restoreState() {
        if (prevState == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevState);
    }

    @Test
    void stop_with_nothing_holding_the_state_is_still_a_clean_not_running() throws Exception {
        EnginePaths.Paths paths = EnginePaths.current();
        Files.createDirectories(paths.dir());

        assertThat(new EngineStopCommand().run(Invocation.builder().build())).isEqualTo(Exit.SUCCESS);
    }
}
