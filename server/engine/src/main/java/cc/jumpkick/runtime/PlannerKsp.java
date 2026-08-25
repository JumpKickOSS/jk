// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerNative.javaSources;
import static cc.jumpkick.runtime.PlannerNative.kotlinSources;
import static cc.jumpkick.runtime.PlannerPlugin.beforeCompile;
import static cc.jumpkick.runtime.PlannerSupport.lockModules;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.KspProcessors;
import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * KSP round, generated-source unions, and plugin source-contribution helpers.
 */
public final class PlannerKsp {

    private PlannerKsp() {}

    /** The step names of every source-generating plugin step the compilers must wait for. */
    static List<String> sourceGenStepSteps(PluginBuild.Declarations decls) {
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
    static List<Path> pluginContributedSources(BuildLayout layout, PluginBuild.Declarations decls, String suffix)
            throws IOException {
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
    static List<Path> pluginContributedSourceDirs(BuildLayout layout, PluginBuild.Declarations decls) {
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
    static List<Path> pluginTestClasspath(BuildLayout layout, PluginBuild.Declarations decls) {
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

    /** The provided-classpath contribution (platform jars), re-read for the test step. */
    @SuppressWarnings("unchecked")
    static List<Path> contributedProvidedFor(TaskContext ctx) {
        return (List<Path>) ctx.get(PROVIDED_CP).orElse(List.of());
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
    static Task kspStep(BuildPlanner.Ctx cx, PluginBuild.Declarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        boolean compact = cx.compact();
        // Plugin-contributed sources (protoc output, variant extra-src) must exist before the
        // round and join its source roots — a contributed @Module/@Entity is processor input
        // like any hand-written one.
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        requires.addAll(sourceGenStepSteps(pluginDecls));
        return Task.builder("ksp")
                .stage(BuildStage.COMPILE)
                .label("KSP")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp = (List<Path>) ctx.require(PROCESSOR_CP);
                    var split = KspProcessors.split(processorCp);
                    ctx.put(JAVAC_PROCESSOR_CP, split.javac());
                    if (split.ksp().isEmpty()) {
                        ctx.label("no KSP processors");
                        ctx.progress(1);
                        return;
                    }
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path outBase = kspOutBase(layout);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
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
                    if (!rerun
                            && FreshnessStamp.isFresh(
                                    outBase, BuildStamps.KSP, stampInputs, stampCp, ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // cache/stamp skip — token tick
                        ctx.label("up to date");
                        ctx.progress(1);
                        return;
                    }

                    ctx.label("KSP: " + split.ksp().size() + " processor jar(s)");
                    JkBuild project = ctx.require(PROJECT);
                    String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), project);
                    if (kotlinVersion == null || kotlinVersion.isBlank()) {
                        kotlinVersion = KotlinResolver.DEFAULT_VERSION;
                    }
                    List<Path> kspClasspath;
                    Path stdlib;
                    try {
                        RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
                        String kspVersion = KspResolver.discoverVersion(repos);
                        kspClasspath = KspResolver.resolveClasspath(repos, cas, kspVersion);
                        stdlib = KotlinBtaResolver.resolveStdlib(repos, cas, kotlinVersion);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted resolving KSP", e);
                    }

                    // A stale round's outputs must not survive into the source union.
                    for (String sub : List.of("kotlin", "java", "classes", "resources")) {
                        cc.jumpkick.host.PathUtil.deleteRecursively(outBase.resolve(sub));
                    }
                    Files.createDirectories(outBase.resolve("caches"));

                    List<Path> srcRoots = new ArrayList<>(
                            compact
                                    ? List.of(in.dir().resolve("src"))
                                    : List.of(
                                            in.dir().resolve("src/main/kotlin"),
                                            in.dir().resolve("src/main/java")));
                    // Plugin-contributed source dirs (protoc output, generated code) and
                    // [build] extra-src roots (variant overlays) are processor input like any
                    // hand-written source.
                    srcRoots.addAll(pluginContributedSourceDirs(ctx.require(LAYOUT), pluginDecls));
                    srcRoots.addAll(CompileSupport.extraSrcDirs(project, in.dir()));
                    List<Path> ktRoots = new ArrayList<>();
                    for (Path root : srcRoots) {
                        if (Files.isDirectory(root)) ktRoots.add(root);
                    }
                    List<Path> libs = new ArrayList<>(classpath);
                    libs.add(stdlib);

                    String languageVersion = majorMinor(kotlinVersion);
                    // KSP is jk's tool: it runs on jk's own runtime, not the project's pinned
                    // JDK (same rule as every plugin — requirements.md "plugin host"). AGP runs
                    // KSP in the Gradle daemon's JVM the same way; the project JDK stays the
                    // -jdk-home cross-compile input below.
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
                    cmd.add("-resource-output-dir="
                            + outBase.resolve("resources").toAbsolutePath());
                    cmd.add("-language-version=" + languageVersion);
                    cmd.add("-api-version=" + languageVersion);
                    cmd.add("-jvm-target=" + CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)));
                    cmd.add("-jdk-home=" + javaHome.toAbsolutePath());
                    cmd.add("-libraries=" + Classpaths.join(libs));
                    // Processor options: plugin-contributed ([[contribute.compiler-args]] ksp
                    // Hilt's superclass-validation toggle) plus project-declared ([build]
                    // ksp-options — Room's schemaLocation; last wins, so the project overrides).
                    // KSP's map syntax joins entries with the platform path separator, same as
                    // its list args; relative option paths resolve against the module dir (the
                    // KSP process CWD).
                    List<String> kspOptions = new ArrayList<>(
                            PluginContributions.kspOptions(project, in.dir(), lockModules(ctx.require(LOCKFILE))));
                    kspOptions.addAll(project.build().kspOptions());
                    if (!kspOptions.isEmpty()) {
                        cmd.add("-processor-options=" + String.join(Classpaths.SEPARATOR, kspOptions));
                    }
                    // The trailing processor classpath is the WHOLE [processor-dependencies]
                    // closure — a provider jar (room-compiler) loads its own deps from it.
                    cmd.add(Classpaths.join(processorCp));

                    ProcessBuilder pb =
                            new ProcessBuilder(cmd).directory(in.dir().toFile()).redirectErrorStream(true);
                    Process proc = JobWorkers.start(pb);
                    // Read on a drainer thread and bound the wait: on an internal error KSP's JVM
                    // can linger (non-daemon compiler pools survive the main thread's exception),
                    // which would hang a plain readAllBytes forever.
                    StringBuilder captured = new StringBuilder();
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
                            ctx.error("ksp", "KSP timed out after 15 minutes\n" + captured);
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
                        ctx.error("ksp", output.isBlank() ? ("KSP exited " + exit) : output);
                        throw new RuntimeException("KSP processing failed");
                    }
                    // A green round still has things to say. Processor `logger.warn`/`info` is how
                    // an annotation-driven framework explains what it did and what to do
                    // differently; dropping it on success meant guidance only ever appeared once
                    // the build was already broken. Surfaced the same way javac
                    // diagnostics are, so -q/-v behave consistently.
                    for (BuildPlanner.KspDiagnostic diagnostic : kspDiagnostics(output)) {
                        ctx.warn(diagnostic.severity(), diagnostic.message());
                    }
                    FreshnessStamp.write(
                            outBase, BuildStamps.KSP, "ksp", "", stampInputs, stampCp, ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The Java source roots kotlinc reads declarations from in a mixed module: the hand-written
     * root plus the KSP round's generated-Java dir (Hilt components are Java — a Kotlin class
     * extending a generated base must resolve it during Kotlin analysis).
     */
    static List<Path> kotlinJavaSourceRoots(
            boolean mixedWithJava, boolean compact, Path dir, BuildLayout layout, PluginBuild.Declarations decls) {
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
