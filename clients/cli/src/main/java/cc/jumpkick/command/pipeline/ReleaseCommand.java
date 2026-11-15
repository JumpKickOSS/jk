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
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk release} — cut the release the manifest declares: build and test, install the version
 * once, optionally publish it, tag the commit, then move {@code jk.toml} to the next version in a
 * commit of its own. Nothing is pushed unless {@code --push} says so.
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
        List<Opt> opts = new ArrayList<>(List.of(
                Opt.flag("After the release, move the major version (1.4.7 → 2.0.0).", "--major"),
                Opt.flag("After the release, move the minor version (1.4.7 → 1.5.0).", "--minor"),
                Opt.flag("After the release, move the patch version (1.4.7 → 1.4.8). The default.", "--patch"),
                Opt.flag("Publish to the [publish] target before tagging; takes jk publish options.", "--publish"),
                Opt.flag("Push the branch and tag to its upstream after the release.", "--push"),
                Opt.flag("Check everything and print what would happen; change nothing.", "--dry-run"),
                Opt.flag(
                        "Take back a release cut here and not pushed: its tag, the version commit, its installed jars.",
                        "--undo"),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<dir>", "Override the lib directory.", "--lib-dir").hide(),
                Opt.value("<dir>", "Override the local Maven repo root (~/.m2).", "--m2-dir")
                        .hide()));
        opts.addAll(BuildFlags.options(WorkspaceTarget.INSTALL.tests(), BuildFlags.Affected.MODULES));
        Set<String> taken = new HashSet<>();
        taken.add("tag"); // a release names its own tag
        for (Opt o : opts) taken.addAll(o.names());
        for (Opt o : new PublishCommand().options()) {
            // Publish's options ride along unlisted (the help names --publish); a spelling a release option uses stays
            // its own.
            List<String> free =
                    o.names().stream().filter(n -> !taken.contains(n)).toList();
            if (free.isEmpty() || taken.contains(o.canonicalName())) continue;
            taken.addAll(free);
            opts.add(new Opt(
                    free,
                    o.paramLabel(),
                    o.description(),
                    o.takesValue(),
                    o.repeatable(),
                    o.split(),
                    true,
                    o.negatable(),
                    o.required(),
                    o.fallbackValue(),
                    o.aliases()));
        }
        return opts;
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        // A release installs the declared version, never a snapshot: leave the session as found.
        Session before = SessionContext.current();
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
        boolean publish = in.isSet("publish");
        boolean push = in.isSet("push");
        boolean undo = in.isSet("undo");
        if (undo && (publish || push || segments > 0)) {
            CommandWedge.printFail(CHIP, "--undo takes no --publish, --push or version segment");
            return Exit.USAGE;
        }

        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        String version = ReleaseVersion.declared(text).orElse(null);
        if (version == null) {
            CommandWedge.printFail(CHIP, "this jk.toml inherits its version; run `jk release` where `version` is set");
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
        String refusal = preflight(git, tag, manifest);
        if (refusal != null) {
            CommandWedge.printFail(CHIP, refusal);
            return Exit.CONFIG;
        }
        CompletableFuture<RemoteClaim.Verdict> claim = startClaim(info, version);
        RemoteClaim.Verdict verdict = claim.join();
        if (verdict == RemoteClaim.Verdict.CLAIMED) {
            CommandWedge.printFail(
                    CHIP,
                    info.group() + ":" + info.name() + ":" + version + " is already published to "
                            + info.publishRepository() + "; raise the version in jk.toml");
            return Exit.CONFIG;
        }

        if (dryRun) {
            CommandWedge.printOk(
                    CHIP,
                    "Would release " + info.group() + ":" + info.name() + ":" + version + (publish ? ", publish" : "")
                            + ", tag " + tag + ", move jk.toml to " + next + (push ? ", push" : ""));
            return Exit.SUCCESS;
        }

        BuildFlags flags;
        try {
            flags = BuildFlags.from(in);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail(CHIP, e.getMessage());
            return Exit.CONFIG;
        }
        flags.installSession();
        // The build ships the declared version: filtered resources stamp it as declared.
        SessionContext.install(SessionContext.current().withRelease(true));
        BuildOptions buildOpts = new BuildOptions();
        buildOpts.skipTests = flags.skipTests();
        InstallCommand install = new InstallCommand(global, buildOpts);
        install.cacheDirOverride = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        install.stateDirOverride = in.value("state-dir").map(CliPaths::abs).orElse(null);
        install.binDirOverride = in.value("bin-dir").map(CliPaths::abs).orElse(null);
        install.libDirOverride = in.value("lib-dir").map(CliPaths::abs).orElse(null);
        install.m2DirOverride = in.value("m2-dir").map(CliPaths::abs).orElse(null);
        install.jdksDir = CommonOpts.jdksDirValue(in);
        int built = install.runProjectInstallBuildPlan(dir);
        if (built != 0) return built;

        if (publish) {
            int published = new PublishCommand().run(in);
            if (published != 0) {
                CommandWedge.printFail(
                        CHIP,
                        info.name() + " " + version + " was not published or tagged; fix the publish and run"
                                + " `jk release` again");
                return published;
            }
        }

        return tagAndStart(info, git, dir, manifest, text, version, next, tag, push);
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
            boolean push)
            throws IOException {
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
        CommandWedge.printOk(
                CHIP,
                "Released " + info.group() + ":" + info.name() + ":" + version + ", tag " + tag + ", next " + next
                        + (push ? ", pushed" : "; push with `git push --follow-tags`"));
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
