// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.guard.eval.Freezer;
import cc.jumpkick.host.Errors;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.GuardFreezeAck;
import cc.jumpkick.wire.protocol.GuardFreezeRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@code jk guard freeze}: the one privileged baseline write, engine-side like every baseline write. */
public final class GuardFreezeVerb implements HostedVerb {

    private final VerbHost host;

    public GuardFreezeVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GUARD_FREEZE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("guard-freeze");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-guard-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            GuardFreezeAck ack;
            try {
                GuardFreezeRequest req = GuardFreezeRequest.decode(requestLine);
                Path dir = Path.of(Objects.requireNonNull(req.dir(), "dir"));
                Path root = WorkspaceScan.findRoot(dir).orElse(dir);
                Freezer.Result r = Freezer.freeze(
                        root,
                        Objects.requireNonNull(req.ruleId(), "ruleId"),
                        req.reason(),
                        req.retire(),
                        req.acceptScope());
                ack = new GuardFreezeAck(r.error(), r.accepted(), r.total(), r.rebased());
            } catch (Exception e) {
                ack = GuardFreezeAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
