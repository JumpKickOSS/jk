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

    /**
     * CLI socket: the connection's reader, watched for the client's EOF while the job runs, and the
     * writer its events and terminal go to.
     */
    record SocketWatch(BufferedReader reader, BufferedWriter writer) implements JobTransport {}

    /**
     * HTTP/MCP: return request id immediately; progress is the sink (SSE). {@code deadlineMs} is the
     * wall deadline the submission asked for — {@code 0} for none — or {@code null} to run under the
     * engine's detached default. No connection ends a detached job, so the deadline is its only bound.
     */
    record FireAndForget(@Nullable Long deadlineMs) implements JobTransport {
        /** Under the engine's detached default. */
        public FireAndForget() {
            this(null);
        }

        public FireAndForget {
            if (deadlineMs != null && deadlineMs < 0) {
                throw new IllegalArgumentException("deadlineMs must be >= 0 (0 = no deadline)");
            }
        }
    }
}
