// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.W_RESOURCES;

import cc.jumpkick.config.RequestScope;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.host.DeterministicProperties;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The {@code build-info} step: {@code [build-info]} writes {@code git.properties} into the classes
 * tree after the static resources land, and {@code META-INF/build-info.properties} beside it when
 * the module is a Spring Boot application, so every jar shape carries them. The files are rendered
 * from the checkout's {@code HEAD}, its dirty state and the manifest, and written only when the
 * rendering differs from what is on disk: an unchanged tree is a skip, a new commit rewrites the
 * file and nothing else, and {@code package-jar} re-keys on the classes tree it changed.
 *
 * <p>Time keys read the {@code HEAD} commit's time unless {@code time = "build"} asks for the wall
 * clock, so one commit's builds are byte-identical. Timestamps are spelled the way Boot's {@code
 * GitProperties} and {@code BuildProperties} parse them ({@code yyyy-MM-dd'T'HH:mm:ssZ}, UTC).
 */
final class PlannerBuildInfo {

    /** Diagnostic code for the step's warnings: the step's own name. */
    static final String CODE = TaskNames.BUILD_INFO;

    /** Where Boot's {@code BuildProperties} reads its keys from. */
    static final String BOOT_ENTRY = "META-INF/build-info.properties";

    static final String NOT_A_REPOSITORY = "[build-info] is declared but the module is not inside a git checkout"
            + " with a commit; no git.properties was written";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** One worktree description per checkout root per request, however many modules share it. */
    private static final Object WORKTREES = new Object();

    private PlannerBuildInfo() {}

    static Task step(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        return Task.builder(TaskNames.BUILD_INFO)
                .stage(BuildLogicAnchor.AFTER_RESOURCES.stage())
                .label("Build info")
                .kind(TaskKind.IO)
                .requires(TaskNames.COPY_RESOURCES)
                .weight(W_RESOURCES)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildBlock.BuildInfo info = project.build().buildInfo();
                    if (info == null)
                        throw new IllegalStateException("build-info planned without a [build-info] table");
                    Path classes = ctx.require(MAIN_CLASSES);
                    boolean changed = write(in.dir(), project, info, classes, Clock.SYSTEM, ctx::warn);
                    ctx.label(changed ? "write " + info.file() : info.file() + " up-to-date");
                    if (!changed) ctx.cached();
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Render the module's build-info files and write the ones whose bytes differ from the classes
     * tree. Returns true when any file was written or removed. Outside a checkout the files are
     * removed, so a tree exported without its {@code .git} does not ship a stale commit, and the
     * step warns once.
     */
    static boolean write(
            Path moduleDir,
            JkBuild project,
            BuildBlock.BuildInfo info,
            Path classes,
            Clock clock,
            BiConsumer<String, String> warn)
            throws IOException {
        Optional<GitFetcher.Worktree> worktree = worktree(moduleDir);
        if (worktree.isEmpty()) {
            warn.accept(CODE, NOT_A_REPOSITORY);
            boolean removed = Files.deleteIfExists(classes.resolve(info.file()));
            if (project.isSpringBoot()) removed |= Files.deleteIfExists(classes.resolve(BOOT_ENTRY));
            return removed;
        }
        boolean changed = false;
        for (Map.Entry<String, byte[]> e :
                render(project, info, worktree.get(), clock).entrySet()) {
            changed |= writeIfChanged(classes.resolve(e.getKey()), e.getValue());
        }
        return changed;
    }

    /** True when the files {@link #write} would produce differ from what {@code classes} holds. */
    static boolean outOfSync(Path moduleDir, JkBuild project, BuildBlock.BuildInfo info, Path classes, Clock clock)
            throws IOException {
        Optional<GitFetcher.Worktree> worktree = worktree(moduleDir);
        if (worktree.isEmpty()) return Files.exists(classes.resolve(info.file()));
        for (Map.Entry<String, byte[]> e :
                render(project, info, worktree.get(), clock).entrySet()) {
            Path target = classes.resolve(e.getKey());
            if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Every file the table writes, by its path inside the classes tree, rendered deterministically. */
    static Map<String, byte[]> render(
            JkBuild project, BuildBlock.BuildInfo info, GitFetcher.Worktree worktree, Clock clock) {
        Instant buildTime = info.buildTime() ? clock.instant() : worktree.commitTime();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(info.file(), bytes(gitProperties(project, worktree, buildTime)));
        if (project.isSpringBoot()) files.put(BOOT_ENTRY, bytes(bootProperties(project, buildTime)));
        return files;
    }

    /** The {@code git.*} keys Boot's {@code GitProperties} and the git-commit-id consumers read. */
    static Map<String, String> gitProperties(JkBuild project, GitFetcher.Worktree worktree, Instant buildTime) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("git.branch", worktree.branch());
        out.put("git.build.time", TIME.format(buildTime));
        out.put("git.build.version", project.project().version());
        out.put("git.commit.id", worktree.sha());
        out.put("git.commit.id.abbrev", worktree.abbrev());
        out.put("git.commit.time", TIME.format(worktree.commitTime()));
        out.put("git.dirty", Boolean.toString(worktree.dirty()));
        worktree.nearestTag().ifPresent(tag -> out.put("git.closest.tag.name", tag));
        return out;
    }

    /** The {@code build.*} keys Boot's {@code BuildProperties} reads. */
    static Map<String, String> bootProperties(JkBuild project, Instant buildTime) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("build.artifact", project.project().name());
        out.put("build.group", project.project().group());
        out.put("build.name", project.project().name());
        out.put("build.time", TIME.format(buildTime));
        out.put("build.version", project.project().version());
        return out;
    }

    private static byte[] bytes(Map<String, String> entries) {
        return DeterministicProperties.render(entries).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean writeIfChanged(Path target, byte[] bytes) throws IOException {
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) return false;
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return true;
    }

    /**
     * The checkout containing {@code moduleDir}, read once per request for every module under the
     * same checkout root. The root is the nearest ancestor holding a {@code .git} directory or
     * file, so two worktrees of one repository are two entries.
     */
    static Optional<GitFetcher.Worktree> worktree(Path moduleDir) throws IOException {
        Path root = checkoutRoot(moduleDir);
        if (root == null) return Optional.empty();
        Map<Path, Optional<GitFetcher.Worktree>> memo =
                RequestScope.current().get(WORKTREES, k -> new ConcurrentHashMap<>());
        Optional<GitFetcher.Worktree> known = memo.get(root);
        if (known != null) return known;
        Optional<GitFetcher.Worktree> read = new GitFetcher(JkDirs.store().resolve("git")).describeWorktree(root);
        memo.put(root, read);
        return read;
    }

    private static @Nullable Path checkoutRoot(Path dir) {
        for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) return d;
        }
        return null;
    }
}
