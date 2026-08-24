// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
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
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * native-image tail and Graal home resolution.
 */
public final class PlannerNative {

    private PlannerNative() {}

    public static Task nativeStep(
            Path dir,
            Path cache,
            Path lockFile,
            Path jdksDir,
            Path graalHome,
            String mainOverride,
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
            Path jdksDir,
            Path graalHome,
            String mainOverride,
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
                .execute(ctx -> {
                    // Fail-fast: verify native-image is available before compilation
                    // has already run and the user has waited for potentially minutes.
                    // Resolution: explicit graalHome (client) → $GRAALVM_HOME → project JDK →
                    // running JVM. [native] enabled = "always" on jk build takes this path with
                    // graalHome=null and relies on env / project JDK having native-image.
                    Path javaHomeEarly = resolveNativeImageHome(graalHome, dir, jdksDir);
                    if (cc.jumpkick.tool.NativeImageDriver.resolve(javaHomeEarly)
                            .isEmpty()) {
                        ctx.error(
                                "native",
                                cc.jumpkick.tool.NativeImageDriver.notFoundError(javaHomeEarly)
                                        .getMessage());
                        throw new RuntimeException("native-image not found");
                    }

                    JkBuild project = ctx.require(PROJECT);
                    JkBuild.NativeConfig nativeCfg = project.nativeConfig()
                            .orElseGet(() -> new JkBuild.NativeConfig(
                                    null, null, List.of(), null, JkBuild.NativeMode.SUPPORTED));
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path mainJar = layout.mainJar();
                    if (!Files.exists(mainJar)) {
                        ctx.error("native", "jar not found at " + mainJar);
                        throw new RuntimeException("missing main jar for native-image");
                    }
                    // Resolution order: --main CLI flag > [native].main > [application].main.
                    // A resolvable main → executable; none → shared library (--shared) on jk
                    // build. jk native requires a unique main (allowShared=false).
                    String mainClass = (mainOverride != null && !mainOverride.isBlank())
                            ? mainOverride
                            : (nativeCfg.mainClass() != null ? nativeCfg.mainClass() : project.mainClass());
                    if (mainClass == null || mainClass.isBlank()) {
                        boolean scan = !allowShared
                                || PluginBuild.shape(project, dir)
                                        .map(sh -> sh.mainScan())
                                        .orElse(false);
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
                    boolean shared = (mainClass == null || mainClass.isBlank());
                    if (shared) {
                        if (!allowShared) {
                            ctx.error("native", cc.jumpkick.layout.NativePreflight.NO_MAIN);
                            throw new RuntimeException(cc.jumpkick.layout.NativePreflight.NO_MAIN);
                        }
                        mainClass = null;
                    }
                    // Output path: [native].name overrides the artifact-derived name.
                    // Executable → target/<name>; library → target/lib<name> (native-image
                    // appends the platform extension.so/.dylib/.dll and emits C headers).
                    Path out;
                    if (nativeCfg.name() != null) {
                        String nm = nativeCfg.name();
                        out = layout.moduleTargetDir().resolve(shared && !nm.startsWith("lib") ? "lib" + nm : nm);
                    } else {
                        out = shared ? layout.nativeLibrary() : layout.nativeBinary();
                    }
                    Files.createDirectories(out.getParent());
                    // Args, least specific first so the more specific wins on conflict: what the
                    // active plugins' frameworks require (class-initialization policy, which no
                    // amount of reachability metadata expresses), then [native].args, then the
                    // CLI's trailing args.
                    // A framework that computed its own invocation gets none of jk's automatic
                    // additions. Its list is complete by construction — Quarkus even passes
                    // --exclude-config to suppress library metadata it does not want, and layering
                    // the community metadata repository on top of that reintroduces exactly what it
                    // excluded. `[native] args` and CLI extras still apply: those are the user
                    // speaking, not jk guessing.
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
                    List<String> pluginNativeArgs = frameworkSources != null
                            ? List.of()
                            : cc.jumpkick.plugin.manifest.PluginContributions.nativeArgs(project, dir);
                    if (!pluginNativeArgs.isEmpty()) {
                        ctx.label(pluginNativeArgs.size() + " native-image "
                                + (pluginNativeArgs.size() == 1 ? "arg" : "args") + " from plugins");
                    }
                    List<String> allArgs = new ArrayList<>(pluginNativeArgs);
                    allArgs.addAll(nativeCfg.args());
                    allArgs.addAll(extra);

                    Path javaHome = javaHomeEarly; // resolved above in fail-fast check

                    List<Path> classpath = new ArrayList<>();
                    if (PluginBuild.shape(project, dir)
                            .map(sh -> sh.classesRun())
                            .orElse(false)) {
                        // A classes-run packager's jar is not classpath-able (e.g. Boot's
                        // BOOT-INF nesting) — native-image gets the exploded classes plus
                        // whatever the plugin's steps contributed (generated classes +
                        // META-INF/native-image hints), produced just before this step.
                        classpath.add(layout.classesDir());
                        var activeOpt = PluginBuild.activeCodePlugin(project, dir);
                        if (activeOpt.isPresent()) {
                            var decls = PluginBuild.declarations(
                                    activeOpt.get(), project, dir, cache, layout.moduleTargetDir());
                            for (Path contributed : PluginBuild.contributedDirs(decls, layout)) {
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

                    // Reachability metadata (general, not Boot-specific): third-party libs
                    // publish native-image config to the GraalVM metadata repository rather
                    // than their own jars. Matched dirs ride -H:ConfigurationFileDirectories;
                    // unavailable (offline) degrades to building without it.
                    List<Path> metadataDirs = List.of();
                    if (Files.exists(lockFile)) {
                        Lockfile metaLock = LockfileReader.read(lockFile);
                        List<Lockfile.Artifact> runtimeArtifacts = new ArrayList<>();
                        for (Lockfile.Artifact a : metaLock.artifacts()) {
                            if (a.inAnyScope(ClasspathResolver.RUNTIME) && a.checksum() != null) {
                                runtimeArtifacts.add(a);
                            }
                        }
                        cc.jumpkick.repo.RepoGroup metaRepos =
                                RepoGroupBuilder.buildFor(project, null, JkStores.cas(cache));
                        metadataDirs = ReachabilityMetadata.configDirs(
                                cache, metaRepos, runtimeArtifacts, msg -> ctx.label(msg));
                    }
                    if (frameworkSources != null) {
                        metadataDirs = List.of();
                    }
                    // Trained reachability from `jk train` (target/train/merged/reachability).
                    Path trainReach = layout.moduleTargetDir()
                            .resolve(cc.jumpkick.surface.TrainLayout.ROOT)
                            .resolve("merged")
                            .resolve(cc.jumpkick.surface.TrainLayout.REACHABILITY);
                    if (Files.isDirectory(trainReach)
                            && Files.isRegularFile(trainReach.resolve("reachability-metadata.json"))) {
                        ArrayList<Path> withTrain = new ArrayList<>(metadataDirs);
                        withTrain.add(0, trainReach);
                        metadataDirs = withTrain;
                        // Refuse to native-build on stale train outputs when configured.
                        try {
                            var trainCfg =
                                    cc.jumpkick.config.TrainConfigParser.parse(dir.resolve(ManifestPaths.MANIFEST));
                            String stale =
                                    TrainRunner.staleReason(dir, project, layout, lockFile, javaHomeEarly, trainCfg);
                            if (stale != null) {
                                ctx.error("train-stale", stale);
                                throw new RuntimeException(stale);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    if (!metadataDirs.isEmpty()) {
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
                        allArgs = withMeta;
                    }

                    // Packaging cache (executable only): the binary is a pure function of
                    // the runtime classpath, the build args, the main class, and the GraalVM
                    // toolchain. Shared libraries (+ generated C headers) aren't cached yet.
                    Path releaseFile = javaHome.resolve("release");
                    String graalTok = Files.isRegularFile(releaseFile)
                            ? cc.jumpkick.host.Hashing.sha256Hex(releaseFile)
                            : javaHome.toString();
                    List<String> nativeTokens = List.of(
                            "cp:" + cc.jumpkick.task.ClasspathFingerprint.of(classpath),
                            "args:" + String.join(" ", allArgs),
                            "main:" + (mainClass == null ? "" : mainClass),
                            "shared:" + shared,
                            "out:" + out.getFileName(),
                            "graal:" + graalTok,
                            // Framework mode consumes the whole native-sources tree (computed args,
                            // runner jar) — a plugin-only change to it must miss the cache.
                            "framework:"
                                    + (frameworkSources == null
                                            ? ""
                                            : cc.jumpkick.task.ClasspathFingerprint.entry(frameworkSources)));
                    String nTask = ActionKey.qualifiedTaskId(TaskNames.NATIVE_IMAGE, out);
                    String nKey = ActionKey.forArtifact(
                            nTask, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), nativeTokens);
                    if (!shared && restorePackaged(cache, nKey, out.getParent())) {
                        // Shrink only: cache restore is a token touch. Never reweight *up* mid-run
                        // (bar must not jump; accurate native weight is reserved up front).
                        ctx.reweight(EffortWeights.RESTORE);
                        ctx.label(out.getFileName() + " up-to-date");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }

                    // Effective size (app full + discounted deps) for ETA learning / reweight.
                    long effectiveBytes = NativeEffort.estimateInputBytes(dir);
                    if (effectiveBytes < 1024) effectiveBytes = NativeEffort.sumExistingBytes(classpath);
                    NativeEffort.recordSuccessInputBytes(dir, effectiveBytes);
                    // Size-aware reservation; reweight may shrink only (never grow the bar).
                    int sized = NativeEffort.weight(dir);
                    try {
                        ctx.reweight(sized);
                    } catch (RuntimeException ignored) {
                    }
                    // Human label: output binary basename + full classpath byte sum.
                    // CLI colors filename with Theme.path (periwinkle) and the size as bold white.
                    // Effective/discounted bytes stay internal for ETA learning.
                    long classpathBytes = NativeEffort.sumExistingBytes(classpath);
                    long labelBytes = classpathBytes > 0 ? classpathBytes : effectiveBytes;
                    String binName = nativeOutputDisplayName(out, shared);
                    ctx.label(
                            labelBytes > 0
                                    ? binName + " · classpath input size: ~" + formatNativeInputMib(labelBytes) + " MiB"
                                    : binName);

                    // Progress listener: parse [N/M] headers from native-image stdout.
                    // ticks(10) is declared upfront (preamble + 8 GraalVM stages + done).
                    // Ticks: 1 preamble (when step 1 first appears) +
                    // 8 steps ([1/8]…[8/8]) +
                    // 1 final (ctx.progress after run returns) = 10.
                    // Fallback: if no [N/M] headers appear (older GraalVM, --quiet),
                    // the listener never fires and the single ctx.progress(1) at the end
                    // is the only tick — the bar jumps to 1/10, which is acceptable.
                    java.util.concurrent.atomic.AtomicBoolean preambleDone =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
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
                            : new cc.jumpkick.tool.NativeImageDriver.Request(
                                    javaHome, classpath, mainClass, out, allArgs, shared);
                    if (frameworkSources != null) {
                        ctx.label("native-image from "
                                + PluginBuild.activeCodePlugin(project, dir)
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
                            throw new IOException(
                                    "native-image reported success but produced no binary in " + frameworkSources);
                        }
                        Files.createDirectories(out.getParent());
                        Files.move(produced, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        out.toFile().setExecutable(true);
                    }
                    // Final tick: completes the last native-image step (or the only tick
                    // when no progress headers were emitted).
                    ctx.progress(1);
                    if (!shared) {
                        storePackaged(cache, nTask, nKey, nativeTokens, out.getParent(), List.of(out), persist);
                    }
                })
                .build();
    }

    // ---- helpers --------------------------------------------------------

    /**
     * The active plugin's {@code native-image-sources} step output, or null when the module's
     * native image is jk's generic one. Present only once the plugin has actually written it — a
     * declared directory with no {@code native-image.args} means the framework did not run a
     * native build.
     */
    /** Whether the active packager declares a {@code native-image-sources} output at all. */
    static boolean packagerDeclaresNativeSources(JkBuild project, Path dir) {
        try {
            var active = PluginBuild.activeCodePlugin(project, dir);
            if (active.isEmpty()) return false;
            var packaging = active.get().manifest().packaging();
            if (packaging == null) return false;
            String rel = packaging.resolve(active.get().config()).nativeImageSources();
            return rel != null && !rel.isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Path nativeImageSourcesDir(JkBuild project, Path dir, Path cache, BuildLayout layout)
            throws IOException, InterruptedException {
        var active = PluginBuild.activeCodePlugin(project, dir);
        if (active.isEmpty()) return null;
        var packaging = active.get().manifest().packaging();
        if (packaging == null) return null;
        String rel = packaging.resolve(active.get().config()).nativeImageSources();
        if (rel == null || rel.isBlank()) return null;
        Path sources = PluginBuild.taskScratch(layout, stepNameOf(active.get(), project, dir, cache))
                .resolve(rel);
        return Files.isRegularFile(sources.resolve(NATIVE_IMAGE_ARGS)) ? sources : null;
    }

    /** The plugin's build step name — the scratch dir its declared outputs live under. */
    static String stepNameOf(PluginBuild.Active active, JkBuild project, Path dir, Path cache)
            throws IOException, InterruptedException {
        var decls = PluginBuild.declarations(active, project, dir, cache, dir.resolve("target"));
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
     * Basename shown in the native-image step label — the {@code -o} target, with the platform
     * executable suffix on Windows ({@code .exe}) so the UI matches what lands on disk.
     */
    static String nativeOutputDisplayName(Path out, boolean shared) {
        if (out == null || out.getFileName() == null) return "native";
        String name = out.getFileName().toString();
        if (shared) return name;
        String os = System.getProperty("os.name", "");
        if (os.toLowerCase(java.util.Locale.ROOT).contains("win") && !name.endsWith(".exe") && !name.endsWith(".EXE")) {
            return name + ".exe";
        }
        return name;
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
    static Path frameworkBinary(Path sources) throws IOException {
        try (var list = Files.list(sources)) {
            return list.filter(Files::isRegularFile)
                    .filter(p -> Files.isExecutable(p))
                    .filter(p -> !p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith(".args"))
                    .filter(p -> !p.getFileName().toString().endsWith(".json"))
                    .findFirst()
                    .orElse(null);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Path> javaSources(TaskContext ctx) {
        return (List<Path>) ctx.get(JAVA_SOURCES).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    static List<Path> kotlinSources(TaskContext ctx) {
        return (List<Path>) ctx.get(KOTLIN_SOURCES).orElse(List.of());
    }

    /**
     * GraalVM / JDK home that has {@code bin/native-image}: client-resolved home first, then
     * {@code $GRAALVM_HOME}, then the project JDK, then the running JVM.
     */
    static Path resolveNativeImageHome(Path graalHome, Path projectDir, Path jdksDir) {
        if (graalHome != null
                && cc.jumpkick.tool.NativeImageDriver.resolve(graalHome).isPresent()) {
            return graalHome;
        }
        String env = System.getenv("GRAALVM_HOME");
        if (env != null && !env.isBlank()) {
            Path fromEnv = Path.of(env);
            if (cc.jumpkick.tool.NativeImageDriver.resolve(fromEnv).isPresent()) return fromEnv;
        }
        try {
            return cc.jumpkick.jdk.JdkResolver.forProject(projectDir, jdksDir)
                    .map(cc.jumpkick.jdk.InstalledJdk::home)
                    .orElseGet(JavaHomes::runningJavaHome);
        } catch (IOException e) {
            return JavaHomes.runningJavaHome();
        }
    }

    @SuppressWarnings("unchecked")
    static List<Path> groovySources(TaskContext ctx) {
        return (List<Path>) ctx.get(GROOVY_SOURCES).orElse(List.of());
    }
}
