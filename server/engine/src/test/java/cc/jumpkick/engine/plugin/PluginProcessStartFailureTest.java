// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A worker whose fork fails after it started is stopped, untracked and leaves no argfile behind. */
class PluginProcessStartFailureTest {

    @Test
    void a_failure_after_the_start_stops_the_worker_and_deletes_its_argfile() throws Exception {
        // The quote in an argument makes Windows launch through an argfile; elsewhere there is none.
        List<String> command = List.of(
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                Sleeper.class.getName(),
                "a\"b");
        AtomicReference<Process> started = new AtomicReference<>();
        AtomicReference<Path> argfile = new AtomicReference<>();
        WorkerScope scope = new WorkerScope(-1);
        try {
            assertThatThrownBy(() -> scope.run(() -> PluginProcess.converseOnChannel(
                            command,
                            WorkerEnv.strict(),
                            (pb, heap) -> {
                                for (String arg : pb.command())
                                    if (arg.startsWith("@")) argfile.set(Path.of(arg.substring(1)));
                                Process worker = new StdinFails(pb.start());
                                started.set(worker);
                                JobWorkers.register(worker);
                                return worker;
                            },
                            convo -> {},
                            frame -> {},
                            null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("no stdin");

            assertThat(started.get().waitFor(10, TimeUnit.SECONDS))
                    .as("the worker was stopped")
                    .isTrue();
            if (argfile.get() != null)
                assertThat(Files.exists(argfile.get()))
                        .as("the argfile was deleted")
                        .isFalse();
            assertThat(scope.trackedCount()).as("the worker was unregistered").isZero();
        } finally {
            if (started.get() != null) started.get().destroyForcibly();
        }
    }

    /** A worker that runs until it is stopped. */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(60_000);
        }
    }

    /** {@code root}, whose stdin cannot be had: the step after the start throws. */
    private static final class StdinFails extends Process {
        private final Process root;

        StdinFails(Process root) {
            this.root = root;
        }

        @Override
        public OutputStream getOutputStream() {
            throw new IllegalStateException("no stdin");
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
        public Stream<ProcessHandle> descendants() {
            return root.descendants();
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
