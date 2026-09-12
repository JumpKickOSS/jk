// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code stream} retries a stale connection, never the ensure: an engine that cannot start already
 * spends two spawn attempts behind a 30 s ceiling each before ensure gives up, and a refusal is an
 * answer. Doubling either only doubles how long the user waits to be told.
 */
class EngineWireEnsureOnceTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkw-");

    private @Nullable String forcedTransport;

    @BeforeEach
    void isolate() {
        EngineWire.forgetEnsured();
        forcedTransport = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
    }

    @AfterEach
    void restore() {
        EngineWire.forgetEnsured();
        if (forcedTransport == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
        else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, forcedTransport);
    }

    @Test
    void an_ensure_that_fails_is_attempted_once_and_its_reason_is_the_error() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(paths, "{}", (reader, ch) -> "unreached", (p, v) -> {
                    ensures.incrementAndGet();
                    throw new IOException("could not start the build engine");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("could not start the build engine");

        assertThat(ensures).hasValue(1);
    }

    @Test
    void a_refusal_from_ensure_is_not_retried_either() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(paths, "{}", (reader, ch) -> "unreached", (p, v) -> {
                    ensures.incrementAndGet();
                    throw new IOException("the build engine is shutting down");
                }))
                .hasMessage("the build engine is shutting down");

        assertThat(ensures).hasValue(1);
    }

    @Test
    void a_dead_remembered_endpoint_is_forgotten_and_ensured_once_more() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        EngineWire.rememberEnsured(paths, EnginePaths.activeSocket(paths)); // nothing listens there
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(paths, "{}", (reader, ch) -> "unreached", (p, v) -> {
                    ensures.incrementAndGet();
                    throw new IOException("could not start the build engine");
                }))
                .hasMessage("could not start the build engine");

        assertThat(ensures).as("the stale memo costs exactly one re-ensure").hasValue(1);
    }

    @Test
    void an_engine_that_ensures_but_never_listens_is_ensured_at_most_twice() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        AtomicInteger ensures = new AtomicInteger();

        assertThatThrownBy(() -> EngineWire.stream(
                        paths, "{}", (reader, ch) -> "unreached", (p, v) -> ensures.incrementAndGet()))
                .isInstanceOf(IOException.class);

        assertThat(ensures)
                .as("the first ensure, then one more after the connect failed")
                .hasValue(2);
    }
}
