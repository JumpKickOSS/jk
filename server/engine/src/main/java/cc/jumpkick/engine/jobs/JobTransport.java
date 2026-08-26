// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.channels.SocketChannel;
import org.jspecify.annotations.Nullable;

/**
 * How the client observes a job. The only CLI-vs-HTTP difference: the connection owns the job,
 * or the request id is returned immediately.
 */
public sealed interface JobTransport {

    /**
     * CLI: connection owns the job; watch BUILD_CANCEL / EOF; join before return.
     *
     * <p>{@code channel} is the same socket {@code reader}/{@code writer} sit on, carried so the
     * job can wake the connection thread off client-readLine by half-closing the read direction —
     * the blunt alternative, {@link Thread#interrupt}, closes the whole channel and costs the
     * client its end-of-job line. May be {@code null} in tests.
     */
    record SocketWatch(
            BufferedReader reader,
            BufferedWriter writer,
            @Nullable SocketChannel channel) implements JobTransport {
        public SocketWatch(BufferedReader reader, BufferedWriter writer) {
            this(reader, writer, null);
        }
    }

    /** HTTP/MCP: return request id immediately; progress is the sink (SSE). */
    record FireAndForget() implements JobTransport {}
}
