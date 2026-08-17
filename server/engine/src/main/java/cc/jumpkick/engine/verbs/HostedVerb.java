// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobBody;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.engine.jobs.JobSpec;
import java.io.BufferedWriter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One hosted command. Not sealed — the set grows; tests need fakes. Not {@code ServiceLoader}.
 *
 * <p>Every surface reaches the same verb: the CLI wire dispatches by {@link #wireType()}; HTTP and
 * MCP job submissions resolve a {@link JobSpec} kind via {@link #jobKinds()} and decode it into a
 * wire request line with {@link #decodeJob} — exposing a verb to the dashboard/agents is
 * implementing that one method, never a second body.
 */
public interface HostedVerb {

    String wireType();

    JobKind jobKind();

    VerbShape shape();

    String threadPrefix();

    default VerbRequest decode(VerbInput in) {
        return new VerbRequest(wireType(), jobKind().verb(), in.requestLine());
    }

    /** HTTP/MCP job kinds this verb serves ({@code build}, {@code assemble}, …); empty = not exposed. */
    default List<String> jobKinds() {
        return List.of();
    }

    /**
     * Decode an HTTP/MCP job into this verb's wire request line ({@code Proto*} builders +
     * {@code ProtoSession.withTrigger}). Only called for kinds in {@link #jobKinds()}.
     *
     * @throws IllegalArgumentException when the spec cannot run (bad modules, missing toolchain …)
     */
    default String decodeJob(JobSpec spec) {
        throw new IllegalArgumentException("kind not hosted: " + spec.kind());
    }

    /**
     * {@code writer} is {@code null} for a detached (HTTP/MCP) job — sinks and hooks still run.
     * A job verb returns its {@link cc.jumpkick.engine.jobs.JobOutcome}; the envelope stamps it
     * (the one success law). Sync reads return {@code null}.
     */
    @Nullable
    JobOutcome run(String requestLine, Session.CancelToken cancel, @Nullable BufferedWriter writer);

    /** The submission for one decoded request; the line may refine the kind (workspace test). */
    default JobRequest toJobRequest(String requestLine) {
        JobBody body = this::run;
        return new JobRequest(jobKind(), threadPrefix(), body);
    }
}
