// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.guard.eval.CommitMessageCheck;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.LoadError;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.GuardCommitMsgAck;
import cc.jumpkick.wire.protocol.GuardCommitMsgRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@code jk guard commit-msg}: the commit rules over one message, a sync read with no baseline. */
public final class GuardCommitMsgVerb implements HostedVerb {

    private final VerbHost host;

    public GuardCommitMsgVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GUARD_COMMIT_MSG_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("guard-commit-msg");
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
            GuardCommitMsgAck ack;
            try {
                GuardCommitMsgRequest req = GuardCommitMsgRequest.decode(requestLine);
                Path dir = Path.of(Objects.requireNonNull(req.dir(), "dir"));
                Path root = WorkspaceScan.findRoot(dir).orElse(dir);
                LoadResult load =
                        GuardRules.load(root, JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST)));
                if (load.hasErrors()) {
                    StringBuilder sb = new StringBuilder("jk-guards.toml did not load; no commit rule ran\n");
                    for (LoadError e : load.errors())
                        sb.append("  ").append(e.render()).append('\n');
                    ack = new GuardCommitMsgAck(
                            null, sb.toString().stripTrailing(), load.errors().size());
                } else {
                    CommitMessageCheck.Result r =
                            CommitMessageCheck.check(load.rules(), req.message() == null ? "" : req.message());
                    ack = new GuardCommitMsgAck(null, r.render(), r.problems().size());
                }
            } catch (Exception e) {
                ack = GuardCommitMsgAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
