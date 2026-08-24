// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.ExecPlans;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class ProjectInfoVerb implements HostedVerb {

    private final VerbHost host;

    public ProjectInfoVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.PROJECT_INFO_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("project-info");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-projinfo-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            ProjectInfo info;
            try {
                info = ExecPlans.projectInfo(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Jsonl.str(requestLine, "modules"),
                        Jsonl.str(requestLine, "affectedSince"),
                        Jsonl.bool(requestLine, "counts", false));
            } catch (RuntimeException e) {
                info = ProjectInfo.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, info.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
