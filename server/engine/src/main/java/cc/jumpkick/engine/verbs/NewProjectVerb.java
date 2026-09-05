// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.host.Errors;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.NewProjectAck;
import cc.jumpkick.wire.protocol.NewProjectRequest;
import java.io.BufferedWriter;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class NewProjectVerb implements HostedVerb {

    private final VerbHost host;

    public NewProjectVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.NEW_PROJECT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("new");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-new-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("new");
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            NewProjectAck ack;
            try {
                NewProjectRequest wire = NewProjectRequest.decode(requestLine);
                NewProjectOps.Request req = new NewProjectOps.Request(
                        wire.name(),
                        wire.parentDir(),
                        wire.group(),
                        wire.lang(),
                        wire.layout(),
                        wire.template(),
                        wire.executable(),
                        wire.jdk(),
                        wire.javaRelease(),
                        wire.assembly(),
                        wire.nativeImage(),
                        wire.plugin(),
                        wire.kotlinModule(),
                        wire.deps(),
                        wire.sample(),
                        wire.standalone(),
                        wire.templateParams(),
                        wire.relaxParent(),
                        wire.targetDir());
                NewProjectOps.Created created = NewProjectOps.createWithIdentity(req);
                ack = NewProjectAck.of(created.path().toString(), created.projectId(), created.filesWritten());
            } catch (Exception e) {
                ack = NewProjectAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
