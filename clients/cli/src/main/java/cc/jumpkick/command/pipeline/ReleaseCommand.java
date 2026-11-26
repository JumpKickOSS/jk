// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.cli.api.BuildOptions;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.config.ReleaseVerb;
import cc.jumpkick.config.ReleaseVersion;
import cc.jumpkick.config.ReleaseVersion.Segment;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.release.ReleaseTag;
import cc.jumpkick.repo.InstalledRelease;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RemoteClaim;
import cc.jumpkick.repo.transport.RepoTransports;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.Task;
import cc.jumpkick.secret.SecretCommand;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk release} — cut the release the manifest declares: build and test, install the version
 * once, tag the commit, move {@code jk.toml} to the next version in a commit of its own, push both,
 * and publish the tag when this machine is the one that publishes ({@link PublishMode}).
 */
public final class ReleaseCommand implements CliCommand {

    private static final String CHIP = "Release";

    @Override
    public String name() {
        return "release";
    }

    @Override
    public boolean holdsInstall() {
        return true;
    }

    @Override
    public String description() {
        return "Tag the declared version and move jk.toml to the next one";
    }

    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("After the release, move the major version (1.4.7 → 2.0.0).", "--major"),
                Opt.flag("After the release, move the minor version (1.4.7 → 1.5.0).", "--minor"),
                Opt.flag("After the release, move the patch version (1.4.7 → 1.4.8). The default.", "--patch"),
                Opt.value(
                        "<ci|local|none>",
                        "Who publishes the release: CI on the pushed tag, this machine, or nobody."
                                + " Default: inferred.",
                        "--publish"),
                Opt.flag("Keep the branch and tag local; push nothing.", "--no-push"),
                Opt.flag("Check everything and print what would happen; change nothing.", "--dry-run"),
                Opt.flag(
                        "Take back a release cut here and not pushed: its tag, the version commit, its installed jars.",
                        "--undo"),
                Opt.value(
                        "<m2|jk>",
                        "Install the release into m2 (the Maven local repository) or jk (JumpKick's own repository).",
                        "--destination"),
                CommonOpts.skipTests(),
                CommonOpts.jdksDir(),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<dir>", "Override the lib directory.", "--lib-dir").hide(),
                Opt.value("<dir>", "Override the local Maven repo root (~/.m2).", "--m2-dir")
                        .hide());
    }

    /** Who uploads a release. */
    enum PublishMode {
        /** The pushed tag starts a CI job that runs {@code jk publish}. */
        CI,
        /** This machine runs {@code jk publish} on the tag once it is pushed. */
        LOCAL,
        /** Nothing is uploaded: the release is the tag and the installed jars. */
        NONE;

        /** The clause a dry run adds for this mode. */
        String dryRunNote() {
            return switch (this) {
                case LOCAL -> ", publish from here";
                case CI -> ", leave the publish to CI";
                case NONE -> "";
            };
        }

        static @Nullable PublishMode parse(String word) {
            return switch (word) {
                case "ci" -> CI;
                case "local" -> LOCAL;
                case "none" -> NONE;
                default -> null;
            };
        }
    }

    /**
     * The publish mode: {@code override} ({@code --publish}), else {@code [release] publish}, else
     * inferred. With no destination there is nothing to upload. With one, CI that runs {@code jk
     * publish} publishes it; with none, this machine does, when {@code [publish] repository} names
     * somewhere to upload (an image registry alone has no {@code jk publish} lane yet).
     */
    static PublishMode publishMode(@Nullable String override, ProjectInfo info, Path ciRoot, boolean inGit) {
        String word = override != null ? override : info.releasePublish();
        PublishMode chosen = word.isEmpty() ? null : PublishMode.parse(word);
        if (chosen != null) return chosen;
        boolean repository = !info.publishRepository().isEmpty();
        if (!repository && info.imageRegistry().isEmpty()) return PublishMode.NONE;
        if (inGit && CiPublishScan.runsPublish(ciRoot)) return PublishMode.CI;
        return repository ? PublishMode.LOCAL : PublishMode.NONE;
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        // A release reads, builds, installs and withdraws the declared version, never a snapshot: the
        // engine's release rule makes the release verb's session a release. Leave the session as found.
        Session before = SessionContext.current();
        SessionContext.install(before.withReleaseVerb(ReleaseVerb.RELEASE));
        try {
            return release(in);
        } finally {
            SessionContext.install(before);
        }
    }

    private int release(Invocation in) throws IOException, InterruptedException {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path manifest = ManifestPaths.manifestIn(dir);
        if (!Files.isRegularFile(manifest)) {
            CommandWedge.printFail(CHIP, "no jk.toml here; run `jk release` inside a project");
            return Exit.USAGE;
        }
        int segments = (in.isSet("major") ? 1 : 0) + (in.isSet("minor") ? 1 : 0) + (in.isSet("patch") ? 1 : 0);
        if (segments > 1) {
            CommandWedge.printFail(CHIP, "--major, --minor and --patch are alternatives: name at most one");
            return Exit.USAGE;
        }
        Segment segment = in.isSet("major") ? Segment.MAJOR : in.isSet("minor") ? Segment.MINOR : Segment.PATCH;
        boolean dryRun = in.isSet("dry-run");
        String publishWord = in.value("publish").orElse(null);
        if (publishWord != null && PublishMode.parse(publishWord) == null) {
            CommandWedge.printFail(CHIP, "--publish must be ci, local or none, not `" + publishWord + "`");
            return Exit.USAGE;
        }
        boolean noPush = in.isSet("no-push");
        boolean undo = in.isSet("undo");
        String destinationWord = in.value(InstallDestination.KEY).orElse(null);
        InstallDestination destination = destinationWord == null
                ? null
                : InstallDestination.parse(destinationWord).orElse(null);
        if (destinationWord != null && destination == null) {
            CommandWedge.printFail(CHIP, "--destination must be m2 or jk, not `" + destinationWord + "`");
            return Exit.USAGE;
        }
        if (undo && (publishWord != null || noPush || segments > 0)) {
            CommandWedge.printFail(CHIP, "--undo takes no --publish, --no-push or version segment");
            return Exit.USAGE;
        }

        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        String version = ReleaseVersion.declared(text).orElse(null);
        if (version == null) {
            CommandWedge.printFail(CHIP, "this jk.toml inherits its version; run `jk release` where `version` is set");
            return Exit.CONFIG;
        }
        boolean inGit = Git.present(dir);
        if (undo && !inGit) {
            CommandWedge.printFail(
                    CHIP,
                    "no git here, so jk cannot tell which release to take back; set `version` in jk.toml back"
                            + " and delete that version's installed jars by hand");
            return Exit.CONFIG;
        }
        if (undo) {
            return withdraw(
                    dir, version, dryRun, in.value("m2-dir").map(CliPaths::abs).orElse(null));
        }
        String next;
        try {
            next = ReleaseVersion.next(version, segment);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail(CHIP, e.getMessage());
            return Exit.CONFIG;
        }

        ProjectInfo info = EngineClient.projectInfo(EnginePaths.current(), dir);
        if (info.error() != null) {
            CommandWedge.printFail(CHIP, info.error());
            return Exit.CONFIG;
        }
        String tag = ReleaseTag.render(info.releaseTag(), version);

        Git git = new Git(dir);
        // A checkout with no remote has nowhere to push to.
        String remotes = inGit ? git.output("remote") : null;
        boolean noRemote = inGit && (remotes == null || remotes.isBlank());
        boolean push = inGit && !noPush && !noRemote;
        String top = inGit ? git.output("rev-parse", "--show-toplevel") : null;
        Path ciRoot = top == null || top.isEmpty() ? dir : Path.of(top);
        PublishMode mode = publishMode(publishWord, info, ciRoot, inGit);
        boolean explicitMode = publishWord != null || !info.releasePublish().isEmpty();
        if (mode == PublishMode.CI && noPush && explicitMode) {
            CommandWedge.printFail(CHIP, "--publish ci needs the tag pushed for CI to see it; drop --no-push");
            return Exit.USAGE;
        }
        // Outside git a release is the verb's alone: no clean-tree check, no tag, no commit, no push.
        String refusal = inGit ? preflight(git, tag, manifest) : null;
        if (refusal != null) {
            CommandWedge.printFail(CHIP, refusal);
            return Exit.CONFIG;
        }
        if (startClaim(info, version).join() == RemoteClaim.Verdict.CLAIMED) {
            CommandWedge.printFail(
                    CHIP,
                    info.group() + ":" + info.name() + ":" + version + " is already published to "
                            + info.publishRepository() + "; raise the version in jk.toml");
            return Exit.CONFIG;
        }

        // A release this machine must publish is refused before anything changes when it cannot.
        if (mode == PublishMode.LOCAL) {
            String cannot = localPublishRefusal(info, dir, manifest);
            if (cannot != null) {
                CommandWedge.printFail(CHIP, cannot);
                return Exit.CONFIG;
            }
        }

        if (dryRun) {
            String steps = (inGit ? ", tag " + tag : "") + ", move jk.toml to " + next + (push ? ", push" : "");
            CommandWedge.printOk(
                    CHIP,
                    "Would release " + info.group() + ":" + info.name() + ":" + version + steps + mode.dryRunNote());
            return Exit.SUCCESS;
        }

        int built = installRelease(in, global, dir, destination);
        if (built != 0) return built;

        if (!inGit) {
            if (mode == PublishMode.LOCAL) {
                int published = new PublishCommand().run(publishInvocation(in, dir));
                if (published != 0) {
                    CommandWedge.printFail(
                            CHIP,
                            info.name() + " " + version + " was not published; fix the publish and run"
                                    + " `jk release` again");
                    return published;
                }
            }
            return startOutsideGit(info, dir, manifest, text, version, next);
        }
        return tagAndStart(info, git, dir, manifest, text, version, next, tag, push, mode, in);
    }

    /** Build, test and install the declared version where {@code jk install} would. */
    private static int installRelease(
            Invocation in, GlobalOptions global, Path dir, @Nullable InstallDestination destination)
            throws IOException, InterruptedException {
        BuildFlags flags;
        try {
            flags = BuildFlags.from(in);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail(CHIP, e.getMessage());
            return Exit.CONFIG;
        }
        flags.installSession();
        BuildOptions buildOpts = new BuildOptions();
        buildOpts.skipTests = flags.skipTests();
        InstallCommand install = new InstallCommand(global, buildOpts);
        install.stateDirOverride = in.value("state-dir").map(CliPaths::abs).orElse(null);
        install.binDirOverride = in.value("bin-dir").map(CliPaths::abs).orElse(null);
        install.libDirOverride = in.value("lib-dir").map(CliPaths::abs).orElse(null);
        install.m2DirOverride = in.value("m2-dir").map(CliPaths::abs).orElse(null);
        install.destinationOverride = destination;
        install.jdksDir = CommonOpts.jdksDirValue(in);
        return install.runProjectInstallBuildPlan(dir);
    }

    /** Why this machine cannot publish {@code info}'s release, or {@code null} when it can. */
    private static @Nullable String localPublishRefusal(ProjectInfo info, Path dir, Path manifest) {
        PublishTarget.Resolution resolved = PublishTarget.resolve(info, dir, manifest, null, false, JkDirs::env);
        PublishTarget target = resolved.target();
        if (target == null) return resolved.refusal();
        if (target.lacksCredential()) {
            return "no credential for " + target.describe() + " on this machine; if CI publishes this project,"
                    + " run jk release --publish ci";
        }
        return null;
    }

    /** The options of a publish run from this release's: its global options and test policy, at {@code dir}. */
    private static Invocation publishInvocation(Invocation in, Path dir) {
        Invocation.Builder b = Invocation.builder();
        for (Opt opt : GlobalOptions.globalOpts()) {
            if (!opt.canonicalName().equals("dir")) BuildVerbCommand.copy(in, opt, b);
        }
        BuildVerbCommand.copy(in, CommonOpts.skipTests(), b);
        BuildVerbCommand.copy(in, CommonOpts.jdksDir(), b);
        b.addValue("dir", dir.toString());
        return b.build();
    }

    /**
     * {@code jk publish} on the tag just cut, from a checkout of it: the release commit is no longer
     * HEAD once {@code jk.toml} has moved on. The checkout is a detached worktree beside the user
     * cache, removed afterwards.
     */
    private static int publishTag(Git git, Path dir, String tag, Invocation in)
            throws IOException, InterruptedException {
        String top = git.output("rev-parse", "--show-toplevel");
        if (top == null || top.isEmpty()) {
            CliOutput.err("could not find the repository root to publish " + tag + " from");
            return Exit.CONFIG;
        }
        Path root = Path.of(top).toRealPath();
        Path checkout = JkDirs.cache()
                .resolve("release-checkouts")
                .resolve(root.getFileName() + "-" + tag.replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.createDirectories(checkout.getParent());
        git.run("worktree", "remove", "--force", checkout.toString());
        String added = git.run("worktree", "add", "--detach", checkout.toString(), tag);
        if (added != null) {
            CliOutput.err("could not check out " + tag + " to publish it: " + added);
            return Exit.CONFIG;
        }
        try {
            Path at = checkout.resolve(root.relativize(dir.toRealPath()).toString());
            return new PublishCommand().run(publishInvocation(in, at));
        } finally {
            git.run("worktree", "remove", "--force", checkout.toString());
        }
    }

    /** Outside git: move the manifests to {@code next} by editing them, and relock. Nothing is tagged or committed. */
    private static int startOutsideGit(
            ProjectInfo info, Path dir, Path manifest, String text, String version, String next) throws IOException {
        List<Path> bumped = new ArrayList<>();
        try {
            bump(info, dir, manifest, text, version, next, bumped);
        } catch (IllegalArgumentException | IllegalStateException e) {
            CommandWedge.printFail(
                    CHIP, "released " + version + " but could not move jk.toml to " + next + ": " + e.getMessage());
            return Exit.CONFIG;
        }
        int relocked = relock(dir);
        if (relocked != 0) {
            CommandWedge.printFail(
                    CHIP,
                    "released " + version + " and moved jk.toml to " + next + ", but relocking failed; run `jk lock`");
            return relocked;
        }
        CommandWedge.printOk(
                CHIP,
                "Released " + info.group() + ":" + info.name() + ":" + version + ", next " + next
                        + " (no git here: nothing tagged or committed)");
        return Exit.SUCCESS;
    }

    /** Tag the release commit, move the manifests to {@code next}, commit that, and push when asked. */
    private static int tagAndStart(
            ProjectInfo info,
            Git git,
            Path dir,
            Path manifest,
            String text,
            String version,
            String next,
            String tag,
            boolean push,
            PublishMode mode,
            Invocation in)
            throws IOException, InterruptedException {
        String tagFailure = git.run("tag", "-a", tag, "-m", "Release " + version);
        if (tagFailure != null) {
            CommandWedge.printFail(CHIP, "could not tag " + tag + ": " + tagFailure);
            return Exit.CONFIG;
        }

        List<Path> bumped = new ArrayList<>();
        try {
            bump(info, dir, manifest, text, version, next, bumped);
        } catch (IllegalArgumentException | IllegalStateException e) {
            CommandWedge.printFail(
                    CHIP,
                    "tagged " + tag + " but could not move jk.toml to " + next + ": " + e.getMessage()
                            + "; set the version by hand and commit it");
            return Exit.CONFIG;
        }
        int relocked = relock(dir);
        if (relocked != 0) {
            CommandWedge.printFail(
                    CHIP, "tagged " + tag + " and moved jk.toml to " + next + ", but relocking failed; run `jk lock`");
            return relocked;
        }
        List<String> add = new ArrayList<>(List.of("add", "--"));
        for (Path p : bumped) add.add(dir.relativize(p).toString());
        Path lock = LockPaths.lockFile(LockPaths.lockOwnerDir(dir));
        if (Files.exists(lock)) add.add(dir.relativize(lock).toString());
        String staged = git.run(add.toArray(String[]::new));
        String committed = staged != null ? staged : git.run("commit", "-m", "Start " + next);
        if (committed != null) {
            CommandWedge.printFail(
                    CHIP,
                    "tagged " + tag + " and moved jk.toml to " + next + ", but the commit failed: " + committed
                            + "; commit the version change by hand");
            return Exit.CONFIG;
        }

        if (push) {
            String pushed = git.run("push", "--follow-tags");
            if (pushed != null) {
                CommandWedge.printFail(
                        CHIP,
                        "released " + version + " locally but the push failed: " + pushed
                                + "; run `git push --follow-tags` later");
                return Exit.CONFIG;
            }
        }
        String remotes = push ? null : git.output("remote");
        boolean noRemote = !push && (remotes == null || remotes.isBlank());
        String released =
                "Released " + info.group() + ":" + info.name() + ":" + version + ", tag " + tag + ", next " + next
                        + (push
                                ? ", pushed"
                                : noRemote ? "; no git remote to push to" : "; push with `git push --follow-tags`");
        if (mode == PublishMode.LOCAL) {
            int published = publishTag(git, dir, tag, in);
            if (published != 0) {
                CommandWedge.printFail(
                        CHIP,
                        "released " + version + " as " + tag + (push ? " and pushed it" : "")
                                + " but the upload failed; run `jk publish` on " + tag + " to finish it");
                return published;
            }
        }
        CommandWedge.printOk(CHIP, released);
        if (mode == PublishMode.CI && push) CliOutput.out("pushed " + tag + "; CI publishes it");
        return Exit.SUCCESS;
    }

    /**
     * The release {@code jk release} cut at HEAD: the tag on HEAD's parent when HEAD is the commit
     * that moved {@code jk.toml} on ({@code bumped}), or on HEAD itself when the move was never
     * committed.
     */
    record CutRelease(String version, String tag, boolean bumped) {}

    /** The release cut at HEAD, or {@code null} when HEAD is not where {@code jk release} left a tree. */
    static @Nullable CutRelease cutAtHead(Git git, String tagTemplate, String declared) {
        String atHead = ReleaseTag.render(tagTemplate, declared);
        if (tagged(git, atHead, "HEAD")) return new CutRelease(declared, atHead, false);
        String parentManifest = git.output("show", "HEAD^:./" + ManifestPaths.MANIFEST);
        if (parentManifest == null) return null;
        String released = ReleaseVersion.declared(parentManifest).orElse(null);
        if (released == null || released.equals(declared)) return null;
        String tag = ReleaseTag.render(tagTemplate, released);
        return tagged(git, tag, "HEAD^") ? new CutRelease(released, tag, true) : null;
    }

    private static boolean tagged(Git git, String tag, String rev) {
        String tagged = git.output("rev-parse", "-q", "--verify", "refs/tags/" + tag + "^{commit}");
        return tagged != null && tagged.equals(git.output("rev-parse", "-q", "--verify", rev + "^{commit}"));
    }

    /** Why {@code cut} is out of this checkout's hands, or {@code null} when it can be taken back. */
    static @Nullable String published(Git git, CutRelease cut) {
        String remotes = git.output("remote");
        if (remotes == null) return "git remote failed here";
        for (String remote :
                remotes.lines().map(String::strip).filter(r -> !r.isEmpty()).toList()) {
            String found = git.output("ls-remote", "--tags", remote, "refs/tags/" + cut.tag());
            if (found == null) return "could not ask " + remote + " for tag " + cut.tag() + "; try again online";
            if (!found.isEmpty()) return "tag " + cut.tag() + " is on " + remote + "; a pushed release stays";
        }
        if (cut.bumped()) {
            String pushed = git.output("branch", "-r", "--contains", "HEAD");
            if (pushed == null) return "git branch failed here";
            if (!pushed.isEmpty())
                return "the version commit is on "
                        + pushed.lines().findFirst().orElse("").strip();
        }
        return null;
    }

    /**
     * {@code jk release --undo}: take back the release {@code jk release} cut at HEAD and never
     * pushed or published. The version commit goes (HEAD, when it is that commit), then the
     * release's installed jars and POMs, then the record of the install before the live one when it
     * was that release, then the tag, so a run cut short can be run again. Refused while the
     * installed JumpKick runs the release's own jars: {@code jk self rollback} first.
     */
    private int withdraw(Path dir, String declared, boolean dryRun, @Nullable Path m2Dir) throws IOException {
        ProjectInfo info = EngineClient.projectInfo(EnginePaths.current(), dir);
        if (info.error() != null) {
            CommandWedge.printFail(CHIP, info.error());
            return Exit.CONFIG;
        }
        Git git = new Git(dir);
        String dirty = git.output("status", "--porcelain");
        if (dirty == null || !dirty.isEmpty()) {
            CommandWedge.printFail(
                    CHIP,
                    dirty == null
                            ? "not inside a git work tree"
                            : "the working tree has uncommitted changes; commit or stash them first:\n" + dirty);
            return Exit.CONFIG;
        }
        CutRelease cut = cutAtHead(git, info.releaseTag(), declared);
        if (cut == null) {
            CommandWedge.printFail(CHIP, "no release tag on HEAD or on the version commit before it");
            return Exit.CONFIG;
        }
        String refusal = published(git, cut);
        if (refusal == null && startClaim(info, cut.version()).join() == RemoteClaim.Verdict.CLAIMED) {
            refusal = cut.version() + " is published to " + info.publishRepository();
        }
        if (refusal != null) {
            CommandWedge.printFail(CHIP, refusal);
            return Exit.CONFIG;
        }
        List<Coordinate> coordinates = new ArrayList<>();
        for (ProjectInfo module : modules(dir, info)) {
            if (module.version().equals(declared)) {
                coordinates.add(Coordinate.of(module.group(), module.name(), cut.version()));
            }
        }
        EngineInstall engine = EngineInstall.current();
        if (pinsAny(engine.shelfFile(), coordinates)) {
            CommandWedge.printFail(
                    CHIP, "the installed JumpKick is " + cut.version() + "; run `jk self rollback` first");
            return Exit.CONFIG;
        }
        if (dryRun) {
            CommandWedge.printOk(
                    CHIP,
                    "Would delete tag " + cut.tag() + (cut.bumped() ? ", drop the version commit" : "")
                            + " and uninstall " + coordinates.size() + " module(s) at " + cut.version());
            return Exit.SUCCESS;
        }
        if (cut.bumped()) {
            String reset = git.run("reset", "-q", "--keep", "HEAD^");
            if (reset != null) {
                CommandWedge.printFail(CHIP, "could not drop the version commit: " + reset);
                return Exit.CONFIG;
            }
        }
        InstalledRelease.Withdrawn withdrawn =
                InstalledRelease.withdraw(JkStores.store(), M2Dirs.repository(m2Dir), coordinates);
        if (pinsAny(engine.previousShelfFile(), coordinates)) engine.forgetPrevious();
        String untagged = git.run("tag", "-d", cut.tag());
        if (untagged != null) {
            CommandWedge.printFail(CHIP, "could not delete tag " + cut.tag() + ": " + untagged);
            return Exit.CONFIG;
        }
        for (Path left : withdrawn.foreign()) {
            CliOutput.err("left " + left + ": JumpKick did not install what is there");
        }
        CommandWedge.printOk(
                CHIP,
                "Took back " + cut.version() + ": tag " + cut.tag() + " deleted, jk.toml at " + cut.version() + ", "
                        + coordinates.size() + " module(s) uninstalled");
        return Exit.SUCCESS;
    }

    /** {@code root} and, for a workspace, every member it lists. */
    private static List<ProjectInfo> modules(Path dir, ProjectInfo root) throws IOException {
        List<ProjectInfo> out = new ArrayList<>(List.of(root));
        if (!root.workspaceRoot()) return out;
        for (String moduleDir : root.moduleDirs()) {
            Path member = Path.of(moduleDir);
            if (member.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize())) continue;
            ProjectInfo info = EngineClient.projectInfo(EnginePaths.current(), member);
            if (info.error() == null) out.add(info);
        }
        return out;
    }

    /** Whether the shelf manifest at {@code file} pins any of {@code coordinates}. */
    private static boolean pinsAny(Path file, List<Coordinate> coordinates) {
        return ShelfManifest.read(file)
                .map(m -> coordinates.stream().anyMatch(c -> m.sha(c.toGav()).isPresent()))
                .orElse(false);
    }

    /** The reason this tree cannot release, or {@code null} when it can. */
    private static @Nullable String preflight(Git git, String tag, Path manifest) {
        String inside = git.output("rev-parse", "--is-inside-work-tree");
        if (!Boolean.parseBoolean(inside)) {
            return "not inside a git work tree";
        }
        String dirty = git.output("status", "--porcelain");
        if (dirty == null) return "git status failed here.";
        if (!dirty.isEmpty()) {
            return "the working tree has uncommitted changes; commit or stash them first:\n" + dirty;
        }
        if (git.output("rev-parse", "-q", "--verify", "refs/tags/" + tag) != null) {
            return "tag " + tag + " already exists; raise the version in jk.toml or delete the tag";
        }
        return null;
    }

    /** Ask the {@code [publish]} repository, in the background, whether the version is taken. */
    private static CompletableFuture<RemoteClaim.Verdict> startClaim(ProjectInfo info, String version) {
        String repo = info.publishRepository();
        if (!repo.startsWith("http://") && !repo.startsWith("https://")) {
            return CompletableFuture.completedFuture(RemoteClaim.Verdict.UNKNOWN);
        }
        URI uri = URI.create(repo);
        return RemoteClaim.start(
                RepoTransports.forUrl(uri, Http.failFast()),
                uri,
                RepoCredential.ANONYMOUS,
                info.group(),
                info.name(),
                version,
                Duration.ofSeconds(5));
    }

    /** Move every manifest that declared {@code version} to {@code next}; the files changed go in {@code bumped}. */
    private static void bump(
            ProjectInfo info, Path dir, Path manifest, String text, String version, String next, List<Path> bumped)
            throws IOException {
        AtomicWrites.replace(manifest, ReleaseVersion.rewrite(text, next));
        bumped.add(manifest);
        Path base = info.workspaceRootDir().isEmpty() ? dir : Path.of(info.workspaceRootDir());
        for (var file : info.releaseFiles().entrySet()) {
            Path path = base.resolve(file.getKey());
            try {
                Files.writeString(
                        path,
                        ReleaseVersion.rewriteGroup(
                                Files.readString(path, StandardCharsets.UTF_8), file.getValue(), next));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(file.getKey() + ": " + e.getMessage());
            }
            bumped.add(path);
        }
        if (!info.workspaceRoot()) return;
        for (String moduleDir : info.moduleDirs()) {
            Path memberManifest = ManifestPaths.manifestIn(Path.of(moduleDir));
            if (memberManifest.equals(manifest) || !Files.isRegularFile(memberManifest)) continue;
            String member = Files.readString(memberManifest, StandardCharsets.UTF_8);
            if (ReleaseVersion.declared(member).filter(version::equals).isPresent()) {
                AtomicWrites.replace(memberManifest, ReleaseVersion.rewrite(member, next));
                bumped.add(memberManifest);
            }
        }
    }

    private static int relock(Path dir) throws IOException {
        EngineRequests.LockOutcome outcome = EngineClient.runLock(
                EnginePaths.current(),
                new EngineRequests.LockRequest(
                        LockPaths.lockOwnerDir(dir),
                        JkDirs.cache(),
                        List.of(),
                        false,
                        false,
                        null,
                        SessionContext.current().offline(),
                        false,
                        false),
                new EngineRequests.LockHandler() {
                    @Override
                    public BuildPlanListener onModuleStart(String moduleDir, String coord, List<Task> steps) {
                        return new BuildPlanListener() {};
                    }
                });
        for (String err : outcome.errors()) CommandWedge.printFail("Lock", err);
        return outcome.exitCode();
    }

    /**
     * Runs {@code git} in the project directory, both of its streams drained at once so a chatty
     * hook cannot fill one pipe while the other is read. A command that runs hooks ({@code commit},
     * {@code push}) gets {@link #HOOK_CAP}; every other one {@link #CAP}.
     */
    record Git(Path dir) {

        /** Whether {@code dir} is in a git checkout: an ancestor holds a {@code .git}, read without running git. */
        static boolean present(Path dir) {
            for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
                if (Files.exists(d.resolve(".git"))) return true;
            }
            return false;
        }

        static final Duration CAP = Duration.ofSeconds(120);

        static final Duration HOOK_CAP = Duration.ofHours(1);

        /** Trimmed stdout of a successful command, else {@code null}. */
        @Nullable
        String output(String... args) {
            SecretCommand.Result r = exec(args);
            return r.exit() == 0 ? r.stdout().strip() : null;
        }

        /** {@code null} on success, else git's own explanation. */
        @Nullable
        String run(String... args) {
            SecretCommand.Result r = exec(args);
            return r.exit() == 0 ? null : (r.stderr().isBlank() ? r.stdout() : r.stderr()).strip();
        }

        private SecretCommand.Result exec(String... args) {
            List<String> command = GitCommand.of("-C", dir.toString());
            command.addAll(List.of(args));
            boolean hooks = args.length > 0 && (args[0].equals("commit") || args[0].equals("push"));
            try {
                return SecretCommand.REAL.run(command, null, hooks ? HOOK_CAP : CAP);
            } catch (IOException e) {
                return new SecretCommand.Result(1, "", String.valueOf(e.getMessage()));
            }
        }
    }
}
