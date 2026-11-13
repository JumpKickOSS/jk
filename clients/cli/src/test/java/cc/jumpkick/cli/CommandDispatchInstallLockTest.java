// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.InstallLock;
import cc.jumpkick.cli.engine.InstallLockHolderMain;
import cc.jumpkick.cli.engine.IsolatedState;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Two sessions sharing one install: a command that depends on the installed engine runs under the
 * install lock, so another session's install (a forked JVM taking the lock exclusively, as {@code jk
 * snapshot} does) waits for the run to finish, and a run started during an install waits for it.
 * Either way the run's steps see one build of the engine.
 */
@IsolatedState
class CommandDispatchInstallLockTest {

    /** A run that holds the install lock and blocks until {@link #finish} opens. */
    private static final class LongRun implements CliCommand {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);

        @Override
        public String name() {
            return "longrun";
        }

        @Override
        public String description() {
            return "stands in for a long test job";
        }

        @Override
        public boolean holdsInstall() {
            return true;
        }

        @Override
        public int run(Invocation in) throws Exception {
            started.countDown();
            return finish.await(120, TimeUnit.SECONDS) ? 0 : 1;
        }
    }

    @Test
    void an_install_in_another_session_waits_for_the_running_job() throws Exception {
        LongRun job = new LongRun();
        CompletableFuture<Integer> run =
                CompletableFuture.supplyAsync(() -> CommandDispatch.dispatch(job, "jk longrun", List.of(), false));
        assertThat(job.started.await(60, TimeUnit.SECONDS)).isTrue();
        try (var install = InstallLockHolderMain.start(InstallLock.lockFile(), "exclusive", "jk snapshot")) {
            assertThat(install.nextLine(Duration.ofSeconds(60))).startsWith("WAIT jk snapshot waits");
            assertThat(install.printsWithin(Duration.ofSeconds(1)))
                    .as("install ran under the job")
                    .isFalse();
            assertThat(InstallLock.holders())
                    .extracting(InstallLock.Holder::purpose)
                    .contains("jk longrun", "jk snapshot");

            job.finish.countDown();
            assertThat(run.get(60, TimeUnit.SECONDS)).isZero();
            install.awaitHeld();
            install.release();
        }
    }

    @Test
    void a_job_started_during_an_install_waits_for_it() throws Exception {
        try (var install = InstallLockHolderMain.start(InstallLock.lockFile(), "exclusive", "jk snapshot")) {
            install.awaitHeld();
            LongRun job = new LongRun();
            CompletableFuture<Integer> run =
                    CompletableFuture.supplyAsync(() -> CommandDispatch.dispatch(job, "jk longrun", List.of(), false));
            assertThat(job.started.await(1, TimeUnit.SECONDS))
                    .as("job started under the install")
                    .isFalse();
            install.release();
            assertThat(job.started.await(60, TimeUnit.SECONDS)).isTrue();
            job.finish.countDown();
            assertThat(run.get(60, TimeUnit.SECONDS)).isZero();
        }
    }
}
