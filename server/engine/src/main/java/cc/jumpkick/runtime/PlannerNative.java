// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerSupport.assemblyDependencyJars;
import static cc.jumpkick.runtime.PlannerSupport.restorePackaged;
import static cc.jumpkick.runtime.PlannerSupport.storePackaged;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.ReachabilityMetadata;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.tool.GraalHomeLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * native-image tail and Graal home resolution.
 */
public final class PlannerNative {

    private PlannerNative() {}

    public static Task nativeStep(
            Path dir,
            Path cache,
            Path lockFile,
            @Nullable Path jdksDir,
            @Nullable Path graalHome,
            @Nullable String mainOverride,
            List<String> extraArgs) {
        return nativeStep(dir, cache, lockFile, jdksDir, graalHome, mainOverride, extraArgs, true);
    }

    /**
     * @param allowShared when {@code false} ({@code jk native}), missing / several mains fail
     *     instead of emitting a {@code --shared} library
     */
    public static Task nativeStep(
            Path dir,
            Path cache,
            Path lockFile,
            @Nullable Path jdksDir,
            @Nullable Path graalHome,
            @Nullable String mainOverride,
            List<String> extraArgs,
            boolean allowShared) {
        // Install / native plans never run under verify's ephemeral scratch — persist.
        final boolean persist = true;
        List<String> extra = extraArgs == null ? List.of() : extraArgs;
        return Task.builder(TaskNames.NATIVE_IMAGE)
                .stage(BuildStage.NATIVE)
                .label("Native")
                .kind(TaskKind.IO)
                .requires(TaskNames.PACKAGE_JAR)
                .weight(() -> EffortWeights.nativeWeight(dir))
                // Ease the weight slice over expected wall while Graal stages tick sparsely —
                // without this the bar sits near 100% for most of a multi-minute native-image.
                .interpolated()
                .ticks(10) // preamble(1) + 8 native-image stages + done(1)
                .execute(ctx -> runNativeImage(
                        ctx, dir, cache, lockFile, jdksDir, graalHome, mainOverride, extra, allowShared, persist))
                .build();
    }

    /** The step body: preflight, main class, output, args, classpath, metadata, cache, run, store. */
    private static void runNativeImage(
            TaskContext ctx,
            Path dir,
            Path cache,
            Path lockFile,
            @Nullable Path jdksDir,
            @Nullable Path graalHome,
            @Nullable String mainOverride,
            List<String> extra,
            boolean allowShared,
            boolean persist)
            throws Exception {
        Path javaHome = preflightNativeImageHome(ctx, graalHome, dir, jdksDir);
        JkBuild project = ctx.require(PROJECT);
        JkBuild.NativeConfig nativeCfg = project.nativeConfigOpt()
                .orElseGet(() ->
                        new JkBuild.NativeConfig(null, null, List.of(), null, JkBuild.NativeMode.SUPPORTED, null));
        BuildLayout layout = ctx.require(LAYOUT);
        Path mainJar = layout.mainJar();
        if (!Files.exists(mainJar)) {
            ctx.error("native", "jar not found at " + mainJar);
            throw new RuntimeException("missing main jar for native-image");
        }
        String mainClass = resolveMainClass(ctx, project, dir, layout, nativeCfg, mainOverride, allowShared);
        boolean shared = mainClass == null;
        Path out = outputPath(layout, nativeCfg, shared);
        Files.createDirectories(out.getParent());
        Path frameworkSources = frameworkSources(ctx, project, dir, cache, layout);
        List<String> allArgs = imageArgs(ctx, project, dir, nativeCfg, extra, frameworkSources);
        List<Path> classpath = imageClasspath(project, dir, cache, lockFile, layout, mainJar);
        allArgs = withReachabilityMetadata(ctx, dir, project, layout, lockFile, javaHome, frameworkSources, allArgs);
        ImageKey key = imageKey(
                javaHome, classpath, allArgs, mainClass, shared, out, frameworkSources, trainReachabilityDir(layout));
        if (!shared && restorePackaged(cache, key.key(), out.getParent())) {
            // Shrink only: cache restore is a token touch. Never reweight *up* mid-run
            // (bar must not jump; accurate native weight is reserved up front).
            ctx.reweight(EffortWeights.RESTORE);
            ctx.label(out.getFileName() + " up-to-date");
            ctx.cached();
            ctx.progress(1);
            return;
        }
        reserveNativeWeight(ctx, dir, classpath, out);
        runDriver(ctx, project, dir, layout, javaHome, frameworkSources, allArgs, classpath, mainClass, out, shared);
        // Final tick: completes the last native-image step (or the only tick
        // when no progress headers were emitted).
        ctx.progress(1);
        if (!shared) {
            if (!Files.isRegularFile(out)) {
                throw new IOException("native-image reported success but produced no binary at " + out);
            }
            storePackaged(cache, key.task(), key.key(), key.tokens(), out.getParent(), List.of(out), persist);
        }
    }

    /**
     * Fail-fast: verify native-image is available before compilation has already run and the user
     * has waited for potentially minutes. Resolution: the client-resolved home → the request's
     * $GRAALVM_HOME → the installed Graal the CLI would have picked (spec, lock pin, jk jdk graal
     * pointer, policy) → project JDK → running JVM. jk build, jk install and jk native all ship the
     * first for every module that links a native image; a build submitted over HTTP or MCP ships
     * none, and the third tier is what keeps it from linking against whatever the daemon's shell
     * knew.
     */
    private static Path preflightNativeImageHome(
            TaskContext ctx, @Nullable Path graalHome, Path dir, @Nullable Path jdksDir) throws Exception {
        Path javaHomeEarly = resolveNativeImageHome(
                graalHome, dir, jdksDir, ctx.require(PROJECT).graal());
        if (cc.jumpkick.tool.NativeImageDriver.resolve(javaHomeEarly).isEmpty()) {
            ctx.error("native", Errors.text(cc.jumpkick.tool.NativeImageDriver.notFoundError(javaHomeEarly)));
            throw new RuntimeException("native-image not found");
        }
        return javaHomeEarly;
    }

    /**
     * Resolution order: --main CLI flag > [native].main > [application].main. A resolvable main →
     * executable; none → shared library (--shared) on jk build, returned as null. jk native requires
     * a unique main (allowShared=false).
     */
    private static @Nullable String resolveMainClass(
            TaskContext ctx,
            JkBuild project,
            Path dir,
            BuildLayout layout,
            JkBuild.NativeConfig nativeCfg,
            @Nullable String mainOverride,
            boolean allowShared)
            throws Exception {
        String mainClass = (mainOverride != null && !mainOverride.isBlank())
                ? mainOverride
                : (nativeCfg.mainClass() != null ? nativeCfg.mainClass() : project.mainClass());
        if (mainClass == null || mainClass.isBlank()) {
            boolean scan = !allowShared
                    || PluginBuild.shape(project, dir).map(sh -> sh.mainScan()).orElse(false);
            if (scan) {
                try {
                    mainClass = cc.jumpkick.layout.MainClassScanner.scanUnique(layout.classesDir());
                } catch (cc.jumpkick.layout.MainClassScanner.AmbiguousMainException e) {
                    ctx.error("native", cc.jumpkick.layout.NativePreflight.MANY_MAINS);
                    throw new RuntimeException(cc.jumpkick.layout.NativePreflight.MANY_MAINS);
                } catch (cc.jumpkick.layout.MainClassScanner.NoMainFoundException e) {
                    if (!allowShared) {
                        ctx.error("native", cc.jumpkick.layout.NativePreflight.NO_MAIN);
                        throw new RuntimeException(cc.jumpkick.layout.NativePreflight.NO_MAIN);
                    }
                    mainClass = null;
                }
            }
        }
        if (mainClass == null || mainClass.isBlank()) {
            if (!allowShared) {
                ctx.error("native", cc.jumpkick.layout.NativePreflight.NO_MAIN);
                throw new RuntimeException(cc.jumpkick.layout.NativePreflight.NO_MAIN);
            }
            return null;
        }
        return mainClass;
    }

    /**
     * Output path: [native].name overrides the artifact-derived name. Executable →
     * target/<name>[.exe]; library → target/lib<name> (native-image appends the platform
     * extension.so/.dylib/.dll and emits C headers).
     */
    private static Path outputPath(BuildLayout layout, JkBuild.NativeConfig nativeCfg, boolean shared) {
        if (shared) {
            if (nativeCfg.name() != null) {
                String nm = nativeCfg.name();
                return layout.moduleTargetDir().resolve(nm.startsWith("lib") ? nm : "lib" + nm);
            }
            return layout.nativeLibrary();
        }
        // Includes [native].name and the Windows .exe suffix — the file
        // native-image writes, which the action cache stores.
        return layout.nativeBinary();
    }

    /**
     * The native-image sources a framework computed for its own invocation, or null when jk builds
     * the image from the classpath. A framework that computed its own invocation gets none of jk's
     * automatic additions. Its list is complete by construction — Quarkus even passes
     * --exclude-config to suppress library metadata it does not want, and layering the community
     * metadata repository on top of that reintroduces exactly what it excluded. `[native] args` and
     * CLI extras still apply: those are the user speaking, not jk guessing.
     */
    private static @Nullable Path frameworkSources(
            TaskContext ctx, JkBuild project, Path dir, Path cache, BuildLayout layout) throws Exception {
        Path frameworkSources = nativeImageSourcesDir(project, dir, cache, layout);
        if (frameworkSources == null && packagerDeclaresNativeSources(project, dir)) {
            // The packager owns the native invocation but its augment ran in
            // JVM mode — without a [native] table the build never asked for native
            // sources. Falling through to the generic classpath build is exactly the
            // "main entry point not found" failure  fixed; fail with the cure
            // instead.
            String msg = "this framework builds its own native image, but no native-image"
                    + " sources were produced. Add a `[native]` table (it can be empty) to"
                    + " jk.toml so the framework's augment runs in native mode, then re-run"
                    + " `jk native`.";
            ctx.error("native-sources-missing", msg);
            throw new RuntimeException(msg);
        }
        return frameworkSources;
    }

    /**
     * Args, least specific first so the more specific wins on conflict: what the active plugins'
     * frameworks require (class-initialization policy, which no amount of reachability metadata
     * expresses), then [native].args, then the CLI's trailing args.
     */
    private static List<String> imageArgs(
            TaskContext ctx,
            JkBuild project,
            Path dir,
            JkBuild.NativeConfig nativeCfg,
            List<String> extra,
            @Nullable Path frameworkSources)
            throws Exception {
        List<String> pluginNativeArgs = frameworkSources != null
                ? List.of()
                : cc.jumpkick.plugin.manifest.PluginContributions.nativeArgs(project, dir);
        if (!pluginNativeArgs.isEmpty()) {
            ctx.label(pluginNativeArgs.size() + " native-image " + (pluginNativeArgs.size() == 1 ? "arg" : "args")
                    + " from plugins");
        }
        List<String> allArgs = new ArrayList<>(pluginNativeArgs);
        allArgs.addAll(nativeCfg.args());
        allArgs.addAll(extra);
        return allArgs;
    }

    /** The image classpath: the jar (or a classes-run packager's exploded output) plus the runtime closure. */
    private static List<Path> imageClasspath(
            JkBuild project, Path dir, Path cache, Path lockFile, BuildLayout layout, Path mainJar) throws Exception {
        List<Path> classpath = new ArrayList<>();
        if (PluginBuild.shape(project, dir).map(sh -> sh.classesRun()).orElse(false)) {
            // A classes-run packager's jar is not classpath-able (e.g. Boot's
            // BOOT-INF nesting) — native-image gets the exploded classes plus
            // whatever the plugin's steps contributed (generated classes +
            // META-INF/native-image hints), produced just before this step.
            classpath.add(layout.classesDir());
            var plugins = ActivePlugins.declared(project, dir, cache, layout.moduleTargetDir());
            if (plugins != null) {
                for (Path contributed : PluginBuild.contributedDirs(plugins.decls(), layout)) {
                    if (Files.isDirectory(contributed)) classpath.add(contributed);
                }
            }
        } else {
            classpath.add(mainJar);
        }
        // Module-scoped runtime closure + workspace sibling jars.
        for (Path p : assemblyDependencyJars(dir, project, lockFile, cache)) {
            if (!classpath.contains(p)) classpath.add(p);
        }
        return classpath;
    }

    /**
     * Reachability metadata (general, not Boot-specific): third-party libs publish native-image
     * config to the GraalVM metadata repository rather than their own jars. Matched dirs ride
     * -H:ConfigurationFileDirectories; unavailable (offline) degrades to building without it.
     * Trained reachability from `jk train` (target/train/merged/reachability) goes first. Returns
     * the args with the metadata flags prepended, or unchanged when there is none.
     */
    private static List<String> withReachabilityMetadata(
            TaskContext ctx,
            Path dir,
            JkBuild project,
            BuildLayout layout,
            Path lockFile,
            Path javaHome,
            @Nullable Path frameworkSources,
            List<String> allArgs)
            throws Exception {
        List<Path> metadataDirs = List.of();
        if (Files.exists(lockFile)) {
            Lockfile metaLock = LockfileReader.read(lockFile);
            List<Lockfile.Artifact> runtimeArtifacts = new ArrayList<>();
            for (Lockfile.Artifact a : metaLock.artifacts()) {
                if (a.inAnyScope(ClasspathResolver.RUNTIME) && a.checksum() != null) {
                    runtimeArtifacts.add(a);
                }
            }
            cc.jumpkick.repo.RepoGroup metaRepos = RepoGroupBuilder.buildFor(project, null, JkStores.storeCas());
            metadataDirs = ReachabilityMetadata.configDirs(
                    JkStores.store(), metaRepos, metaLock.nativeMetadata(), runtimeArtifacts, msg -> ctx.label(msg));
        }
        if (frameworkSources != null) {
            metadataDirs = List.of();
        }
        Path trainReach = trainReachabilityDir(layout);
        if (trainReach != null) {
            ArrayList<Path> withTrain = new ArrayList<>(metadataDirs);
            withTrain.add(0, trainReach);
            metadataDirs = withTrain;
            // Refuse to native-build on stale train outputs when configured.
            try {
                var trainCfg = cc.jumpkick.config.JkBuildParser.trainConfig(ManifestPaths.manifestIn(dir));
                String stale = TrainRunner.staleReason(dir, project, layout, lockFile, javaHome, trainCfg);
                if (stale != null) {
                    ctx.error("train-stale", stale);
                    throw new RuntimeException(stale);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (metadataDirs.isEmpty()) return allArgs;
        StringBuilder dirsArg = new StringBuilder();
        for (Path d : metadataDirs) {
            if (dirsArg.length() > 0) dirsArg.append(',');
            dirsArg.append(d.toAbsolutePath());
        }
        // Prepended (before [native].args + CLI extras) so user flags win;
        // the unlock pair scopes the experimental option to just this flag.
        List<String> withMeta = new ArrayList<>();
        withMeta.add("-H:+UnlockExperimentalVMOptions");
        withMeta.add("-H:ConfigurationFileDirectories=" + dirsArg);
        withMeta.add("-H:-UnlockExperimentalVMOptions");
        withMeta.addAll(allArgs);
        return withMeta;
    }

    /**
     * The trained reachability metadata {@code jk train} merged under the module target, when it
     * is complete enough to feed native-image; {@code null} otherwise. One predicate for both the
     * args that name the dir and the key that must follow its content.
     */
    static @Nullable Path trainReachabilityDir(BuildLayout layout) {
        Path trainReach = layout.moduleTargetDir()
                .resolve(TrainLayout.ROOT)
                .resolve("merged")
                .resolve(TrainLayout.REACHABILITY);
        boolean present =
                Files.isDirectory(trainReach) && Files.isRegularFile(trainReach.resolve("reachability-metadata.json"));
        return present ? trainReach : null;
    }

    /** An executable image's packaging-cache identity: task id, action key and the tokens behind it. */
    record ImageKey(String task, String key, List<String> tokens) {}

    /**
     * Packaging cache (executable only): the binary is a pure function of the runtime classpath,
     * the build args, the main class, the GraalVM toolchain and the content of the trained
     * reachability metadata the args name. Shared libraries (+ generated C headers) aren't
     * cached yet.
     */
    static ImageKey imageKey(
            Path javaHome,
            List<Path> classpath,
            List<String> allArgs,
            @Nullable String mainClass,
            boolean shared,
            Path out,
            @Nullable Path frameworkSources,
            @Nullable Path trainReach)
            throws Exception {
        Path releaseFile = javaHome.resolve("release");
        String graalTok = Files.isRegularFile(releaseFile)
                ? cc.jumpkick.host.Hashing.sha256Hex(releaseFile)
                : javaHome.toString();
        List<String> nativeTokens = List.of(
                "cp:" + ClasspathFingerprint.of(classpath),
                "args:" + String.join(" ", allArgs),
                "main:" + (mainClass == null ? "" : mainClass),
                "shared:" + shared,
                "out:" + out.getFileName(),
                "graal:" + graalTok,
                // Framework mode consumes the whole native-sources tree (computed args,
                // runner jar) — a plugin-only change to it must miss the cache.
                "framework:" + (frameworkSources == null ? "" : ClasspathFingerprint.entry(frameworkSources)),
                // The args name the train dir by path; its content is what shapes the image, so a
                // `jk train` with another workload must miss the cache rather than restore the
                // binary the previous workload produced.
                "train:" + (trainReach == null ? "" : ClasspathFingerprint.entry(trainReach)));
        String nTask = ActionKey.qualifiedTaskId(TaskNames.NATIVE_IMAGE, out);
        String nKey = ActionKey.forArtifact(nTask, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), nativeTokens);
        return new ImageKey(nTask, nKey, nativeTokens);
    }

    /** Effective size (app full + discounted deps) for ETA learning / reweight, and the human label. */
    private static void reserveNativeWeight(TaskContext ctx, Path dir, List<Path> classpath, Path out)
            throws Exception {
        long effectiveBytes = NativeEffort.estimateInputBytes(dir);
        if (effectiveBytes < 1024) effectiveBytes = NativeEffort.sumExistingBytes(classpath);
        NativeEffort.recordSuccessInputBytes(dir, effectiveBytes);
        // Size-aware reservation; reweight may shrink only (never grow the bar).
        int sized = NativeEffort.weight(dir);
        try {
            ctx.reweight(sized);
        } catch (RuntimeException e) {
            Log.debug("imageKey: RuntimeException ignored", e);
        }
        // Human label: output binary basename + full classpath byte sum.
        // CLI colors filename with Theme.path (periwinkle) and the size as bold white.
        // Effective/discounted bytes stay internal for ETA learning.
        long classpathBytes = NativeEffort.sumExistingBytes(classpath);
        long labelBytes = classpathBytes > 0 ? classpathBytes : effectiveBytes;
        String binName = nativeOutputDisplayName(out);
        ctx.label(
                labelBytes > 0
                        ? binName + " · classpath input size: ~" + formatNativeInputMib(labelBytes) + " MiB"
                        : binName);
    }

    /**
     * Run native-image with the stage-progress listener, keep its output as a report, fail on a
     * non-zero exit, and move a framework's own binary to {@code out}.
     */
    private static void runDriver(
            TaskContext ctx,
            JkBuild project,
            Path dir,
            BuildLayout layout,
            Path javaHome,
            @Nullable Path frameworkSources,
            List<String> allArgs,
            List<Path> classpath,
            @Nullable String mainClass,
            Path out,
            boolean shared)
            throws Exception {
        // Progress listener: parse [N/M] headers from native-image stdout.
        // ticks(10) is declared upfront (preamble + 8 GraalVM stages + done).
        // Ticks: 1 preamble (when step 1 first appears) +
        // 8 steps ([1/8]…[8/8]) +
        // 1 final (ctx.progress after run returns) = 10.
        // Fallback: if no [N/M] headers appear (older GraalVM, --quiet),
        // the listener never fires and the single ctx.progress(1) at the end
        // is the only tick — the bar jumps to 1/10, which is acceptable.
        java.util.concurrent.atomic.AtomicBoolean preambleDone = new java.util.concurrent.atomic.AtomicBoolean(false);
        cc.jumpkick.tool.NativeImageDriver.ProgressListener listener = (current, total, label) -> {
            if (preambleDone.compareAndSet(false, true)) {
                ctx.progress(1); // preamble done (output before [1/N])
            }
            ctx.label("[" + current + "/" + total + "] " + label);
            ctx.progress(1); // stage N started = stage N-1 done
        };

        // Run the framework's list; jk's classpath and [application] main describe a
        // different image entirely (Quarkus enters through a generated --features
        // class, not a main method).
        var request = frameworkSources != null
                ? cc.jumpkick.tool.NativeImageDriver.Request.verbatim(
                        javaHome, frameworkSources, frameworkNativeArgs(frameworkSources, allArgs), out)
                : new cc.jumpkick.tool.NativeImageDriver.Request(javaHome, classpath, mainClass, out, allArgs, shared);
        if (frameworkSources != null) {
            ctx.label("native-image from "
                    + ActivePlugins.packager(project, dir)
                            .map(a -> a.manifest().id())
                            .orElse("plugin")
                    + " sources");
        }
        // Capture Graal stdout/stderr for progress parsing, a durable report, and the
        // plan output channel. The CLI buffers output for Ctrl-O peek (hidden by
        // default); --verbose streams it live. Do not gate on verbose/failure only —
        // that left the peek buffer empty during a successful native-image run.
        List<String> niLog = java.util.Collections.synchronizedList(new ArrayList<>());
        int exit = cc.jumpkick.tool.NativeImageDriver.run(request, listener, line -> {
            niLog.add(line);
            ctx.output(line);
        });
        Path niReport = layout.reportsDir().resolve("native-image.out");
        try {
            Files.createDirectories(niReport.getParent());
            String body = niLog.isEmpty() ? "" : String.join("\n", niLog) + "\n";
            Files.writeString(niReport, body);
        } catch (IOException ioe) {
            // Best-effort report; never fail the image over log write.
        }
        if (exit != 0) {
            ctx.error(
                    "native",
                    "native-image exited " + exit
                            + (Files.isRegularFile(niReport) ? " (full log: " + niReport + ")" : ""));
            throw new RuntimeException("native-image failed (exit " + exit + ")");
        }
        // The framework's args name their own output, inside its sources dir.
        if (frameworkSources != null) {
            Path produced = frameworkBinary(frameworkSources);
            if (produced == null) {
                throw new IOException("native-image reported success but produced no binary in " + frameworkSources);
            }
            Files.createDirectories(out.getParent());
            Files.move(produced, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            out.toFile().setExecutable(true);
        }
    }

    // ---- helpers --------------------------------------------------------

    /** Whether the module's packager declares a {@code native-image-sources} output at all. */
    static boolean packagerDeclaresNativeSources(JkBuild project, Path dir) {
        try {
            return nativeSourcesRel(ActivePlugins.packager(project, dir).orElse(null)) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The packager's {@code native-image-sources} step output, or null when the module's native
     * image is jk's generic one. Present only once the plugin has actually written it — a
     * declared directory with no {@code native-image.args} means the framework did not run a
     * native build.
     */
    static @Nullable Path nativeImageSourcesDir(JkBuild project, Path dir, Path cache, BuildLayout layout)
            throws IOException, InterruptedException {
        PluginBuild.Active packager = ActivePlugins.packager(project, dir).orElse(null);
        String rel = nativeSourcesRel(packager);
        if (packager == null || rel == null) return null;
        Path sources = PluginBuild.taskScratch(layout, stepNameOf(packager, project, dir, cache))
                .resolve(rel);
        return Files.isRegularFile(sources.resolve(NATIVE_IMAGE_ARGS)) ? sources : null;
    }

    /** The packager's declared {@code native-image-sources} dir, or null when it declares none. */
    private static @Nullable String nativeSourcesRel(PluginBuild.@Nullable Active packager) {
        if (packager == null) return null;
        var packaging = packager.manifest().packaging();
        if (packaging == null) return null;
        String rel = packaging.resolve(packager.config()).nativeImageSources();
        return rel == null || rel.isBlank() ? null : rel;
    }

    /** The plugin's build step name — the scratch dir its declared outputs live under. */
    static String stepNameOf(PluginBuild.Active active, JkBuild project, Path dir, Path cache)
            throws IOException, InterruptedException {
        var decls = PluginBuild.declarations(active, project, dir, cache, dir.resolve(BuildLayout.TARGET));
        for (var task : decls.steps()) {
            for (String outDir : task.outputs()) {
                if (!outDir.isBlank()) return task.name();
            }
        }
        return active.manifest().id();
    }

    static final String NATIVE_IMAGE_ARGS = "native-image.args";

    /** Human MiB for native-image step labels (one decimal under 10 MiB so ~1.4 does not become 1). */
    static String formatNativeInputMib(long bytes) {
        if (bytes <= 0) return "0";
        double mib = bytes / (1024.0 * 1024.0);
        if (mib < 10.0) {
            return String.format(java.util.Locale.ROOT, "%.1f", Math.max(0.1, mib));
        }
        return Long.toString(Math.max(1L, Math.round(mib)));
    }

    /**
     * Basename shown in the native-image step label — the on-disk file ({@code .exe} on Windows
     * for executables), matching {@link cc.jumpkick.layout.BuildLayout#nativeBinary()}.
     */
    static String nativeOutputDisplayName(Path out) {
        if (out == null || out.getFileName() == null) return "native";
        return out.getFileName().toString();
    }

    /**
     * The framework's argument list, plus jk's own extras last so {@code [native] args} and CLI
     * trailing args still win. The file is one whitespace-separated line as native-image's
     * {@code @argfile} format expects.
     */
    static List<String> frameworkNativeArgs(Path sources, List<String> extras) throws IOException {
        List<String> args = new ArrayList<>();
        for (String token :
                Files.readString(sources.resolve(NATIVE_IMAGE_ARGS)).trim().split("\\s+")) {
            if (!token.isBlank()) args.add(token);
        }
        args.addAll(extras);
        return args;
    }

    /** The executable native-image left in {@code sources} (the args name it, jk does not). */
    static @Nullable Path frameworkBinary(Path sources) throws IOException {
        try (var list = Files.list(sources)) {
            // Cheapest rejection first, most expensive last. The name tests are free; isRegularFile
            // re-resolves the path for a stat (10.3 us on NTFS); isExecutable is the worst operation
            // in the tree at 64x Linux, because Windows answers it with a security-descriptor read
            // plus an AccessCheck. Ordering it last means a directory listing of jars and .args files
            // never pays for it.
            return list.filter(p -> !p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith(".args"))
                    .filter(p -> !p.getFileName().toString().endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .filter(PathUtil::isRunnable)
                    .findFirst()
                    .orElse(null);
        }
    }

    static List<Path> javaSources(TaskContext ctx) {
        return ctx.get(JAVA_SOURCES).orElse(List.of());
    }

    static List<Path> kotlinSources(TaskContext ctx) {
        return ctx.get(KOTLIN_SOURCES).orElse(List.of());
    }

    /**
     * GraalVM / JDK home that {@code NativeImageDriver.resolve} recognises — i.e. one where
     * {@code GraalLauncher} finds a native-image launcher; that owner defines which directories
     * and spellings count. Order: client-resolved home first, then {@code $GRAALVM_HOME}, then
     * the project JDK, then the running JVM.
     */
    static Path resolveNativeImageHome(
            @Nullable Path graalHome, Path projectDir, @Nullable Path jdksDir, @Nullable String moduleGraalSpec) {
        if (graalHome != null
                && cc.jumpkick.tool.NativeImageDriver.resolve(graalHome).isPresent()) {
            return graalHome;
        }
        // The request's GRAALVM_HOME, carried as a typed field rather than sampled from this
        // process's environment — the engine is a daemon.
        var buildEnv = BuildEnv.forModule(projectDir);
        Path fromRequest = SessionContext.current().graalHome();
        if (fromRequest != null
                && cc.jumpkick.tool.NativeImageDriver.resolve(fromRequest, buildEnv)
                        .isPresent()) {
            return fromRequest;
        }
        // No client answer (HTTP / MCP): the installed Graal the CLI's resolver would have named,
        // short of installing one — the request's --graal spec, the module's [native].graal, the
        // lock's [graal] pin, the jk jdk graal pointer, then policy.
        Optional<Path> installed = GraalHomeLookup.installed(
                projectDir, jdksDir, buildEnv, SessionContext.current().graalSpec(), moduleGraalSpec);
        if (installed.isPresent()) return installed.get();
        try {
            return cc.jumpkick.jdk.JdkResolver.forProject(projectDir, jdksDir)
                    .map(cc.jumpkick.jdk.InstalledJdk::home)
                    .orElseGet(JavaHomes::runningJavaHome);
        } catch (IOException e) {
            return JavaHomes.runningJavaHome();
        }
    }

    static List<Path> groovySources(TaskContext ctx) {
        return ctx.get(GROOVY_SOURCES).orElse(List.of());
    }
}
