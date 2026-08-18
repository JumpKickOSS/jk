// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.NewProjectAck;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.jsonl.Jsonl;
import java.io.BufferedWriter;
import java.util.List;

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
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            NewProjectAck ack;
            try {
                NewProjectOps.Request req = new NewProjectOps.Request(
                        Jsonl.str(requestLine, "name"),
                        Jsonl.str(requestLine, "parentDir"),
                        Jsonl.str(requestLine, "group"),
                        Jsonl.str(requestLine, "lang"),
                        Jsonl.str(requestLine, "layout"),
                        Jsonl.str(requestLine, "template"),
                        Jsonl.bool(requestLine, "executable", false),
                        Jsonl.str(requestLine, "framework"),
                        Jsonl.str(requestLine, "jdk"),
                        Jsonl.intValue(requestLine, "javaRelease", 0),
                        Jsonl.bool(requestLine, "assembly", false),
                        Jsonl.bool(requestLine, "nativeImage", false),
                        Jsonl.bool(requestLine, "plugin", false),
                        Jsonl.str(requestLine, "kotlinModule"),
                        Jsonl.strArray(requestLine, "deps"),
                        Jsonl.bool(requestLine, "sample", true),
                        Jsonl.bool(requestLine, "standalone", true),
                        Jsonl.strMap(requestLine, "templateParams"),
                        Jsonl.bool(requestLine, "relaxParent", false),
                        Jsonl.str(requestLine, "targetDir"));
                NewProjectOps.Created created = NewProjectOps.createWithIdentity(req);
                ack = NewProjectAck.of(created.path().toString(), created.projectId(), created.filesWritten());
            } catch (Exception e) {
                ack = NewProjectAck.error(String.valueOf(e.getMessage()));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
