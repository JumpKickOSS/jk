// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;

class JobWorkersScopeTest {

    /** A process that is never started: register and unregister only hold it. */
    private static final class Held extends Process {
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}
    }

    /**
     * A scope whose last process exits is forgotten: a compiler worker's scope is never shut down
     * when it retires, and an engine that kept every such scope's empty set would grow without end.
     */
    @Test
    void aScopeWhoseLastProcessExitsIsForgotten() {
        long scope = 7_331_007L;
        Process first = new Held();
        Process second = new Held();
        JobWorkers.open(scope);
        try {
            JobWorkers.register(first);
            JobWorkers.register(second);
            JobWorkers.unregister(first);
            assertThat(JobWorkers.tracksScope(scope)).isTrue();
            assertThat(JobWorkers.trackedCount(scope)).isEqualTo(1);
            JobWorkers.unregister(second);
            assertThat(JobWorkers.tracksScope(scope)).isFalse();
        } finally {
            JobWorkers.close();
            JobWorkers.clear(scope);
        }
    }
}
