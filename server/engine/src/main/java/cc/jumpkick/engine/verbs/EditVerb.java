// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.StableVersions;
import cc.jumpkick.runtime.base.EditOps;
import cc.jumpkick.wire.protocol.EditRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoReads;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted {@code jk.toml} edits. An {@code add-dependency} whose selector is {@code latest}
 * is pinned to the newest stable release in the project's repositories before the editor runs.
 */
public final class EditVerb implements HostedVerb {

    private final VerbHost host;

    public EditVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.EDIT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("edit");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-edit-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            EditOps.Result result;
            try {
                EditRequest req = EditRequest.decode(requestLine);
                Path file = Path.of(req.file());
                result = EditOps.apply(file, req.op(), pinned(file, req.op(), req.args()));
            } catch (RuntimeException | IOException e) {
                result = new EditOps.Result(false, Errors.text(e));
            }
            host.sendQuiet(writer, ProtoReads.editAck(result.changed(), result.error(), result.detail()));

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    /** {@code add-dependency} args with {@code latest} replaced by the number to write. */
    private static List<String> pinned(Path file, @Nullable String op, List<String> args) throws IOException {
        if (!"add-dependency".equals(op) || args.size() < 5) return args;
        List<String> out = new ArrayList<>(args);
        out.set(4, StableVersions.pinnedVersion(file, args.get(2), args.get(3), args.get(4)));
        return out;
    }
}
