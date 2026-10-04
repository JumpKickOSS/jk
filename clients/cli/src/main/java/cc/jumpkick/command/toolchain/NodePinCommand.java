// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.NodePinEdit;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk node pin [spec]} — write the project's Node.js into its {@code jk.toml} and relock. With
 * no spec, the version {@code .nvmrc}, {@code .node-version} or {@code package.json} names, else
 * the newest LTS major. {@code --file} also writes {@code .node-version} for other tools.
 */
public final class NodePinCommand implements CliCommand {

    @Override
    public String name() {
        return "pin";
    }

    @Override
    public String description() {
        return "Pin the project to a Node.js version and relock";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<module>", "Pin this workspace member, not the nearest jk.toml", "-m", "--module"),
                Opt.flag("Also write .node-version for editors and other tools", "--file"),
                Opt.flag("Write the manifest without relocking", "--no-lock"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("spec", Arity.ZERO_OR_ONE, "24, =24.21.0 or lts (default: what the project's files suggest)"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        Path dir = GlobalOptions.from(in).workingDir();
        Optional<Path> target = in.value("module").isPresent()
                ? Optional.of(LockPaths.lockOwnerDir(dir)
                        .resolve(in.value("module").get())
                        .normalize())
                : NodeInstalls.nearestManifestDir(dir);
        if (target.isPresent() && ManifestPaths.isShadowed(target.get())) {
            CommandWedge.printFail(
                    NodeCli.WEDGE, ManifestPaths.noManifestToEdit(target.get().toString()));
            return Exit.CONFIG;
        }
        if (target.isEmpty() || !Files.isRegularFile(ManifestPaths.manifestIn(target.get()))) {
            CommandWedge.printFail(NodeCli.WEDGE, "no jk.toml at or above " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path manifest = ManifestPaths.manifestIn(target.get());
        String value;
        String why;
        if (!in.positionals().isEmpty()) {
            value = NodePinEdit.tomlValue(in.positionals().get(0));
            why = null;
        } else {
            NodeShape.Proposal p = NodeShape.propose(target.get());
            if (p.source() != null) {
                value = p.spec();
                why = p.source();
            } else {
                int lts = NodeInstalls.newestLtsMajor(NodeCli.releasesOrNone());
                value = lts > 0 ? Integer.toString(lts) : p.spec();
                why = lts > 0 ? "the newest LTS" : null;
            }
        }
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        String edited;
        try {
            edited = NodePinEdit.apply(text, value);
        } catch (JkBuildParseException | IllegalStateException e) {
            CommandWedge.printFail(NodeCli.WEDGE, "could not write node = " + value + ": " + e.getMessage());
            return Exit.CONFIG;
        }
        Files.writeString(manifest, edited, StandardCharsets.UTF_8);
        String plain = unquote(value);
        if (in.isSet("file")) {
            Files.writeString(
                    target.get().resolve(".node-version"),
                    (plain.startsWith("=") ? plain.substring(1) : plain) + "\n",
                    StandardCharsets.UTF_8);
        }
        boolean json = GlobalOptions.outputIsJson(in);
        int exit = in.isSet("no-lock") ? 0 : relock(LockPaths.lockOwnerDir(target.get()));
        if (json) {
            NodeCli.row(EngineProtocol.NODE_PINNED, f -> f.string("manifest", manifest.toString())
                    .string("spec", plain)
                    .optionalString("from", why)
                    .bool("locked", !in.isSet("no-lock") && exit == 0));
            return exit;
        }
        if (exit != 0) return exit;
        CommandWedge.printOk(
                NodeCli.WEDGE,
                "Pinned " + PathDisplay.styledRaw(manifest) + " to node = " + value
                        + (why == null ? "" : " (from " + why + ")"));
        return 0;
    }

    private static int relock(Path root) throws IOException {
        var session = SessionContext.current();
        EngineRequests.LockOutcome outcome = EngineClient.runLock(
                EnginePaths.current(),
                new EngineRequests.LockRequest(
                        root, JkDirs.cache(), List.of(), false, false, null, session.offline(), false, false),
                new EngineRequests.LockHandler() {
                    @Override
                    public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
                        return new BuildPlanListener() {};
                    }
                });
        for (String err : outcome.errors()) CommandWedge.printFail("Lock", err);
        return outcome.exitCode();
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
