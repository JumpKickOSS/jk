// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** A worker's fork leaves no live descendant behind once it ends. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class PluginProcessStragglerTest {

    private static final List<String> EXIT_AT_ONCE = List.of("/bin/sh", "-c", "exit 0");

    /**
     * A worker whose root has exited while a descendant it started still runs, as Windows reports a
     * dead root's children: the straggler is stopped with it.
     */
    @Tag("slow")
    @Test
    void a_descendant_that_outlives_the_worker_is_stopped() throws Exception {
        Process straggler = new ProcessBuilder("sleep", "60").start();
        try {
            int exit = PluginProcess.converseOnChannel(
                            EXIT_AT_ONCE,
                            WorkerEnv.strict(),
                            (pb, heap) -> new LeavesADescendant(pb.start(), straggler.toHandle()),
                            convo -> {},
                            frame -> {},
                            null)
                    .code();
            assertThat(exit).isZero();
            assertThat(straggler.waitFor(5, TimeUnit.SECONDS))
                    .as("the worker's straggler was stopped")
                    .isTrue();
        } finally {
            straggler.destroyForcibly();
        }
    }

    /** {@code root}, reporting {@code descendant} as its child for as long as it lives. */
    private static final class LeavesADescendant extends Process {
        private final Process root;
        private final ProcessHandle descendant;

        LeavesADescendant(Process root, ProcessHandle descendant) {
            this.root = root;
            this.descendant = descendant;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return descendant.isAlive() ? Stream.of(descendant) : Stream.empty();
        }

        @Override
        public OutputStream getOutputStream() {
            return root.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return root.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return root.getErrorStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return root.waitFor();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return root.waitFor(timeout, unit);
        }

        @Override
        public int exitValue() {
            return root.exitValue();
        }

        @Override
        public boolean isAlive() {
            return root.isAlive();
        }

        @Override
        public long pid() {
            return root.pid();
        }

        @Override
        public ProcessHandle toHandle() {
            return root.toHandle();
        }

        @Override
        public void destroy() {
            root.destroy();
        }

        @Override
        public Process destroyForcibly() {
            root.destroyForcibly();
            return this;
        }
    }
}
