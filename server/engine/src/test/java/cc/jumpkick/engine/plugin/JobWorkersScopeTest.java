// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.JkThreads;
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

    /** The engine keeps a job's scope by id while it is open, and a closed one ends with the job. */
    @Test
    void aClosedScopeIsForgottenAndEndedAndItsIdStartsCleanWhenReused() {
        long id = 7_331_007L;
        WorkerScopes scopes = new WorkerScopes();
        WorkerScope job = scopes.open(id);
        assertThat(scopes.get(id)).isSameAs(job);

        scopes.close(id);

        assertThat(scopes.get(id)).isNull();
        assertThat(job.ended()).isTrue();
        assertThat(scopes.open(id).ended()).as("a later use of the id").isFalse();
    }

    /** A fork registers to the scope bound where it starts, on whatever pool it runs. */
    @Test
    void aForkOnAPoolRegistersToTheScopeOfTheRequestThatSubmittedIt() throws Exception {
        WorkerScope scope = new WorkerScope(7_331_008L);
        Process held = new Held();
        scope.run(() -> JkThreads.io().submit(() -> JobWorkers.register(held)).get());
        assertThat(scope.trackedCount()).isEqualTo(1);
        scope.run(() -> JobWorkers.unregister(held));
        assertThat(scope.trackedCount()).isZero();
    }
}
