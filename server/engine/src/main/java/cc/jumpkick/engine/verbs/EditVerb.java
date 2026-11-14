// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.StableVersions;
import cc.jumpkick.wire.protocol.EditRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoReads;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted {@code jk.toml} edits for {@code jk add} and {@code jk remove}: the wire op decoded
 * into the one {@link ManifestEdit}, which checks versions, writes atomically and relocks after a
 * dependency edit, putting the files back when the new lock trips the deny policy.
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
            boolean changed = false;
            String error;
            String detail = "";
            String warning = null;
            try {
                EditRequest req = EditRequest.decode(requestLine);
                Path file = Path.of(req.file());
                // Under the caller's session: an offline `jk add` must not reach a repository.
                Session session = Session.defaults()
                        .withConfig(JkConfig.empty().withOffline(req.offline()))
                        .withWorkingDir(
                                Objects.requireNonNull(file.toAbsolutePath().getParent(), "manifest directory"))
                        .withCancel(cancelToken);
                if (PROBE_DEPENDENCY.equals(req.op())) {
                    detail = SessionContext.where(session, () -> probe(file, req.args()));
                    error = null;
                } else {
                    ManifestEdit.Request edit =
                            new ManifestEdit.Request(file, List.of(op(req.op(), req.args())), false, true);
                    ManifestEdit.Result result = SessionContext.where(session, () -> ManifestEdit.run(edit));
                    changed = result.changed();
                    detail = result.detail();
                    error = result.error();
                    if (error == null && !result.lockOk()) {
                        // The edit stands: an offline add, or a module the lock cannot reach yet, is
                        // still the user's edit; the next jk lock or build reports the resolve.
                        warning = "jk.toml edited; the lock did not resolve: "
                                + String.valueOf(result.lock())
                                        .lines()
                                        .skip(1)
                                        .findFirst()
                                        .orElse("resolve failed")
                                        .replaceFirst("^E ", "");
                    }
                }
            } catch (Exception e) {
                error = Errors.text(e);
            }
            host.sendQuiet(writer, ProtoReads.editAck(changed, error, detail, warning));

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    /**
     * The op of {@code jk add --ping}: {@code [group, artifact, version]} ({@code version} blank for
     * any version) is looked up, not written, and the ack's detail is the URL that served it, blank
     * when none did. Here rather than in the client so the lookup goes through the user's
     * repository group: their mirrors, credentials and group bindings.
     */
    static final String PROBE_DEPENDENCY = "probe-dependency";

    private static String probe(Path file, List<String> args) throws IOException {
        if (args.size() < 3) throw new IOException(PROBE_DEPENDENCY + " takes group, artifact and version");
        String version = args.get(2).isBlank() ? null : args.get(2);
        return StableVersions.probe(file, args.get(0), args.get(1), version)
                .map(URI::toString)
                .orElse("");
    }

    /**
     * The wire op and its positional args as a {@link ManifestEdit.Op}. {@code add-dependency}
     * takes scope, library, group, artifact, version ({@code latest} for the newest stable) and an
     * optional classifier; {@code add-file-dependency} the same five and the jar's path.
     */
    public static ManifestEdit.Op op(@Nullable String op, List<String> args) {
        return switch (op == null ? "" : op) {
            case "add-dependency" ->
                new ManifestEdit.Op.AddDependency(
                        Scope.fromCanonical(args.get(0)),
                        args.get(1),
                        args.get(2),
                        args.get(3),
                        args.get(4),
                        args.size() > 5 && !args.get(5).isBlank() ? args.get(5) : null);
            case "add-file-dependency" ->
                new ManifestEdit.Op.AddFileDependency(
                        Scope.fromCanonical(args.get(0)),
                        args.get(1),
                        args.get(2),
                        args.get(3),
                        args.get(4),
                        Path.of(args.get(5)));
            case "remove-dependency" ->
                new ManifestEdit.Op.RemoveDependency(Scope.fromCanonical(args.get(0)), args.get(1));
            case "add-workspace-module" -> new ManifestEdit.Op.AddWorkspaceModule(args.get(0));
            case "register-workspace-module" -> new ManifestEdit.Op.RegisterWorkspaceModule(args.get(0));
            case "remove-workspace-module" -> new ManifestEdit.Op.RemoveWorkspaceModule(args.get(0));
            case "set-artifacts" ->
                new ManifestEdit.Op.SetArtifacts(Boolean.parseBoolean(args.get(0)), Boolean.parseBoolean(args.get(1)));
            default -> throw new IllegalArgumentException("unknown edit op: " + op);
        };
    }
}
