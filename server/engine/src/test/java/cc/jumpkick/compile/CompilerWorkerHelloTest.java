// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginProcess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompilerWorkerHelloTest {

    /** A conversation whose worker connects only on the {@code connectsOn}-th send. */
    private static final class LateWorker implements PluginProcess.Conversation {
        final List<String> sent = new ArrayList<>();
        final int connectsOn;
        final boolean closes;
        int attempts;

        LateWorker(int connectsOn, boolean closes) {
            this.connectsOn = connectsOn;
            this.closes = closes;
        }

        @Override
        public void send(String line) {
            attempts++;
            if (closes) throw new UncheckedIOException(new IOException("closed"));
            if (attempts < connectsOn)
                throw new UncheckedIOException(new SocketTimeoutException("the worker never connected"));
            sent.add(line);
        }

        @Override
        public void closeInput() {}
    }

    /** A worker slow enough to miss one connect wait still gets its hello, and so becomes ready. */
    @Test
    void aWorkerThatConnectsAfterOneWaitStillGetsItsHello() {
        LateWorker worker = new LateWorker(3, false);
        CompilerWorker.sendWhenConnected(worker, "hello", () -> false);
        assertThat(worker.sent).containsExactly("hello");
    }

    /** A dead worker or a closed channel ends the wait. */
    @Test
    void aDeadWorkerOrAClosedChannelStopsTheWait() {
        LateWorker never = new LateWorker(Integer.MAX_VALUE, false);
        CompilerWorker.sendWhenConnected(never, "hello", () -> never.attempts >= 2);
        assertThat(never.attempts).isEqualTo(2);
        LateWorker closed = new LateWorker(1, true);
        CompilerWorker.sendWhenConnected(closed, "hello", () -> false);
        assertThat(closed.attempts).isEqualTo(1);
    }
}
