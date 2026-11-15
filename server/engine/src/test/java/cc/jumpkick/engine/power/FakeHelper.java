// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A helper process that holds until its stdin is closed or it is destroyed (or, with {@link
 * #ignoresStdin}, only until destroyed, as macOS {@code caffeinate} does), and the argvs it was
 * started with.
 */
final class FakeHelper implements ChildHold.Starter {

    final List<List<String>> started = new ArrayList<>();
    final List<Proc> processes = new ArrayList<>();

    /** Whether a started helper exits at once, as one that cannot take the hold does. */
    boolean refuse;

    /** Whether a started helper outlives its closed stdin and its timed waits really wait. */
    boolean ignoresStdin;

    @Override
    public Process apply(List<String> argv) {
        started.add(List.copyOf(argv));
        Proc p = new Proc(!refuse, ignoresStdin);
        processes.add(p);
        return p;
    }

    /** Helpers alive now. */
    long alive() {
        return processes.stream().filter(Process::isAlive).count();
    }

    static final class Proc extends Process {
        private volatile boolean alive;
        private final boolean ignoresStdin;

        Proc(boolean alive, boolean ignoresStdin) {
            this.alive = alive;
            this.ignoresStdin = ignoresStdin;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {}

                @Override
                public void close() {
                    if (!ignoresStdin) alive = false;
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (alive && ignoresStdin) Thread.sleep(unit.toMillis(timeout));
            return !alive;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }
    }
}
