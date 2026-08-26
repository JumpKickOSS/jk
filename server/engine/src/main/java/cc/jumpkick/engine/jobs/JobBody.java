// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.Session;
import java.io.BufferedWriter;

/**
 * Decode the request, run it, stream events to {@code writer} ({@code null} = detached job).
 * The returned {@link JobOutcome} is stamped on the accumulator by the envelope;
 * {@link JobOutcome.Declined} leaves the verdict to the accumulated facts.
 */
@FunctionalInterface
public interface JobBody {
    JobOutcome run(
            String requestLine,
            Session.CancelToken cancelToken,
            @org.jspecify.annotations.Nullable BufferedWriter writer);
}
