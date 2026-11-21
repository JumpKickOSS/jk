// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The orphan arm of the watchdog against a real election whose endpoint pointer is gone. */
class DisplacementWatchdogTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkw-");

    /** An exit the engine refuses under its lock (a plan claimed after the look) keeps the watch going. */
    @Test
    void an_orphan_exit_the_engine_refuses_keeps_watching() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(tempDirs.create());
        EngineElection election = new EngineElection(p, "9.9.9-test", "aaaa", 4242, s -> {});
        requireNonNull(election.win()).listener().close();
        Files.delete(EnginePaths.endpoint(p));
        AtomicInteger exits = new AtomicInteger();
        AtomicBoolean refuse = new AtomicBoolean(true);
        DisplacementWatchdog watchdog = new DisplacementWatchdog(
                election,
                () -> 0,
                () -> 0,
                () -> false,
                exitNow -> {},
                () -> {
                    exits.incrementAndGet();
                    return !refuse.get();
                },
                s -> {});
        try {
            assertThat(watchdog.tick()).isFalse();
            refuse.set(false);
            assertThat(watchdog.tick()).isTrue();
            assertThat(exits).hasValue(2);
        } finally {
            election.retire();
        }
    }
}
