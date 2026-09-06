// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pull worker's exit code says whether its session ran to completion. Test outcomes travel
 * as events; a worker that exited non-zero is one the driver counts as dead with work owed.
 */
class TestRunnerPullModeTest {

    @Test
    void done_ends_the_session_with_exit_zero(@TempDir Path dir) throws Exception {
        List<EventType> events = new ArrayList<>();
        int exit = TestRunner.runPullMode(args(dir), recording(events), reader("DONE\n"));
        assertThat(exit).isZero();
        assertThat(events).containsExactly(EventType.READY);
    }

    @Test
    void input_that_ends_without_done_is_a_vanished_driver(@TempDir Path dir) throws Exception {
        int exit = TestRunner.runPullMode(args(dir), recording(new ArrayList<>()), reader(""));
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void unknown_commands_are_skipped_not_fatal(@TempDir Path dir) throws Exception {
        List<EventType> events = new ArrayList<>();
        int exit = TestRunner.runPullMode(args(dir), recording(events), reader("PING\nDONE\n"));
        assertThat(exit).isZero();
        assertThat(events).containsExactly(EventType.READY);
    }

    private static TestRunner.Args args(Path dir) {
        return TestRunner.Args.parse(new String[] {"--pull", "--worker=7", "--scan-classpath=" + dir});
    }

    private static BufferedReader reader(String stdin) {
        return new BufferedReader(new StringReader(stdin));
    }

    private static EventWriter recording(List<EventType> into) {
        return new EventWriter() {
            @Override
            public void write(EventType type, Map<String, Object> payload) {
                into.add(type);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }
}
