// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.GuardFixtures;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.GuardTestAck;
import cc.jumpkick.wire.protocol.GuardTestRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@code jk guard freeze}: the one privileged baseline write, engine-side like every baseline write. */
public final class GuardTestVerb implements HostedVerb {

    private final VerbHost host;

    public GuardTestVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GUARD_TEST_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("guard-test");
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
            GuardTestAck ack;
            try {
                GuardTestRequest req = GuardTestRequest.decode(requestLine);
                Path dir = Path.of(Objects.requireNonNull(req.dir(), "dir"));
                Path root = WorkspaceScan.findRoot(dir).orElse(dir);
                Cas cas = JkStores.storeCas();
                GuardFixtures.Result r = GuardFixtures.run(root, cas);
                ack = new GuardTestAck(null, r.text(), r.failures());
            } catch (Exception e) {
                ack = GuardTestAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
