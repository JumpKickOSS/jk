// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.giter8.TemplateLint;
import cc.jumpkick.host.Errors;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.TemplateLintAck;
import cc.jumpkick.wire.protocol.TemplateLintRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** {@code jk template lint}: the catalogue loader's lint over the template directories the CLI names. */
public final class TemplateLintVerb implements HostedVerb {

    private final VerbHost host;

    public TemplateLintVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.TEMPLATE_LINT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("template-lint");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-template-lint-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            TemplateLintAck ack;
            try {
                ack = answer(TemplateLintRequest.decode(requestLine));
            } catch (Exception e) {
                ack = TemplateLintAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    static TemplateLintAck answer(TemplateLintRequest req) {
        String dir = req.dir();
        if (dir == null || dir.isBlank()) throw new IllegalArgumentException("template-lint request names no dir");
        TemplateLint.Result result = TemplateLint.run(Path.of(dir), req.paths());
        return new TemplateLintAck(null, result.checked(), result.failures());
    }
}
