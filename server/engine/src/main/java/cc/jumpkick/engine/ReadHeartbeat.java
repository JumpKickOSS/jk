// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The heartbeat of one read served inline on its connection: a {@code heartbeat} line every
 * interval until {@link #stop}, so the client's stream-idle bound hears a busy engine. A job's
 * heartbeat comes from its watchdog; a read has none. Once {@link #stop} returns no further line is
 * sent; one may still land between the read's last line and the stop, which the client's reader
 * drops as it drops every heartbeat.
 */
final class ReadHeartbeat {

    private static final ReadHeartbeat NONE = new ReadHeartbeat();

    private final CountDownLatch stopped = new CountDownLatch(1);

    private ReadHeartbeat() {}

    /** Beats on {@code writer} every {@code everyMillis}; {@code 0} or no writer sends nothing. */
    static ReadHeartbeat start(@Nullable BufferedWriter writer, long everyMillis) {
        if (writer == null || everyMillis <= 0) return NONE;
        ReadHeartbeat heartbeat = new ReadHeartbeat();
        long startNanos = Clock.SYSTEM.nanos();
        JkThreads.platform().execute(() -> heartbeat.beat(writer, everyMillis, startNanos));
        return heartbeat;
    }

    private void beat(BufferedWriter writer, long everyMillis, long startNanos) {
        try {
            while (!stopped.await(everyMillis, TimeUnit.MILLISECONDS)) {
                synchronized (this) {
                    if (stopped.getCount() == 0) return;
                    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(Clock.SYSTEM.nanos() - startNanos);
                    WireWriter.sendNoWait(writer, ProtoLifecycle.heartbeat(elapsedMillis));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ends the heartbeat; a beat already being sent lands before this returns. */
    synchronized void stop() {
        stopped.countDown();
    }
}
