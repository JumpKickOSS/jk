// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The client that sent a job, asked about its shell while the job runs: what {@link CallerEnv}
 * falls back on for a variable the request did not carry. Bound on the job's request under {@link
 * #SLOT} when the client said it answers; a build with no client attached (the web UI, MCP, a
 * detached run) has none.
 *
 * <p>Every method answers {@code null} when the client cannot: it went away, or did not answer in
 * time. An implementation keeps what it was told for the job's life and no longer, and logs no
 * value.
 */
public interface CallerAsker {

    /** Where a job's request carries its asker. */
    Request.Slot<CallerAsker> SLOT = new Request.Slot<>("caller-asker");

    /** The values of those of {@code names} the client's shell sets; a name it lacks is absent from the map. */
    @Nullable
    Map<String, String> values(Collection<String> names);

    /** The names the client's shell sets. */
    @Nullable
    Set<String> names();

    /** Every variable the client's shell sets, with its value. */
    @Nullable
    Map<String, String> all();

    /** Whether a question went unanswered: the client is gone, so what was not asked before cannot be known. */
    boolean lost();
}
