// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobBody;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobRequest;
import java.io.BufferedWriter;

/**
 * One hosted command. Not sealed — the set grows; tests need fakes. Not {@code ServiceLoader}.
 */
public interface HostedVerb {

    String wireType();

    JobKind jobKind();

    VerbShape shape();

    String threadPrefix();

    default VerbRequest decode(VerbInput in) {
        return new VerbRequest(wireType(), jobKind().verb(), in.requestLine());
    }

    void run(String requestLine, Session.CancelToken cancel, BufferedWriter writer);

    default JobRequest toJobRequest() {
        JobBody body = this::run;
        return new JobRequest(jobKind(), threadPrefix(), body);
    }
}
