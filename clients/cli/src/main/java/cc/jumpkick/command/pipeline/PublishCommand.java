// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coords;
import cc.jumpkick.config.ReleaseVerb;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.SnapshotQualifier;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.release.ReleaseTag;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.CentralBudget;
import cc.jumpkick.wire.runtime.ImagePush;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk publish}: assemble, sign and upload a release (engine-hosted worker), one {@link
 * PublishLane lane} per target {@code jk.toml} names: the libraries to {@code [publish]
 * repository}, the deliverables to {@code [publish] release}, the container images to {@code
 * [image] registry}. {@code --only} narrows the lanes and {@code --stage} uploads only this
 * platform's release files. The credential and the GPG
 * passphrase are resolved client-side and passed on the request.
 *
 * <p>A Central Portal publish uploads one signed bundle, waits for {@code VALIDATED}, then releases
 * the deployment and waits for {@code PUBLISHED}; {@code [publish] hold = true} stops at {@code
 * VALIDATED}. A dry run writes the bundle under {@code target/publish/} and lists it.
 */
public final class PublishCommand implements CliCommand {

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public String description() {
        return "Publish libraries, release files and container images";
    }

    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value(
                        "<name|url>",
                        "Publish here instead of [publish] repository; central names the Portal.",
                        "-R",
                        "--repository"),
                Opt.value("<lane>", "Run only these lanes: library, release, image.", "-l", "--only")
                        .repeat()
                        .splitOn(","),
                Opt.flag("Upload this platform's release files for a later final run.", "-t", "--stage"),
                Opt.flag("Permit publishing -SNAPSHOT versions.", "-s", "--allow-snapshot"),
                Opt.flag("Print the upload plan; no HTTP requests.", "-U", "--dry-run"),
                CommonOpts.skipTests());
    }

    boolean allowSnapshot;
    boolean dryRun;
    boolean central;

    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.global = GlobalOptions.from(in);
        // The release rule makes a publish a release outside git and holds it to the tag inside;
        // within `jk release` the release verb stands. Leave the session as found.
        Session before = SessionContext.current();
        if (before.releaseVerb() == ReleaseVerb.NONE)
            SessionContext.install(before.withReleaseVerb(ReleaseVerb.PUBLISH));
        try {
            return publish(in);
        } finally {
            SessionContext.install(before);
        }
    }

    /**
     * Why {@code dir} is not a release to publish: a publish ships only what a release build makes,
     * a clean checkout at the declared version's tag (or the commit {@code jk release} is tagging).
     */
    static String notARelease(ProjectInfo info, Path dir) {
        if (!info.releaseLine().isEmpty()) return info.releaseLine() + "; commit or stash, then publish";
        String declared = info.version().endsWith(SnapshotQualifier.SUFFIX)
                ? info.version().substring(0, info.version().length() - SnapshotQualifier.SUFFIX.length())
                : info.version();
        String tag = ReleaseTag.render(info.releaseTag(), declared);
        ReleaseCommand.Git git = new ReleaseCommand.Git(dir);
        String other = git.output("tag", "--points-at", "HEAD");
        String head = other == null || other.isEmpty()
                ? "HEAD carries no tag " + tag
                : "HEAD carries " + other.lines().findFirst().orElse("") + ", not " + tag;
        return head + ", so this is a snapshot; publish from a clean checkout of " + tag;
    }

    /**
     * Why this host may not stage a release: its platform is not one {@code [release] platforms}
     * lists (with none listed, the host's own is the one); {@code null} when it may.
     */
    static @Nullable String stageRefusal(List<String> platforms, String host) {
        List<String> listed = platforms.isEmpty() ? List.of(host) : platforms;
        if (listed.contains(host)) return null;
        return host + " is not in [release] platforms; stage on " + String.join(", ", listed);
    }

    private int publish(Invocation in) throws IOException, InterruptedException {
        this.allowSnapshot = in.isSet("allow-snapshot");
        this.dryRun = in.isSet("dry-run");

        Path projectDir = global.workingDir();
        Path jkBuildPath = ManifestPaths.manifestIn(projectDir);
        if (!Files.exists(jkBuildPath)) {
            CommandWedge.printFail("Publish", jkBuildPath + " not found.");
            return Exit.NO_INPUT;
        }

        // Project facts via PROJECT_INFO; the credential is a deliberate client-side read
        // (keychain/env prompts stay here; secrets never ride the wire).
        ProjectInfo info = projectInfo(projectDir);
        if (info.error() != null) {
            CommandWedge.printFail("Publish", info.error());
            return Exit.CONFIG;
        }
        // Nothing builds for a tree that is not the release: the refusal comes first.
        if (!info.releaseBuild()) {
            CommandWedge.printFail("Publish", notARelease(info, projectDir));
            return Exit.CONFIG;
        }
        String repositoryOverride = in.value("repository").orElse(null);
        boolean stage = in.isSet("stage");
        PublishLanes selected = PublishLanes.select(
                in.values("only"),
                stage,
                dryRun,
                repositoryOverride != null || !info.publishRepository().isEmpty(),
                info.publishRelease(),
                !info.imageRegistry().isEmpty());
        if (selected.refusal() != null) {
            CommandWedge.printFail("Publish", selected.refusal());
            return Exit.CONFIG;
        }
        List<PublishLane> lanes = selected.lanes();
        String stageRefusal = stage ? stageRefusal(info.releasePlatforms(), ReleasePlatform.current()) : null;
        if (stageRefusal != null) {
            CommandWedge.printFail("Publish", stageRefusal);
            return Exit.CONFIG;
        }
        PublishTarget.Resolution resolved = PublishTarget.resolve(
                info,
                projectDir,
                jkBuildPath,
                repositoryOverride,
                dryRun,
                lanes.contains(PublishLane.LIBRARY),
                JkDirs::env);
        PublishTarget target = resolved.target();
        if (target == null) {
            CommandWedge.printFail("Publish", String.valueOf(resolved.refusal()));
            return Exit.CONFIG;
        }
        this.central = target.central();
        if (target.central() && !dryRun && target.lacksCredential()) {
            CommandWedge.printFail("Publish", PublishTarget.centralTokenHint(Objects.requireNonNull(target.repoUrl())));
            return Exit.CONFIG;
        }

        // Path deps are consume-only (docs/path-source-deps.md): a published POM would
        // reference a coordinate no consumer can resolve. Refuse before any upload —
        // `jk export` warn-and-skips for the same reason.
        for (String pathDep : lanes.contains(PublishLane.LIBRARY) ? info.pathDeps() : List.<String>of()) {
            CommandWedge.printFail(
                    "Publish",
                    "`" + pathDep + "` is a path dependency and cannot be published; make it a"
                            + " workspace module or a published coordinate");
            return Exit.CONFIG;
        }

        PublishLanes.@Nullable ReleaseToken releaseToken = null;
        if (lanes.contains(PublishLane.RELEASE) && ReleaseTarget.FORGES.contains(info.publishRelease()) && !dryRun) {
            try {
                releaseToken = PublishLanes.releaseToken(
                        projectDir, info.publishRelease(), PublishLanes.forgeAuth(), JkDirs::env);
            } catch (IllegalArgumentException e) {
                CommandWedge.printFail("Publish", String.valueOf(e.getMessage()));
                return Exit.CONFIG;
            }
        }

        TailArgs.Publish args = new TailArgs.Publish(
                target.repoUrl(),
                allowSnapshot,
                dryRun,
                target.keyFile(),
                target.keyFile() == null ? null : JkDirs.env("JK_GPG_PASSPHRASE"),
                target.credential(),
                target.central(),
                lanes,
                stage,
                releaseToken == null ? null : releaseToken.value(),
                releaseToken != null && releaseToken.job());
        // The build and the upload run as every build verb runs: the same phases through PACKAGE,
        // the upload as the goal's tail, a workspace member's prerequisites first.
        // The release carries this host's native executables when [release] platforms lists it.
        boolean nativeRelease = lanes.contains(PublishLane.RELEASE)
                && stageRefusal(info.releasePlatforms(), ReleasePlatform.current()) == null;
        return BuildVerbCommand.run(
                new PublishVerb(args, nativeRelease, new PublishVerb.Report() {
                    @Override
                    public void module(ModuleOutcome module, ModuleOutcome.Published published, List<String> released)
                            throws IOException {
                        report(module, published, released, projectDir);
                    }

                    @Override
                    public void release(ReleaseOutcome release) {
                        reportRelease(release);
                    }

                    @Override
                    public void images(List<ImagePush> images) {
                        reportImages(images);
                    }
                }),
                BuildVerbCommand.forward(in, WorkspaceTarget.PUBLISH.tests(), b -> {}),
                true);
    }

    /**
     * A module's publish lines: the coordinate and where it went, the Portal deployment when there
     * is one, the documents the run left under target/ (the SBOMs, a Central bundle) by path, and
     * on a dry run the bundle's entries. A module that failed prints only a refused deployment's id
     * and errors. Paths are workspace-relative like every other path jk prints.
     */
    private void report(ModuleOutcome module, ModuleOutcome.Published published, List<String> released, Path projectDir)
            throws IOException {
        if (!module.success()) {
            if (published.deploymentId() != null && !global.outputIsJson()) {
                CliOutput.err(
                        "Central Portal deployment " + published.deploymentId() + " · " + published.deploymentState());
                for (String error : published.deploymentErrors()) CliOutput.err("  - " + error);
            }
            return;
        }
        Path workspaceRoot = WorkspaceLocator.findRoot(projectDir).orElse(projectDir);
        if (!global.outputIsJson()) {
            String summary = published.dryRun()
                    ? "(dry-run)"
                    : "(" + published.files() + " files"
                            + (published.existing() > 0 ? ", " + published.existing() + " already there" : "") + ")";
            String where = central ? " to the Central Portal " : " ";
            ProjectInfo info = ProjectInfos.orNull(module.dir());
            String coord = info == null ? module.coord() : Coords.gav(info.group(), info.name(), info.version());
            CliOutput.out("Published " + coord + where + summary);
            if (!published.bundle().isEmpty()) {
                CliOutput.out("  " + CentralBudget.summary(published.bundle().size(), published.bundleBytes()));
                List<String> warnings =
                        CentralBudget.warnings(published.bundle().size(), published.bundleBytes());
                for (int i = 0; i < warnings.size(); i++) CliOutput.err((i == 0 ? "  ! " : "    ") + warnings.get(i));
            }
            if (published.deploymentId() != null) {
                boolean out = released.contains(published.deploymentId());
                CliOutput.out("  deployment " + published.deploymentId() + " · "
                        + (out ? "PUBLISHED after the release" : published.deploymentState())
                        + (!out && "VALIDATED".equals(published.deploymentState())
                                ? "; release it from the Portal"
                                : ""));
            }
            for (String written : published.written()) {
                CliOutput.out("  wrote " + displayPath(Path.of(written), workspaceRoot, projectDir));
            }
            if (published.dryRun()) {
                for (String entry : published.bundle()) CliOutput.out("    " + entry);
            }
        } else {
            for (String written : published.written()) {
                CliOutput.machine(JsonlShape.output(
                        "publish", "wrote " + displayPath(Path.of(written), workspaceRoot, projectDir)));
            }
        }
    }

    /**
     * The release lane's lines: where the release lives, then every file with what the run did with
     * it (a dry run: its size, SHA-256, destination and role), then what its last step did or would
     * do, and on stderr each note (an unsigned release). Under {@code -O json} one line per file
     * and per note.
     */
    private void reportRelease(ReleaseOutcome release) {
        if (global.outputIsJson()) {
            for (ReleaseOutcome.File f : release.files()) {
                CliOutput.machine(JsonlShape.output("publish", "release " + f.status() + " " + f.name()));
            }
            for (String note : release.notes()) CliOutput.machine(JsonlShape.output("publish", note));
            return;
        }
        for (String line : releaseLines(release)) CliOutput.out(line);
        for (String note : release.notes()) CliOutput.err("  ! " + note);
    }

    /** {@link #reportRelease}'s text lines. */
    static List<String> releaseLines(ReleaseOutcome release) {
        List<String> lines = new ArrayList<>();
        String run = release.dryRun() ? "dry run" : release.stage() ? "staged" : "final";
        lines.add("Release " + release.destination() + " (" + run + ")");
        for (ReleaseOutcome.File f : release.files()) {
            StringBuilder line = new StringBuilder("  ")
                    .append(String.format("%-9s", f.status()))
                    .append(f.name());
            if (f.size() >= 0) line.append("  ").append(f.size()).append(" bytes");
            if (!f.sha256().isEmpty()) line.append("  sha256 ").append(f.sha256());
            if (release.dryRun())
                line.append("  -> ").append(release.destination()).append(f.name());
            line.append("  (").append(f.role()).append(')');
            lines.add(line.toString());
        }
        if (release.finish() != null) lines.add("  " + release.finish());
        for (String id : release.released()) lines.add("  published Central deployment " + id);
        return lines;
    }

    /** The image lane's lines: each image pushed with its digest, or on a dry run each it would push. */
    private void reportImages(List<ImagePush> images) {
        for (String line : imageLines(images)) {
            if (global.outputIsJson()) CliOutput.machine(JsonlShape.output("publish", line));
            else CliOutput.out(line);
        }
    }

    /** {@link #reportImages}'s lines. */
    static List<String> imageLines(List<ImagePush> images) {
        List<String> lines = new ArrayList<>();
        for (ImagePush i : images) {
            if (i.dryRun()) lines.add("Would push image " + i.reference());
            else lines.add("Pushed image " + i.reference() + (i.digest().isEmpty() ? "" : "@" + i.digest()));
        }
        return lines;
    }

    /**
     * A written document's path as the reader sees it: relative to the workspace root when it
     * sits under it, else to the project directory, else absolute. Forward slashes on every
     * platform, so a release script reads one spelling.
     */
    static String displayPath(Path written, Path workspaceRoot, Path projectDir) {
        Path p = WorkingDirectory.resolve(written);
        Path ws = WorkingDirectory.resolve(workspaceRoot);
        Path proj = WorkingDirectory.resolve(projectDir);
        Path shown = p.startsWith(ws) ? ws.relativize(p) : p.startsWith(proj) ? proj.relativize(p) : p;
        return shown.toString().replace(shown.getFileSystem().getSeparator(), "/");
    }

    private static ProjectInfo projectInfo(Path dir) {
        try {
            return EngineClient.projectInfo(EnginePaths.current(), dir);
        } catch (Exception e) {
            return ProjectInfo.error(String.valueOf(e.getMessage()));
        }
    }
}
