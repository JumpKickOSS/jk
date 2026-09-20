// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.workspace.OutdatedPlans;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.OutdatedReport;
import cc.jumpkick.wire.protocol.OutdatedRequest;
import cc.jumpkick.wire.protocol.ProtoReads;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public final class OutdatedVerb implements HostedVerb {

    private final VerbHost host;

    public OutdatedVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.OUTDATED_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("outdated");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-outdated-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            OutdatedReport report;
            try {
                OutdatedRequest req = OutdatedRequest.decode(requestLine);
                Path dir = Path.of(req.dir());
                Path cache = Path.of(req.cache());
                String repoUrl = req.repoUrl();
                Session session = ProtoSession.sessionOf(requestLine, cancelToken);
                report = SessionContext.where(
                        session,
                        () -> OutdatedPlans.compute(
                                dir,
                                cache,
                                repoUrl == null ? null : URI.create(repoUrl),
                                req.offline(),
                                beatsTo(writer)));
            } catch (Exception e) {
                report = OutdatedReport.error(Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    /**
     * Each beat is written with {@link VerbHost#send}, not {@code sendQuiet}: a client that hung up
     * (Ctrl-C) fails the write, and the failure stops the remaining fetches instead of letting a
     * read nobody is waiting for run to the end.
     */
    private OutdatedPlans.Progress beatsTo(@Nullable BufferedWriter writer) {
        return (checked, total, coordinate) -> {
            try {
                host.send(writer, ProtoReads.outdatedProgress(checked, total, coordinate));
            } catch (IOException e) {
                throw new UncheckedIOException("client left before the outdated report finished", e);
            }
        };
    }
}
