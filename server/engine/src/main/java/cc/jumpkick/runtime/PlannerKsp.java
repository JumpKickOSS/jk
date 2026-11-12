// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerPlugin.beforeCompile;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.KspResolver;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.CompileWarnings;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.task.PortablePath;
import cc.jumpkick.task.ProjectedFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What the KSP rounds and their readers derive alike: the output dirs and generated-source
 * unions, the source roots, a round's digest, stamp and key, and the plugin source-contribution
 * helpers. The rounds themselves run in {@link KspArm}.
 */
public final class PlannerKsp {

    private PlannerKsp() {}

    /** The step names of every source-generating plugin step the compilers must wait for. */
    static List<String> sourceGenStepSteps(@Nullable PluginDeclarations decls) {
        List<String> out = new ArrayList<>();
        if (decls != null) {
            for (TaskDecl step : decls.steps()) {
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
    static List<Path> pluginContributedSources(BuildLayout layout, @Nullable PluginDeclarations decls, String suffix)
            throws IOException {
        return contributed(layout, decls, suffix, TaskDecl::contributesSources);
    }

    /**
     * Plugin-contributed generated test sources ({@code contributesTestSources}): files with
     * {@code suffix} under each contributed scratch dir, for compile-test's source list.
     */
    static List<Path> pluginContributedTestSources(
            BuildLayout layout, @Nullable PluginDeclarations decls, String suffix) throws IOException {
        return contributed(layout, decls, suffix, TaskDecl::contributesTestSources);
    }

    /**
     * The generated test sources compile-test reads beside the suites' own: the plugin-contributed
     * ones, then the test KSP round's. A test round's bytecode and resources come first among the
     * roots mirrored into the test classes ({@link #outputRoots}), so a hand-written resource wins.
     */
    static List<Path> generatedTestSources(BuildLayout layout, @Nullable PluginDeclarations decls, String suffix)
            throws IOException {
        List<Path> out = pluginContributedTestSources(layout, decls, suffix);
        out.addAll(generatedSources(kspTestOutBase(layout), suffix));
        return out;
    }

    private static List<Path> contributed(
            BuildLayout layout,
            @Nullable PluginDeclarations decls,
            String suffix,
            Function<TaskDecl, List<String>> lane)
            throws IOException {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (TaskDecl step : decls.steps()) {
            for (String rel : lane.apply(step)) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                List<Path> projected = ProjectedFiles.files(dir, suffix);
                if (projected != null) {
                    out.addAll(projected);
                    continue;
                }
                if (!Files.isDirectory(dir)) continue;
                List<Path> found = new ArrayList<>();
                PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                    if (file.toString().endsWith(suffix)) found.add(file);
                });
                found.sort(null);
                out.addAll(found);
            }
        }
        return out;
    }

    /**
     * Lay the sources each before-compile plugin step of {@code decls} contributes at its roots, for
     * a forecast of a module whose step scratch is gone: the build restores the scratch from the
     * step's record before any compile reads it, and the forecast prices the step as restored, so
     * the files of the record the step's pointer names are what every compile keys on. A step with
     * no record projects nothing: it runs, and what it writes cannot be described.
     */
    static void projectContributedSources(
            ProjectedFiles projection,
            BuildLayout layout,
            @Nullable PluginDeclarations decls,
            ActionCache actionCache) {
        if (decls == null) return;
        for (TaskDecl step : decls.steps()) {
            if (!beforeCompile(step)) continue;
            List<String> roots = new ArrayList<>(step.contributesSources());
            roots.addAll(step.contributesTestSources());
            if (roots.isEmpty()) continue;
            Path scratch = PluginBuild.taskScratch(layout, step.name());
            if (Files.isDirectory(scratch)) continue;
            Map<String, String> outputs;
            try {
                var record = actionCache.lastFor(ActionKey.qualifiedTaskId("plugin-" + step.name(), scratch));
                if (record.isEmpty()) continue;
                outputs = record.get().outputs();
            } catch (IOException e) {
                Log.debug("projectContributedSources: " + step.name() + " keys on the tree as it is", e);
                continue;
            }
            for (String rel : roots) {
                String prefix = rel.endsWith("/") ? rel : rel + "/";
                Map<String, String> files = new TreeMap<>();
                for (Map.Entry<String, String> e : outputs.entrySet()) {
                    if (e.getKey().startsWith(prefix)) files.put(e.getKey().substring(prefix.length()), e.getValue());
                }
                projection.project(scratch.resolve(rel), files);
            }
        }
    }

    /** Plugin steps' declared source-contribution dirs (existing or projected ones only). */
    static List<Path> pluginContributedSourceDirs(BuildLayout layout, @Nullable PluginDeclarations decls) {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (TaskDecl step : decls.steps()) {
            for (String rel : step.contributesSources()) {
                Path dir = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (ProjectedFiles.isDirectory(dir)) out.add(dir);
            }
        }
        return out;
    }

    /**
     * Plugin steps' declared test-classpath contributions that exist: a directory is one entry, a
     * file lists one absolute entry per non-blank line.
     */
    static List<Path> pluginTestClasspath(BuildLayout layout, @Nullable PluginDeclarations decls) throws IOException {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (TaskDecl step : decls.steps()) {
            for (String rel : step.contributesTestClasspath()) {
                Path path = PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                if (Files.isDirectory(path)) {
                    out.add(path);
                } else if (Files.isRegularFile(path)) {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) out.add(Path.of(line.strip()));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The arguments plugin steps contributed to the forked test JVM ({@code contributesTestJvmArgs}):
     * every non-blank line of each declared file, in declaration order. A declared file the step
     * did not write contributes nothing.
     */
    static List<String> pluginTestJvmArgs(BuildLayout layout, @Nullable PluginDeclarations decls) throws IOException {
        List<String> out = new ArrayList<>();
        if (decls == null) return out;
        for (TaskDecl step : decls.steps()) {
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

    /** The subdirectories a round writes under its output base, cleared before each round. */
    static final List<String> OUTPUT_DIRS = List.of("kotlin", "java", "classes", "resources");

    /** The main round's output base: {@code target/ksp}. */
    static Path kspOutBase(BuildLayout layout) {
        return kspOutBase(layout.moduleTargetDir());
    }

    /** The main round's output base under a module's {@code target/} directory. */
    static Path kspOutBase(Path moduleTargetDir) {
        return moduleTargetDir.resolve("ksp");
    }

    /** The test round's output base: {@code target/ksp-test}. */
    static Path kspTestOutBase(BuildLayout layout) {
        return layout.moduleTargetDir().resolve("ksp-test");
    }

    /** Files with {@code suffix} under the main KSP output tree, sorted — empty when no round ran. */
    static List<Path> kspGeneratedSources(BuildLayout layout, String suffix) throws IOException {
        return generatedSources(kspOutBase(layout), suffix);
    }

    /**
     * Files with {@code suffix} under a round's generated-source dirs, sorted — empty when it never
     * ran. A {@link ProjectedFiles projected} dir lists the files the build restores there.
     */
    static List<Path> generatedSources(Path outBase, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String lang : List.of("kotlin", "java")) {
            Path dir = outBase.resolve(lang);
            List<Path> projected = ProjectedFiles.files(dir, suffix);
            if (projected != null) {
                out.addAll(projected);
                continue;
            }
            if (!Files.isDirectory(dir)) continue;
            List<Path> found = new ArrayList<>();
            PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                if (file.toString().endsWith(suffix)) found.add(file);
            });
            found.sort(null);
            out.addAll(found);
        }
        return out;
    }

    /**
     * The bytecode and resources a round wrote under {@code outBase} that exist, in that order. A
     * processor that emits bytecode (Micronaut's bean definitions) or a resource (a service
     * registration) writes them here rather than as sources, so nothing compiles them: the resource
     * copy mirrors these roots into the classes tree like any resource root, which puts them in the
     * tree's key, on every classpath that reads the tree, and in the jar. A {@link ProjectedFiles
     * projected} root exists when the build restores a file into it.
     */
    static List<Path> outputRoots(Path outBase) {
        List<Path> out = new ArrayList<>();
        for (String sub : List.of("classes", "resources")) {
            Path dir = outBase.resolve(sub);
            if (ProjectedFiles.isDirectory(dir)) out.add(dir);
        }
        return out;
    }

    /**
     * The main round's output roots on disk ({@link #outputRoots}) for a module that runs the round,
     * none for one that does not, whose {@code target/ksp} is a retired round's the build removes.
     */
    static List<Path> liveOutputRoots(JkBuild project, Path dir) {
        if (!runsMainRound(project, dir)) return List.of();
        return outputRoots(kspOutBase(BuildLayout.moduleTargetDir(dir)));
    }

    /**
     * Whether the module runs a main round: a Kotlin module that declares {@code
     * [processor-dependencies]}. The planner plans the {@code ksp} step on exactly these, and every
     * reader of {@code target/ksp}, the forecast's included, reads it only then: a module that drops
     * its processors keeps no generated code alive.
     */
    static boolean runsMainRound(JkBuild project, Path dir) {
        return ModuleShape.of(project, dir, null).ksp();
    }

    /**
     * Remove the output of each round the module can no longer run, before anything reads it: the
     * main round's when no {@code ksp} step is planned, the test round's when the module declares
     * no processor at all. What a removed processor generated must not compile, ship or test.
     */
    static void dropRetiredRounds(ModuleShape shape, BuildLayout layout, JkBuild project) throws IOException {
        if (!shape.ksp()) PathUtil.deleteRecursively(kspOutBase(layout));
        // A test round runs only in a Kotlin module that declares a processor compile-test reads.
        if (!shape.kotlin() || !ModuleShape.hasTestProcessorDeps(project)) dropTestRound(layout);
    }

    /**
     * The hand-written sources the main round reads, for its stamp and its action key: the
     * module's Kotlin and Java (and Scala, which shares the Java list) plus the plugin-contributed
     * {@code .kt} and {@code .java}, so an extra-src or protoc edit re-runs it. One derivation for
     * the build and the forecast.
     */
    static List<Path> mainRoundInputs(
            List<Path> kotlin, List<Path> javaAndScala, BuildLayout layout, @Nullable PluginDeclarations decls)
            throws IOException {
        List<Path> inputs = new ArrayList<>(kotlin);
        inputs.addAll(javaAndScala);
        inputs.addAll(pluginContributedSources(layout, decls, ".kt"));
        inputs.addAll(pluginContributedSources(layout, decls, ".java"));
        return inputs;
    }

    /**
     * A round's option digest ({@link #kspStampDigest}) over the module's Kotlin and KSP versions:
     * what its stamp records and its action key hashes.
     */
    @KeyOwner(step = TaskNames.KSP)
    static String roundDigest(JkBuild project, Lockfile lock, Path dir, Path javaHome, int release) throws IOException {
        return kspStampDigest(
                project, lock, dir, kotlinVersion(lock, project), KspResolver.versionFor(project), javaHome, release);
    }

    /**
     * Whether the freshness stamp under a round's {@code outBase} vouches for it: the hand-written
     * sources it reads, its classpath and KSP jars, the release and the round's option digest
     * ({@link #kspStampDigest}).
     */
    @KeyOwner(step = TaskNames.KSP)
    static boolean roundStampFresh(
            Path outBase, List<Path> stampInputs, List<Path> classpath, List<Path> kspJars, int release, String digest)
            throws IOException {
        return FreshnessStamp.isFresh(
                outBase, BuildStamps.KSP, stampInputs, stampClasspath(classpath, kspJars), release, digest);
    }

    /** A round's stamped classpath: its compile classpath, then its KSP jars. */
    static List<Path> stampClasspath(List<Path> classpath, List<Path> kspJars) {
        List<Path> out = new ArrayList<>(classpath);
        out.addAll(kspJars);
        return out;
    }

    /** The Kotlin release the module compiles with, which the round analyses at. */
    static String kotlinVersion(Lockfile lock, JkBuild project) {
        String version = CompileToolchain.kotlinVersionFor(lock, project);
        return version == null || version.isBlank() ? KotlinResolver.DEFAULT_VERSION : version;
    }

    /** The action-cache task id of the round writing under {@code outBase}. */
    static String roundTaskId(Path outBase) {
        return ActionKey.qualifiedTaskId(TaskNames.KSP, outBase);
    }

    /**
     * The action key of a round's outputs: its options digest ({@link #kspStampDigest}), each
     * source by path and content, each classpath entry by the identity {@code classpath} reads it
     * with, and each processor-path entry by content. The build reads the classpath on disk; the
     * forecast reads a wiped sibling tree as the tree the build restores, so both name one record.
     */
    static String roundKey(
            Path outBase,
            String optionsDigest,
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorCp,
            ClasspathFingerprint.EntryIdentity identity)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(roundTaskId(outBase)).append('\n');
        sb.append("jk:").append(BuildIdentity.cacheKeyVersion()).append('\n');
        sb.append("options:").append(optionsDigest).append('\n');
        List<Path> sorted = new ArrayList<>();
        for (Path source : sources) sorted.add(source.toAbsolutePath().normalize());
        sorted.sort(Comparator.comparing(Path::toString));
        for (Path source : sorted) {
            sb.append("source:")
                    .append(PortablePath.of(source))
                    .append(':')
                    .append(FileHashMemo.contentHash(source))
                    .append('\n');
        }
        for (Path entry : classpath) sb.append("cp:").append(identity.of(entry)).append('\n');
        for (Path entry : processorCp)
            sb.append("pp:").append(ClasspathFingerprint.entry(entry)).append('\n');
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * The files a round's record ({@code outputs}, its output-relative paths to content sha) places
     * in the classes tree, tree-relative path to sha: its bytecode, then its resources, the order
     * {@code copy-resources} mirrors the two roots in.
     */
    static Map<String, String> classesTreeOutputs(Map<String, String> outputs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String sub : List.of("classes/", "resources/")) {
            outputs.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(sub))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.put(e.getKey().substring(sub.length()), e.getValue()));
        }
        return out;
    }

    /** Remove a test round's output, which no round will refresh; true when there was any. */
    static boolean dropTestRound(BuildLayout layout) throws IOException {
        Files.deleteIfExists(CompileWarnings.currentFile(
                layout.buildDir().resolve("incremental"), CompileWarnings.roundFile(TaskNames.COMPILE_TEST)));
        Path outBase = kspTestOutBase(layout);
        if (!Files.exists(outBase)) return false;
        PathUtil.deleteRecursively(outBase);
        return true;
    }

    /**
     * What a round reads under {@code roots} so that it sees exactly the {@code kept} sources: a
     * root, or a directory under it, holding no Kotlin or Java file outside {@code kept} goes whole;
     * any other directory gives its kept files one by one and its subdirectories judged the same
     * way. KSP takes files among its source roots as readily as directories, so a {@code [test]
     * exclude-src} rule removes a file from the round as it does from the test compile, and without
     * one the roots go as they are.
     */
    static List<Path> keptSourceRoots(List<Path> roots, List<Path> kept) throws IOException {
        Set<Path> keep = new HashSet<>();
        for (Path source : kept) keep.add(source.toAbsolutePath().normalize());
        List<Path> out = new ArrayList<>();
        for (Path root : roots) out.addAll(cover(root, keep));
        return out;
    }

    private static List<Path> cover(Path dir, Set<Path> keep) throws IOException {
        Map<Path, Boolean> children = new TreeMap<>();
        PathUtil.forEachChild(dir, (child, attrs) -> {
            children.put(child, attrs.isDirectory());
            return true;
        });
        List<Path> parts = new ArrayList<>();
        boolean whole = true;
        for (Map.Entry<Path, Boolean> entry : children.entrySet()) {
            Path child = entry.getKey();
            if (entry.getValue()) {
                List<Path> sub = cover(child, keep);
                whole &= sub.size() == 1 && sub.getFirst().equals(child);
                parts.addAll(sub);
            } else if (isRoundSource(child)) {
                if (keep.contains(child.toAbsolutePath().normalize())) {
                    parts.add(child);
                } else {
                    whole = false;
                }
            }
        }
        return whole ? List.of(dir) : parts;
    }

    private static boolean isRoundSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".kt") || name.endsWith(".java");
    }

    /**
     * The test round's source roots that exist: each compiled suite's Kotlin and Java roots, the
     * {@code [test] extra-src} directories and the plugin-contributed test source dirs.
     */
    static List<Path> testSourceRoots(
            JkBuild project,
            Path moduleDir,
            boolean compact,
            List<String> compiledSuites,
            BuildLayout layout,
            @Nullable PluginDeclarations decls) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (String suite : compiledSuites) {
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
        }
        for (String rel : project.build().testExtraSrc())
            roots.add(moduleDir.resolve(rel).normalize());
        if (decls != null) {
            for (TaskDecl step : decls.steps()) {
                for (String rel : step.contributesTestSources()) {
                    roots.add(PluginBuild.taskScratch(layout, step.name()).resolve(rel));
                }
            }
        }
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) out.add(root);
        }
        return out;
    }

    /**
     * The round's processor options ({@code key=value}): plugin-contributed ([[contribute.compiler-args]]
     * ksp — Hilt's superclass-validation toggle) first, then the project's {@code [build]
     * ksp-options} (Room's schemaLocation), so the project overrides.
     */
    static List<String> kspOptions(JkBuild project, Path moduleDir, Lockfile lock) {
        List<String> options =
                new ArrayList<>(PluginContributions.kspOptions(project, moduleDir, lock.classpathModules()));
        options.addAll(project.build().kspOptions());
        return options;
    }

    /**
     * Digest of the round's option-bearing inputs for its freshness stamp: the Kotlin version
     * (language and API level), the KSP2 release, the JDK, the module name and every processor
     * option.
     */
    @KeyOwner(step = TaskNames.KSP)
    static String kspStampDigest(
            JkBuild project,
            Lockfile lock,
            Path moduleDir,
            String kotlinVersion,
            String kspVersion,
            Path javaHome,
            int release)
            throws IOException {
        List<String> parts = new ArrayList<>();
        parts.add("kotlin:" + kotlinVersion);
        parts.add("ksp:" + kspVersion);
        parts.add("jvmTarget:" + CompileSupport.kotlinJvmTarget(release, JvmOptions.hostFeature(javaHome)));
        parts.add("jdk:" + ActionKey.jdkToken(javaHome));
        parts.add("moduleName:" + project.project().name());
        for (String option : kspOptions(project, moduleDir, lock)) parts.add("option:" + option);
        return FreshnessStamp.optionsDigest(parts);
    }

    /**
     * The round's source roots: the module's own, plus plugin-contributed source dirs (protoc
     * output, generated code) and [build] extra-src roots (variant overlays) — processor input
     * like any hand-written source. Only roots that exist.
     */
    static List<Path> kspSourceRoots(
            BuildPlanner.Inputs in,
            JkBuild project,
            BuildLayout layout,
            @Nullable PluginDeclarations pluginDecls,
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
    static List<String> kspCommand(
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
     * KSP runs third-party processors, so it starts with a compiler worker's environment, never the
     * daemon's whole one; the bytes are decoded once, so a character split across reads survives.
     */
    static String runKspProcess(TaskContext ctx, Path dir, WorkerEnv env, List<String> cmd) throws Exception {
        ProcessBuilder pb = PluginProcess.builder(cmd, WorkerEnv.strict().with(env.compilerEnvironment()))
                .directory(dir.toFile());
        Process proc = JobWorkers.start(pb);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Supplier<String> captured = () -> bytes.toString(StandardCharsets.UTF_8);
        // Byte pump for KSP's output; reads no session.
        Thread drainer = new Thread(() -> {
            try (var in2 = proc.getInputStream()) {
                in2.transferTo(bytes);
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
                ctx.error(TaskNames.KSP, "KSP timed out after 15 minutes\n" + captured.get());
                throw new RuntimeException("KSP timed out");
            }
            exit = proc.exitValue();
            drainer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new RuntimeException("interrupted waiting for KSP", e);
        }
        String output = captured.get();
        if (exit != 0) {
            String killed = WorkerContainment.failure(exit, "");
            String body = output.isBlank() ? (killed.isEmpty() ? "KSP exited " + exit : "KSP " + killed) : output;
            if (!output.isBlank() && !killed.isEmpty()) body = killed + "\n" + output;
            ctx.error(TaskNames.KSP, body);
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
            @Nullable PluginDeclarations decls,
            boolean ksp) {
        if (!mixedWithJava) return null;
        List<Path> roots = new ArrayList<>();
        roots.add(compact ? dir.resolve("src") : dir.resolve("src/main/java"));
        Path kspJava = kspOutBase(layout).resolve("java");
        if (ksp && ProjectedFiles.isDirectory(kspJava)) roots.add(kspJava);
        // Plugin-contributed generated dirs can carry Java that Kotlin sources reference
        // (protoc: the --kotlin_out DSL wraps its own --java_out message classes).
        if (decls != null) {
            for (TaskDecl step : decls.steps()) {
                for (String rel : step.contributesSources()) {
                    Path contributed =
                            PluginBuild.taskScratch(layout, step.name()).resolve(rel);
                    if (ProjectedFiles.isDirectory(contributed)) roots.add(contributed);
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
