// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.MavenYank;
import cc.jumpkick.repo.RepoTransport;
import cc.jumpkick.repo.transport.RepoTransports;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code jk yank <version>}: take a published version out of the version list at the {@code
 * [publish]} repository, for every module a publish ships, or for one {@code
 * group:artifact:version}. The files stay, so every lock that pins the version still builds;
 * {@code --delete} removes them too. Docs: {@code docs/user/publish.md}.
 */
public final class YankCommand implements CliCommand {

    private static final String CHIP = "Yank";

    @Override
    public String name() {
        return "yank";
    }

    @Override
    public String description() {
        return "Delist a published version from a Maven repository";
    }

    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name|url>", "Yank here instead of [publish] repository.", "-R", "--repository"),
                Opt.flag("Also delete the version's files; locks that pin it stop building.", "--delete"),
                Opt.flag("Print what would change; change nothing.", "-U", "--dry-run"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("version", Arity.ONE, "The version to yank, or group:artifact:version for one artifact."));
    }

    /** One coordinate to yank. */
    record Coordinate(String group, String artifact, String version) {}

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        GlobalOptions global = GlobalOptions.from(in);
        boolean delete = in.isSet("delete");
        boolean dryRun = in.isSet("dry-run");
        String override = in.value("repository").orElse(null);
        String arg = in.positionals().get(0).strip();
        String[] parts = arg.split(":", -1);
        if (!(parts.length == 1 || parts.length == 3) || List.of(parts).stream().anyMatch(String::isBlank)) {
            CommandWedge.printFail(CHIP, "`" + arg + "`: expected <version> or <group:artifact:version>");
            return Exit.USAGE;
        }

        Path dir = global.workingDir();
        Path manifest = ManifestPaths.manifestIn(dir);
        ProjectInfo info = null;
        if (Files.isRegularFile(manifest)) {
            info = projectInfo(dir);
            if (info.error() != null) {
                CommandWedge.printFail(CHIP, info.error());
                return Exit.CONFIG;
            }
        } else if (parts.length == 1 || override == null) {
            CommandWedge.printFail(CHIP, manifest + " not found; give group:artifact:version and -R");
            return Exit.NO_INPUT;
        }

        PublishTarget.Resolution resolved =
                PublishTarget.yank(info == null ? "" : info.publishRepository(), manifest, override);
        PublishTarget target = resolved.target();
        if (target == null) {
            CommandWedge.printFail(CHIP, String.valueOf(resolved.refusal()));
            return Exit.CONFIG;
        }
        URI repoUrl = Objects.requireNonNull(target.repoUrl());

        List<Coordinate> coordinates = new ArrayList<>();
        if (parts.length == 3) {
            coordinates.add(new Coordinate(parts[0], parts[1], parts[2]));
        } else {
            for (ProjectInfo module : ReleaseCommand.modules(dir, Objects.requireNonNull(info))) {
                if (module.coordinatorOnly() || module.publishSkip()) continue;
                coordinates.add(new Coordinate(module.group(), module.name(), arg));
            }
        }

        RepoTransport transport =
                RepoTransports.forUrl(repoUrl, new Http(), PublishTarget.objectStore(manifest, repoUrl));
        MavenYank yank = new MavenYank(repoUrl, transport, target.credential());
        int exit = Exit.SUCCESS;
        for (Coordinate c : coordinates) {
            MavenYank.Outcome outcome;
            try {
                outcome = yank.yank(c.group(), c.artifact(), c.version(), delete, dryRun);
            } catch (IOException e) {
                CommandWedge.printFail(
                        CHIP, c.group() + ":" + c.artifact() + ":" + c.version() + ": " + e.getMessage());
                return Exit.FAILURE;
            }
            if (global.outputIsJson()) {
                row(outcome, repoUrl, dryRun);
            } else {
                report(outcome, repoUrl, delete, dryRun);
            }
            if (!outcome.refused().isEmpty()) exit = Exit.FAILURE;
        }
        return exit;
    }

    private static void row(MavenYank.Outcome o, URI repoUrl, boolean dryRun) {
        Map<String, String> refused = new LinkedHashMap<>();
        o.refused().forEach((path, status) -> refused.put(path, String.valueOf(status)));
        JsonlShape.row("yank", f -> f.string("coordinate", o.coordinate())
                .string("repository", repoUrl.toString())
                .bool("dryRun", dryRun)
                .bool("listed", o.listed())
                .array("before", o.before())
                .array("after", o.after())
                .array("files", o.files())
                .map("refused", refused));
    }

    private static void report(MavenYank.Outcome o, URI repoUrl, boolean delete, boolean dryRun) {
        String version = o.coordinate().substring(o.coordinate().lastIndexOf(':') + 1);
        if (o.nothing()) {
            String what = delete ? "is not listed and has no files" : "is not listed";
            CommandWedge.printOk(CHIP, o.coordinate() + " " + what + "; nothing to yank");
            return;
        }
        if (o.listed()) {
            CommandWedge.printOk(
                    CHIP,
                    (dryRun ? "Would yank " : "Yanked ") + o.coordinate() + " from " + repoUrl
                            + (dryRun ? " (dry-run)" : ""));
            CliOutput.out("  versions: " + list(o.before()) + " -> " + list(o.after()));
            if (o.after().isEmpty()) CliOutput.out("  no version left; latest and release cleared");
        } else {
            CommandWedge.printOk(CHIP, o.coordinate() + " is not listed");
        }
        if (dryRun) {
            for (String file : o.files()) CliOutput.out("  would delete " + file);
            return;
        }
        if (!o.files().isEmpty()) {
            warn("deleted " + o.files().size() + " files; locks that pin " + version + " no longer build");
        }
        for (var refused : o.refused().entrySet()) {
            CliOutput.err("  refused " + refused.getKey() + ": HTTP " + refused.getValue());
        }
    }

    private static String list(List<String> versions) {
        return versions.isEmpty() ? "(none)" : String.join(", ", versions);
    }

    private static void warn(String message) {
        CliOutput.err(Theme.colorize(Glyphs.bang(), Theme.active().warning()) + " " + message);
    }

    private static ProjectInfo projectInfo(Path dir) {
        try {
            return EngineClient.projectInfo(EnginePaths.current(), dir);
        } catch (Exception e) {
            return ProjectInfo.error(String.valueOf(e.getMessage()));
        }
    }
}
