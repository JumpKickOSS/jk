// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import java.io.BufferedWriter;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The only code that writes a line to a client's JSONL socket.
 *
 * <p>One connection's writer is shared by several producers: the plan's worker threads, the
 * envelope's heartbeat watchdog, the cancel/terminal path, and the job-finish tail. A line is only
 * atomic on the wire if every one of them holds the same monitor while writing it, so this class
 * owns that monitor and nothing else takes it.
 *
 * <p>It exists because that discipline was previously restated in three places and one of them
 * disagreed: {@code EngineServer.send} and {@code WireEventSink.emit} both synchronized (each with a
 * comment explaining why), while {@code JobEnvelope.send} did not — and the envelope's copy was the
 * one the 30-second heartbeat used, so a long build could splice a heartbeat into the middle of a
 * plan event and hand the client a malformed line. Three implementations of one invariant is two
 * too many; a fourth would have arrived with the next producer.
 */
@NullMarked
public final class WireWriter {

    private WireWriter() {}

    /** Write one line, atomically with respect to every other producer on this writer. */
    public static void send(BufferedWriter writer, String line) throws IOException {
        synchronized (writer) {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }
    }

    /**
     * As {@link #send}, but a write failure is dropped: it means the client is gone, and the
     * cancel-watching read loop notices the same disconnect. A null writer is a detached job
     * (HTTP/MCP) with no wire at all.
     */
    public static void sendQuiet(@Nullable BufferedWriter writer, String line) {
        if (writer == null) return;
        try {
            send(writer, line);
        } catch (IOException ignored) {
            // client gone; the read loop sees it too
        }
    }
}
