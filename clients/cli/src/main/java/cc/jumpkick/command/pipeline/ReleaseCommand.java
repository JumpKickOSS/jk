// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.BuildOptions;
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
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.release.ReleaseTag;
import cc.jumpkick.repo.RemoteClaim;
import cc.jumpkick.repo.RepoTransports;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
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
import java.util.concurrent.TimeUnit;
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
    public String description() {
        return "Tag the declared version and move jk.toml to the next one";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(List.of(
                Opt.flag("After the release, move the major version (1.4.7 → 2.0.0).", "--major"),
                Opt.flag("After the release, move the minor version (1.4.7 → 1.5.0).", "--minor"),
                Opt.flag("After the release, move the patch version (1.4.7 → 1.4.8). The default.", "--patch"),
                Opt.flag(
                        "Publish the release (jk publish, to the [publish] target; its options are accepted) before it is tagged.",
                        "--publish"),
                Opt.flag("Push the branch and the tag to the branch's upstream when the release succeeded.", "--push"),
                Opt.flag("Check everything and print what would happen; change nothing.", "--dry-run"),
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
            CommandWedge.printFail(CHIP, "no jk.toml here — run `jk release` inside a project");
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

        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        String version = ReleaseVersion.declared(text).orElse(null);
        if (version == null) {
            CommandWedge.printFail(
                    CHIP,
                    "this jk.toml declares no version of its own — it inherits the workspace's. Run `jk release`"
                            + " in the directory that declares `version = \"...\"`.");
            return Exit.CONFIG;
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
                            + info.publishRepository() + ". Raise the version in jk.toml, or release the next one.");
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
                        info.name() + " " + version + " is installed locally but was not published, and nothing is"
                                + " tagged. Fix the publish and run `jk release` again.");
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
                            + ". Set the version by hand and commit it.");
            return Exit.CONFIG;
        }
        int relocked = relock(dir);
        if (relocked != 0) {
            CommandWedge.printFail(
                    CHIP,
                    "tagged " + tag + " and moved jk.toml to " + next + ", but relocking failed; run `jk lock`"
                            + " and commit the lock.");
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
                            + ". Commit the version change by hand.");
            return Exit.CONFIG;
        }

        if (push) {
            String pushed = git.run("push", "--follow-tags");
            if (pushed != null) {
                CommandWedge.printFail(
                        CHIP,
                        "released " + version + " locally (tag " + tag + ", then " + next + ") but the push failed: "
                                + pushed + ". Run `git push --follow-tags` when the remote is reachable.");
                return Exit.CONFIG;
            }
        }
        CommandWedge.printOk(
                CHIP,
                "Released " + info.group() + ":" + info.name() + ":" + version + " — tag " + tag + ", jk.toml at "
                        + next + (push ? ", pushed" : ". Push with `git push --follow-tags`."));
        return Exit.SUCCESS;
    }

    /** The reason this tree cannot release, or {@code null} when it can. */
    private static @Nullable String preflight(Git git, String tag, Path manifest) {
        String inside = git.output("rev-parse", "--is-inside-work-tree");
        if (!Boolean.parseBoolean(inside)) {
            return "not inside a git work tree: a release is a tag on a commit.";
        }
        String dirty = git.output("status", "--porcelain");
        if (dirty == null) return "git status failed here.";
        if (!dirty.isEmpty()) {
            return "the working tree has uncommitted changes; commit or stash them first:\n" + dirty;
        }
        if (git.output("rev-parse", "-q", "--verify", "refs/tags/" + tag) != null) {
            return "tag " + tag + " already exists. Raise the version in jk.toml or delete the tag.";
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
        Files.writeString(manifest, ReleaseVersion.rewrite(text, next));
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
                Files.writeString(memberManifest, ReleaseVersion.rewrite(member, next));
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

    /** Runs {@code git} in the project directory. */
    private record Git(Path dir) {

        /** Trimmed stdout of a successful command, else {@code null}. */
        @Nullable
        String output(String... args) {
            ProcessResult r = exec(args);
            return r.exit == 0 ? r.out.strip() : null;
        }

        /** {@code null} on success, else git's own explanation. */
        @Nullable
        String run(String... args) {
            ProcessResult r = exec(args);
            return r.exit == 0 ? null : (r.err.isBlank() ? r.out : r.err).strip();
        }

        private ProcessResult exec(String... args) {
            List<String> command = new ArrayList<>(args.length + 1);
            command.add("git");
            command.addAll(List.of(args));
            try {
                Process p = new ProcessBuilder(command).directory(dir.toFile()).start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String errText = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(120, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new ProcessResult(1, out, "git timed out");
                }
                return new ProcessResult(p.exitValue(), out, errText);
            } catch (IOException e) {
                return new ProcessResult(1, "", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ProcessResult(1, "", "interrupted");
            }
        }
    }

    private record ProcessResult(int exit, String out, String err) {}
}
