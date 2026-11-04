// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.eval.DocsCheck;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadError;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.GuardDocsAck;
import cc.jumpkick.wire.protocol.GuardDocsRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** {@code jk guard docs}: the Markdown rules over the tree, a sync read with no build. */
public final class GuardDocsVerb implements HostedVerb {

    private final VerbHost host;

    public GuardDocsVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GUARD_DOCS_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("guard-docs");
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
            GuardDocsAck ack;
            try {
                GuardDocsRequest req = GuardDocsRequest.decode(requestLine);
                Path dir = Path.of(Objects.requireNonNull(req.dir(), "dir"));
                Path root = WorkspaceScan.findRoot(dir).orElse(dir);
                LoadResult load = GuardRules.load(root, JkBuildParser.guardsConfig(ManifestPaths.manifestIn(root)));
                if (load.hasErrors()) {
                    StringBuilder sb = new StringBuilder("jk-guards.toml did not load; no docs rule ran\n");
                    for (LoadError e : load.errors())
                        sb.append("  ").append(e.render()).append('\n');
                    ack = new GuardDocsAck(
                            null, sb.toString().stripTrailing(), load.errors().size());
                } else {
                    DocsCheck.Result r =
                            DocsCheck.check(root, load.rules(), BaselineFile.read(GuardsPresence.baselineFile(root)));
                    ack = new GuardDocsAck(null, r.text(), r.failures());
                }
            } catch (Exception e) {
                ack = GuardDocsAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
