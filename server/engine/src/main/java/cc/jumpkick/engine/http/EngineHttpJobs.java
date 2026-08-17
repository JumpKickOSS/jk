// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.jobs.JobSpec;

/**
 * Async jobs started from the embedded HTTP / MCP surface. One admission point: the spec's kind
 * resolves to its {@code HostedVerb}, decodes to a wire request line, and submits FireAndForget.
 * Progress on SSE {@code GET /api/events}; cancel via {@link #cancel(long)}.
 */
public interface EngineHttpJobs {

    /**
     * Start a job (build / test / lock / …) and return its jid immediately.
     *
     * @throws IllegalArgumentException when the kind is not hosted or {@code dir} isn't runnable
     *     — relayed as a {@code 400}
     * @throws cc.jumpkick.engine.jobs.JobEnvelope.AlreadyRunning when a same-project job of this
     *     kind is in flight — relayed as a {@code 409}
     */
    long trigger(JobSpec spec);

    /**
     * Cooperative cancel + worker grace→force for a jid. Returns {@code false} if the id is
     * unknown or already finished.
     */
    boolean cancel(long jid);
}
