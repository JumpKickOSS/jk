// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.Session;
import java.io.BufferedWriter;

/**
 * Decode the request, run it, stream events to {@code writer} ({@code null} = detached job).
 * The returned {@link JobOutcome} is stamped on the accumulator by the envelope; {@code null}
 * leaves the outcome to the accumulated facts.
 */
@FunctionalInterface
public interface JobBody {
    @org.jspecify.annotations.Nullable
    JobOutcome run(
            String requestLine,
            Session.CancelToken cancelToken,
            @org.jspecify.annotations.Nullable BufferedWriter writer);
}
