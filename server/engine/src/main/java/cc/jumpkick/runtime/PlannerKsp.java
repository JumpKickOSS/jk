// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerNative.javaSources;
import static cc.jumpkick.runtime.PlannerNative.kotlinSources;
import static cc.jumpkick.runtime.PlannerPlugin.beforeCompile;
import static cc.jumpkick.runtime.PlannerSupport.lockModules;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.KspProcessors;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.KotlinBtaResolver;
import cc.jumpkick.runtime.base.KspResolver;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * KSP round, generated-source unions, and plugin source-contribution helpers.
 */
public final class PlannerKsp {

    private PlannerKsp() {}

    /** The step names of every source-generating plugin step the compilers must wait for. */
    static List<String> sourceGenStepSteps(PluginBuild.@Nullable Declarations decls) {
        List<String> out = new ArrayList<>();
        if (decls != null) {
            for (PluginBuild.TaskDecl step : decls.steps()) {
                if (beforeCompile(step)) out.add("plugin-" + step.name());
            }
        }
        return out;
    }

    /**
     * Plugin-contributed generated sources ({@code contributesSources} of before-compile steps):
     * files with {@code suffix} under each contributed scratch dir. They join the compiler's
     * source list, so the freshness stamp and the javac action key see them like any source.
     */
    static List<Path> pluginContributedSources(
            BuildLayout layout, PluginBuild.@Nullable Declarations decls, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.TaskDecl step : decls.steps()) {
            for (String rel : step.contributesSources()) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (!Files.isDirectory(dir)) continue;
                try (var walk = Files.walk(dir)) {
                    walk.filter(f -> f.toString().endsWith(suffix) && Files.isRegularFile(f))
                            .sorted()
                            .forEach(out::add);
                }
            }
        }
        return out;
    }

    /** Plugin steps' declared source-contribution dirs (existing ones only). */
    static List<Path> pluginContributedSourceDirs(BuildLayout layout, PluginBuild.@Nullable Declarations decls) {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.TaskDecl step : decls.steps()) {
            for (String rel : step.contributesSources()) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (Files.isDirectory(dir)) out.add(dir);
            }
        }
        return out;
    }

    /** Plugin steps' declared test-classpath contribution dirs (existing ones only). */
    static List<Path> pluginTestClasspath(BuildLayout layout, PluginBuild.@Nullable Declarations decls) {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.TaskDecl step : decls.steps()) {
            for (String rel : step.contributesTestClasspath()) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (Files.isDirectory(dir)) out.add(dir);
            }
        }
        return out;
    }

    /**
     * The arguments plugin steps contributed to the forked test JVM ({@code contributesTestJvmArgs}):
     * every non-blank line of each declared file, in declaration order. A declared file the step
     * did not write contributes nothing.
     */
    static List<String> pluginTestJvmArgs(BuildLayout layout, PluginBuild.@Nullable Declarations decls)
            throws IOException {
        List<String> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.TaskDecl step : decls.steps()) {
            for (String rel : step.contributesTestJvmArgs()) {
                Path file = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (!Files.isRegularFile(file)) continue;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) out.add(line.strip());
                }
            }
        }
        return out;
    }

    /** The provided-classpath contribution (platform jars), re-read for the test step. */
    static List<Path> contributedProvidedFor(TaskContext ctx) {
        return ctx.get(PROVIDED_CP).orElse(List.of());
    }

    /** Generated-source dirs the KSP round writes (checked by the compile-step unions). */
    /** One processor-authored KSP diagnostic: the reporting severity plus the bare message. */
    // KspDiagnostic lives on BuildPlanner so existing tests keep their type name.

    /**
     * The processor-authored diagnostics in a successful KSP round's output.
     *
     * <p>KSP's CLI prefixes them {@code w:} / {@code i:} / {@code v:}, usually with a {@code [ksp]}
     * tag. Everything else on that stream is host noise — the JVM's {@code sun.misc.Unsafe}
     * deprecation banner from KSP's bundled IntelliJ containers, stack frames, blank lines — and
     * reprinting it on every green build would train people to ignore the channel entirely.
     *
     * <p>Both prefixes are stripped: the reporter already renders the step and severity, so
     * carrying them in the text too gives {@code Warning [ksp/ksp]: w: [ksp] …}.
     */
    static List<BuildPlanner.KspDiagnostic> kspDiagnostics(String output) {
        List<BuildPlanner.KspDiagnostic> out = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.length() < 2 || trimmed.charAt(1) != ':') continue;
            String severity =
                    switch (trimmed.charAt(0)) {
                        case 'w' -> "warn";
                        case 'i' -> "info";
                        case 'v' -> "verbose";
                        default -> null;
                    };
            if (severity == null) continue;
            // KSP tags its own output; kotlinc-level warnings on the same stream are the Kotlin
            // compile step's business, not ours.
            String rest = trimmed.substring(2).strip();
            if (!rest.startsWith("[ksp]")) continue;
            String message = rest.substring("[ksp]".length()).strip();
            if (message.isEmpty()) continue;
            out.add(new BuildPlanner.KspDiagnostic(severity, message));
        }
        return out;
    }

    static Path kspOutBase(BuildLayout layout) {
        return layout.moduleTargetDir().resolve("ksp");
    }

    /** Files with {@code suffix} under the KSP output tree, sorted — empty when no round ran. */
    static List<Path> kspGeneratedSources(BuildLayout layout, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String lang : List.of("kotlin", "java")) {
            Path dir = kspOutBase(layout).resolve(lang);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                walk.filter(f -> f.toString().endsWith(suffix) && Files.isRegularFile(f))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    /**
     * KSP2 round: fork {@code KSPJvmMain} with KSP processor jars ({@link
     * cc.jumpkick.compile.KspProcessors}); outputs under {@code target/ksp/} join compile sources.
     */
    static Task kspStep(BuildPlanner.Ctx cx, PluginBuild.@Nullable Declarations pluginDecls) {
        // Plugin-contributed sources (protoc output, variant extra-src) must exist before the
        // round and join its source roots — a contributed @Module/@Entity is processor input
        // like any hand-written one.
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        requires.addAll(sourceGenStepSteps(pluginDecls));
        return Task.builder(TaskNames.KSP)
                .stage(BuildStage.COMPILE)
                .label("KSP")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                .execute(ctx -> runKsp(ctx, cx, pluginDecls))
                .build();
    }

    /** The KSP2 round: split the processors, check the stamp, resolve the toolchain, fork, stamp. */
    private static void runKsp(TaskContext ctx, BuildPlanner.Ctx cx, PluginBuild.@Nullable Declarations pluginDecls)
            throws Exception {
        BuildPlanner.Inputs in = cx.in();
        List<Path> processorCp = ctx.require(PROCESSOR_CP);
        var split = KspProcessors.split(processorCp);
        ctx.put(JAVAC_PROCESSOR_CP, split.javac());
        if (split.ksp().isEmpty()) {
            ctx.label("no KSP processors");
            ctx.progress(1);
            return;
        }
        BuildLayout layout = ctx.require(LAYOUT);
        Path outBase = kspOutBase(layout);
        List<Path> classpath = ctx.require(CLASSPATH);
        List<Path> ktSources = kotlinSources(ctx);
        List<Path> javaSources = javaSources(ctx);

        List<Path> stampInputs = new ArrayList<>(ktSources);
        stampInputs.addAll(javaSources);
        // Contributed sources are round input too — an extra-src/protoc edit re-runs.
        stampInputs.addAll(pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".kt"));
        stampInputs.addAll(pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".java"));
        List<Path> stampCp = new ArrayList<>(classpath);
        stampCp.addAll(split.ksp());
        boolean rerun = in.session().config().rebuildOr(false);
        JkBuild project = ctx.require(PROJECT);
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), project);
        if (kotlinVersion == null || kotlinVersion.isBlank()) {
            kotlinVersion = KotlinResolver.DEFAULT_VERSION;
        }
        // Processor options and the toolchain are round inputs no file mtime reflects; the digest
        // is what makes a `ksp-options` edit with untouched sources re-run the round.
        String optionsDigest = kspStampDigest(
                project, ctx.require(LOCKFILE), in.dir(), kotlinVersion, ctx.require(JAVA_HOME), ctx.require(RELEASE));
        if (!rerun
                && FreshnessStamp.isFresh(
                        outBase, BuildStamps.KSP, stampInputs, stampCp, ctx.require(RELEASE), optionsDigest)) {
            ctx.reweight(EffortWeights.TOKEN); // cache/stamp skip — token tick
            ctx.label("up to date");
            ctx.progress(1);
            return;
        }

        ctx.label("KSP: " + split.ksp().size() + " processor jar(s)");
        // Before the round reads a source: a source edited while it runs is stale next time.
        long readClock = FreshnessStamp.clockNow(outBase);
        KspToolchain toolchain = resolveKspToolchain(project, cx.cas(), kotlinVersion);

        // A stale round's outputs must not survive into the source union.
        for (String sub : List.of("kotlin", "java", "classes", "resources")) {
            PathUtil.deleteRecursively(outBase.resolve(sub));
        }
        Files.createDirectories(outBase.resolve("caches"));

        List<Path> ktRoots = kspSourceRoots(in, project, layout, pluginDecls, cx.compact());
        List<Path> libs = new ArrayList<>(classpath);
        libs.add(toolchain.stdlib());
        List<String> cmd = kspCommand(
                ctx, in, project, outBase, toolchain.kspClasspath(), ktRoots, libs, kotlinVersion, processorCp);
        String output = runKspProcess(ctx, in, cmd);
        // A green round still has things to say. Processor `logger.warn`/`info` is how
        // an annotation-driven framework explains what it did and what to do
        // differently; dropping it on success meant guidance only ever appeared once
        // the build was already broken. Surfaced the same way javac
        // diagnostics are, so -q/-v behave consistently.
        for (BuildPlanner.KspDiagnostic diagnostic : kspDiagnostics(output)) {
            ctx.warn(diagnostic.severity(), diagnostic.message());
        }
        FreshnessStamp.write(
                outBase,
                BuildStamps.KSP,
                TaskNames.KSP,
                "",
                stampInputs,
                stampCp,
                ctx.require(RELEASE),
                optionsDigest,
                readClock);
        ctx.progress(1);
    }

    /**
     * The round's processor options ({@code key=value}): plugin-contributed ([[contribute.compiler-args]]
     * ksp — Hilt's superclass-validation toggle) first, then the project's {@code [build]
     * ksp-options} (Room's schemaLocation), so the project overrides.
     */
    static List<String> kspOptions(JkBuild project, Path moduleDir, Lockfile lock) {
        List<String> options = new ArrayList<>(PluginContributions.kspOptions(project, moduleDir, lockModules(lock)));
        options.addAll(project.build().kspOptions());
        return options;
    }

    /**
     * Digest of the round's option-bearing inputs for its freshness stamp: the Kotlin version
     * (language and API level, and the KSP2 runtime resolved against it), the JDK, the module
     * name and every processor option.
     */
    static String kspStampDigest(
            JkBuild project, Lockfile lock, Path moduleDir, String kotlinVersion, Path javaHome, int release)
            throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add("kotlin:" + kotlinVersion);
        parts.add("jvmTarget:" + CompileSupport.kotlinJvmTarget(release, JvmOptions.hostFeature(javaHome)));
        parts.add("jdk:" + ActionKey.jdkToken(javaHome));
        parts.add("moduleName:" + project.project().name());
        for (String option : kspOptions(project, moduleDir, lock)) parts.add("option:" + option);
        return FreshnessStamp.optionsDigest(parts);
    }

    /** The KSP2 runtime classpath and the Kotlin stdlib the round compiles against. */
    private record KspToolchain(List<Path> kspClasspath, Path stdlib) {}

    private static KspToolchain resolveKspToolchain(JkBuild project, Cas cas, String kotlinVersion) throws Exception {
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
            String kspVersion = KspResolver.discoverVersion(repos);
            List<Path> kspClasspath = KspResolver.resolveClasspath(repos, cas, kspVersion);
            Path stdlib = KotlinBtaResolver.resolveStdlib(repos, cas, kotlinVersion);
            return new KspToolchain(kspClasspath, stdlib);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving KSP", e);
        }
    }

    /**
     * The round's source roots: the module's own, plus plugin-contributed source dirs (protoc
     * output, generated code) and [build] extra-src roots (variant overlays) — processor input
     * like any hand-written source. Only roots that exist.
     */
    private static List<Path> kspSourceRoots(
            BuildPlanner.Inputs in,
            JkBuild project,
            BuildLayout layout,
            PluginBuild.@Nullable Declarations pluginDecls,
            boolean compact)
            throws Exception {
        List<Path> srcRoots = new ArrayList<>(
                compact
                        ? List.of(in.dir().resolve("src"))
                        : List.of(in.dir().resolve("src/main/kotlin"), in.dir().resolve("src/main/java")));
        srcRoots.addAll(pluginContributedSourceDirs(layout, pluginDecls));
        srcRoots.addAll(CompileSupport.extraSrcDirs(project, in.dir()));
        List<Path> ktRoots = new ArrayList<>();
        for (Path root : srcRoots) {
            if (Files.isDirectory(root)) ktRoots.add(root);
        }
        return ktRoots;
    }

    /**
     * The KSPJvmMain command line. KSP is jk's tool: it runs on jk's own runtime, not the project's
     * pinned JDK (same rule as every plugin — requirements.md "plugin host"). AGP runs KSP in the
     * Gradle daemon's JVM the same way; the project JDK stays the -jdk-home cross-compile input.
     */
    private static List<String> kspCommand(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JkBuild project,
            Path outBase,
            List<Path> kspClasspath,
            List<Path> ktRoots,
            List<Path> libs,
            String kotlinVersion,
            List<Path> processorCp)
            throws Exception {
        String languageVersion = majorMinor(kotlinVersion);
        Path javaHome = ctx.require(JAVA_HOME);
        List<String> cmd = new ArrayList<>();
        cmd.add(JdkFingerprint.java(JavaHomes.runningJavaHome()).toString());
        cmd.addAll(JvmOptions.batchFlags(1));
        cmd.add("-cp");
        cmd.add(Classpaths.join(kspClasspath));
        cmd.add(KspResolver.KSP_MAIN);
        cmd.add("-module-name=" + project.project().name());
        cmd.add("-source-roots=" + Classpaths.join(ktRoots));
        cmd.add("-java-source-roots=" + Classpaths.join(ktRoots));
        cmd.add("-project-base-dir=" + in.dir().toAbsolutePath());
        cmd.add("-output-base-dir=" + outBase.toAbsolutePath());
        cmd.add("-caches-dir=" + outBase.resolve("caches").toAbsolutePath());
        cmd.add("-class-output-dir=" + outBase.resolve("classes").toAbsolutePath());
        cmd.add("-kotlin-output-dir=" + outBase.resolve("kotlin").toAbsolutePath());
        cmd.add("-java-output-dir=" + outBase.resolve("java").toAbsolutePath());
        cmd.add("-resource-output-dir=" + outBase.resolve("resources").toAbsolutePath());
        cmd.add("-language-version=" + languageVersion);
        cmd.add("-api-version=" + languageVersion);
        cmd.add("-jvm-target="
                + CompileSupport.kotlinJvmTarget(ctx.require(RELEASE), JvmOptions.hostFeature(javaHome)));
        cmd.add("-jdk-home=" + javaHome.toAbsolutePath());
        cmd.add("-libraries=" + Classpaths.join(libs));
        // KSP's map syntax joins entries with the platform path separator, same as its list
        // args; relative option paths resolve against the module dir (the KSP process CWD).
        List<String> kspOptions = kspOptions(project, in.dir(), ctx.require(LOCKFILE));
        if (!kspOptions.isEmpty()) {
            cmd.add("-processor-options=" + String.join(Classpaths.SEPARATOR, kspOptions));
        }
        // The trailing processor classpath is the WHOLE [processor-dependencies]
        // closure — a provider jar (room-compiler) loads its own deps from it.
        cmd.add(Classpaths.join(processorCp));
        return cmd;
    }

    /**
     * Fork KSP and return its output. Read on a drainer thread and bound the wait: on an internal
     * error KSP's JVM can linger (non-daemon compiler pools survive the main thread's exception),
     * which would hang a plain readAllBytes forever. A non-zero exit fails the step with the output.
     */
    private static String runKspProcess(TaskContext ctx, BuildPlanner.Inputs in, List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(in.dir().toFile()).redirectErrorStream(true);
        Process proc = JobWorkers.start(pb);
        StringBuilder captured = new StringBuilder();
        // Byte pump for KSP's output; reads no session.
        Thread drainer = new Thread(() -> {
            try (var in2 = proc.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in2.read(buf)) >= 0) {
                    captured.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // stream closed with the process
            }
        });
        drainer.setDaemon(true);
        drainer.start();
        int exit;
        try {
            if (!proc.waitFor(15, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                ctx.error(TaskNames.KSP, "KSP timed out after 15 minutes\n" + captured);
                throw new RuntimeException("KSP timed out");
            }
            exit = proc.exitValue();
            drainer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new RuntimeException("interrupted waiting for KSP", e);
        }
        String output = captured.toString();
        if (exit != 0) {
            ctx.error(TaskNames.KSP, output.isBlank() ? ("KSP exited " + exit) : output);
            throw new RuntimeException("KSP processing failed");
        }
        return output;
    }

    /**
     * The Java source roots kotlinc reads declarations from in a mixed module: the hand-written
     * root plus the KSP round's generated-Java dir (Hilt components are Java — a Kotlin class
     * extending a generated base must resolve it during Kotlin analysis).
     */
    static @Nullable List<Path> kotlinJavaSourceRoots(
            boolean mixedWithJava,
            boolean compact,
            Path dir,
            BuildLayout layout,
            PluginBuild.@Nullable Declarations decls) {
        if (!mixedWithJava) return null;
        List<Path> roots = new ArrayList<>();
        roots.add(compact ? dir.resolve("src") : dir.resolve("src/main/java"));
        Path kspJava = kspOutBase(layout).resolve("java");
        if (Files.isDirectory(kspJava)) roots.add(kspJava);
        // Plugin-contributed generated dirs can carry Java that Kotlin sources reference
        // (protoc: the --kotlin_out DSL wraps its own --java_out message classes).
        if (decls != null) {
            for (PluginBuild.TaskDecl step : decls.steps()) {
                for (String rel : step.contributesSources()) {
                    Path contributed =
                            PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                    if (Files.isDirectory(contributed)) roots.add(contributed);
                }
            }
        }
        return roots;
    }

    /** The {@code major.minor} language level of a full Kotlin version ({@code 2.4.0} → 2.4). */
    static String majorMinor(String version) {
        int first = version.indexOf('.');
        int second = version.indexOf('.', first + 1);
        return second > 0 ? version.substring(0, second) : version;
    }
}
