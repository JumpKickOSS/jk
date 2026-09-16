// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.guard.explain.GuardExplain;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.GuardExplainAck;
import cc.jumpkick.wire.protocol.GuardExplainRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@code jk guard explain}: an inline read of the rule file, baseline and last outcomes; never a build. */
public final class GuardExplainVerb implements HostedVerb {

    private final VerbHost host;

    public GuardExplainVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GUARD_EXPLAIN_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("guard-explain");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-guard-";
    }

    /** The manifest's {@code [guards]} table; an unparseable manifest is the build's error, not explain's. */
    private static GuardsConfig guardsConfig(Path root) {
        try {
            return JkBuildParser.guardsConfig(ManifestPaths.manifestIn(root));
        } catch (RuntimeException unparseable) {
            return GuardsConfig.ABSENT;
        }
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            GuardExplainAck ack;
            try {
                GuardExplainRequest req = GuardExplainRequest.decode(requestLine);
                GuardExplain.Result r;
                if (req.schema() != null) {
                    r = GuardExplain.schema(req.schema());
                } else {
                    Path dir = Path.of(Objects.requireNonNull(req.dir(), "dir"));
                    Path root = WorkspaceScan.findRoot(dir).orElse(dir);
                    r = GuardExplain.explain(root, guardsConfig(root), req.ruleId());
                }
                ack = new GuardExplainAck(r.error(), r.text(), r.json());
            } catch (Exception e) {
                ack = GuardExplainAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
