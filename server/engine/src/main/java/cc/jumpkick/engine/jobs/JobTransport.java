// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import org.jspecify.annotations.Nullable;

/**
 * How the client observes a job. The only CLI-vs-HTTP difference: the connection owns the job,
 * or the request id is returned immediately.
 */
public sealed interface JobTransport {

    /** CLI: connection owns the job; watch BUILD_CANCEL / EOF; join before return. */
    record SocketWatch(BufferedReader reader, BufferedWriter writer) implements JobTransport {}

    /** HTTP/MCP: return request id immediately; progress is the sink (SSE). JK-1928. */
    record FireAndForget() implements JobTransport {}

    static @Nullable BufferedWriter writerOf(JobTransport t) {
        return t instanceof SocketWatch w ? w.writer() : null;
    }
}
