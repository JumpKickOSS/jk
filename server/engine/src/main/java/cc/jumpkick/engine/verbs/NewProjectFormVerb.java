// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.form.Form;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.giter8.TemplateSpec;
import cc.jumpkick.host.Errors;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.NewProjectFormAck;
import cc.jumpkick.wire.protocol.NewProjectFormRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Serves the new-project form to the CLI: jk's step, the template's when one is named, and the catalogue. */
public final class NewProjectFormVerb implements HostedVerb {

    private final VerbHost host;

    public NewProjectFormVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.NEW_PROJECT_FORM_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("new-form");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-new-form-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            NewProjectFormAck ack;
            try {
                ack = answer(NewProjectFormRequest.decode(requestLine));
            } catch (Exception e) {
                ack = NewProjectFormAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    static NewProjectFormAck answer(NewProjectFormRequest req) throws Exception {
        String dir = req.dir();
        if (dir == null || dir.isBlank()) throw new IllegalArgumentException("new-project-form request names no dir");
        Path cwd = Path.of(dir);
        String ref = req.template();
        Form form;
        String id = "";
        if (ref == null || ref.isBlank()) {
            form = NewProjectForm.project();
        } else {
            NewProjectOps.TemplateForm template = NewProjectOps.templateForm(ref.strip(), req.lang(), cwd);
            form = NewProjectForm.of(template);
            id = template.id();
        }
        Map<String, String> catalogue = new LinkedHashMap<>();
        for (TemplateSpec s : Giter8TemplateIndex.picker(Giter8TemplateIndex.searchRoots(cwd))) {
            catalogue.put(s.id(), s.description());
        }
        return new NewProjectFormAck(null, form.toJson(), id, catalogue);
    }
}
