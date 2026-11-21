// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.JavacRunner;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.WorkerCompileDriver;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoMirrors;
import cc.jumpkick.resolver.NaiveResolver;
import cc.jumpkick.resolver.Resolution;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.BuildLogicGroovyHost;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.GroovyHostJar;
import cc.jumpkick.runtime.base.KotlinPluginSetup;
import cc.jumpkick.runtime.base.ResourceToolJar;
import cc.jumpkick.script.ScriptHeader;
import cc.jumpkick.script.ScriptHeaderParser;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.tool.JarManifest;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Script/jar preparation for {@code jk tool run <file>}: parse header, resolve deps, compile (or
 * inspect a jar). Exec stays client-side; terminal state carries {@link #MAIN_CLASS}/{@link
 * #CLASSPATH}/etc.
 */
public final class ScriptPlans {

    private ScriptPlans() {}

    // Cross-step keys (mode-specific, but all live in the same record).
    static final BuildPlanKey<ScriptHeader> HEADER = BuildPlanKey.scalar("script-header", ScriptHeader.class);
    public static final BuildPlanKey<Path> CLASSES_DIR = BuildPlanKey.scalar("classes-dir", Path.class);

    private static final BuildPlanKey<List<Path>> WORKER_CP = BuildPlanKey.list("kotlin-worker-cp", Path.class);

    public static final BuildPlanKey<Path> KT_STDLIB = BuildPlanKey.scalar("kotlin-stdlib", Path.class);
    public static final BuildPlanKey<String> MAIN_CLASS = BuildPlanKey.scalar("main-class", String.class);

    public static final BuildPlanKey<List<Path>> CLASSPATH = BuildPlanKey.list("classpath", Path.class);

    private static final BuildPlanKey<List<Dependency>> JAR_DECLARED_DEPS =
            BuildPlanKey.list("jar-declared-deps", Dependency.class);

    /** The finished plan's classpath. */
    public static List<Path> classpathOf(BuildPlan plan) {
        return plan.get(CLASSPATH).orElse(List.of());
    }

    /**
     * {@code classes/} for a script's compiled output: one {@link CacheTree#TOOL_SCRIPTS} entry per
     * {@link ScriptCacheKey}, collected once nothing has used it for 30 days.
     */
    static Path classesDirFor(Path cacheDir, String key) {
        return CacheTree.TOOL_SCRIPTS.under(cacheDir).resolve(key).resolve("classes");
    }

    // --- .java -----------------------------------------------------------

    /** {@code parse-script → resolve-deps → compile-java} for a {@code .java} script. */
    public static BuildPlan javaScriptBuildPlan(Path script, Path cacheDir, URI repoUrl, boolean forceRecompile)
            throws IOException {
        return javaScriptBuildPlan(script, cacheDir, repoUrl, forceRecompile, List.of());
    }

    /** As above with {@code extraDeps} — alias/{@code --with} injections joining the header's deps. */
    public static BuildPlan javaScriptBuildPlan(
            Path script, Path cacheDir, @Nullable URI repoUrl, boolean forceRecompile, List<Dependency> extraDeps)
            throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        ScriptHeader header =
                withExtras(ScriptHeaderParser.parse(new String(bytes, StandardCharsets.UTF_8)), extraDeps);
        Path classesDir = classesDirFor(cacheDir, ScriptCacheKey.of(script, bytes, header, extraDeps, ""));
        String mainClass = header.main() != null ? header.main() : simpleMainClassName(script, ".java");

        Task resolveDeps = Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = JkStores.storeCas();
                    Http http = new Http();
                    RepoGroup repos = buildRepos(header, repoUrl, http, cas);
                    try {
                        List<Path> classpath = resolveClasspath(header.deps(), repos);
                        ctx.put(CLASSPATH, classpath);
                    } catch (RuntimeException e) {
                        ctx.error("resolve", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Task compile = Task.builder(TaskNames.COMPILE_JAVA)
                .stage(BuildStage.COMPILE)
                .kind(TaskKind.CPU)
                .requires(TaskNames.RESOLVE_DEPS)
                .ticks(1)
                .execute(ctx -> {
                    boolean rerun =
                            forceRecompile || SessionContext.current().config().rebuildOr(false);
                    if (!rerun && Files.exists(classesDir.resolve(mainClass.replace('.', '/') + ".class"))) {
                        ctx.label("cache hit (" + mainClass + ".class)");
                        materializeFiles(script, header, classesDir);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("javac " + script.getFileName());
                    Files.createDirectories(classesDir);
                    List<Path> cp = ctx.require(CLASSPATH);
                    CompileResult result = compileJava(script, header, classesDir, cp);
                    if (!result.success()) {
                        for (var d : result.diagnostics()) {
                            ctx.error(
                                    "javac",
                                    d.severity()
                                            + " "
                                            + (d.source() != null ? d.source().getFileName() : "<unknown>")
                                            + ":"
                                            + d.line()
                                            + ": "
                                            + d.message());
                        }
                        throw new RuntimeException("javac failed");
                    }
                    materializeFiles(script, header, classesDir);
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("run-java")
                .stateKeys(HEADER, MAIN_CLASS, CLASSES_DIR, CLASSPATH)
                .addTask(parseScriptStep(script, header, mainClass, classesDir, cacheDir))
                .addTask(resolveDeps)
                .addTask(compile)
                .build();
    }

    @KeyOwner(
            step = TaskNames.COMPILE_JAVA,
            unpaired = "a script's compile, planned outside any module: nothing forecasts it")
    private static CompileResult compileJava(Path script, ScriptHeader header, Path classesDir, List<Path> classpath)
            throws IOException {
        int release = ScriptCacheKey.release(header);
        CompileRequest request = CompileRequest.builder()
                .sources(withDeclaredSources(script, header))
                .classpath(classpath)
                .outputDir(classesDir)
                .release(release)
                .extraOptions(header.javacOptions())
                .javaHome(JavaHomes.resolveJavaHome(scriptDir(script)))
                .build();
        return new JavacRunner().compile(request);
    }

    // --- .kt -------------------------------------------------------------

    /** {@code parse-script → (resolve-deps ∥ resolve-kotlinc) → compile-kt} for a {@code .kt} script. */
    public static BuildPlan kotlinScriptBuildPlan(Path script, Path cacheDir, URI repoUrl, boolean forceRecompile)
            throws IOException {
        return kotlinScriptBuildPlan(script, cacheDir, repoUrl, forceRecompile, List.of());
    }

    /** As above with {@code extraDeps} — alias/{@code --with} injections joining the header's deps. */
    public static BuildPlan kotlinScriptBuildPlan(
            Path script, Path cacheDir, @Nullable URI repoUrl, boolean forceRecompile, List<Dependency> extraDeps)
            throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        // parseKotlin: // directives + @file:DependsOn/@file:Repository annotations.
        ScriptHeader header =
                withExtras(ScriptHeaderParser.parseKotlin(new String(bytes, StandardCharsets.UTF_8)), extraDeps);
        Path classesDir = classesDirFor(
                cacheDir, ScriptCacheKey.of(script, bytes, header, extraDeps, "kotlin:" + kotlinVersion(header)));
        String mainClass = header.main() != null ? header.main() : kotlinMainClassName(script);

        return BuildPlan.builder("run-kt")
                .stateKeys(HEADER, MAIN_CLASS, CLASSES_DIR, CLASSPATH, WORKER_CP, KT_STDLIB)
                .addTask(parseScriptStep(script, header, mainClass, classesDir, cacheDir))
                .addTask(resolveScriptDepsStep(header, repoUrl))
                .addTask(resolveKotlincStep(header, repoUrl))
                .addTask(
                        compileKotlinScriptStep(script, bytes, header, classesDir, mainClass, cacheDir, forceRecompile))
                .build();
    }

    private static Task resolveScriptDepsStep(ScriptHeader header, @Nullable URI repoUrl) {
        // resolve-deps and resolve-kotlinc are independent and slow; run
        // them in parallel.
        return Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = JkStores.storeCas();
                    Http http = new Http();
                    RepoGroup repos = buildRepos(header, repoUrl, http, cas);
                    try {
                        List<Path> classpath = resolveClasspath(header.deps(), repos);
                        ctx.put(CLASSPATH, classpath);
                    } catch (RuntimeException e) {
                        ctx.error("resolve", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }

    private static Task resolveKotlincStep(ScriptHeader header, @Nullable URI repoUrl) {
        return Task.builder(TaskNames.RESOLVE_KOTLINC)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label(
                            header.kotlinVersion() != null
                                    ? "resolve kotlin compiler " + header.kotlinVersion()
                                    : "resolve kotlin compiler");
                    Cas cas = JkStores.storeCas();
                    RepoGroup repos = buildRepos(header, repoUrl, new Http(), cas);
                    try {
                        KotlinPluginSetup.Prepared prep = KotlinPluginSetup.prepare(repos, cas, header.kotlinVersion());
                        ctx.put(WORKER_CP, prep.workerClasspath());
                        ctx.put(KT_STDLIB, prep.stdlib());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted resolving the Kotlin compiler", e);
                    } catch (RuntimeException e) {
                        ctx.error("kotlin", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** kotlinc over the script and its declared sources; a cache hit only re-materializes the files. */
    @KeyOwner(
            step = TaskNames.COMPILE_KOTLIN,
            unpaired = "a script's compile, planned outside any module: nothing forecasts it")
    private static Task compileKotlinScriptStep(
            Path script,
            byte[] bytes,
            ScriptHeader header,
            Path classesDir,
            String mainClass,
            Path cacheDir,
            boolean forceRecompile) {
        return Task.builder(TaskNames.COMPILE_KOTLIN)
                .stage(BuildStage.COMPILE)
                .kind(TaskKind.CPU)
                .requires(TaskNames.RESOLVE_DEPS, TaskNames.RESOLVE_KOTLINC)
                .ticks(1)
                .execute(ctx -> {
                    boolean rerun =
                            forceRecompile || SessionContext.current().config().rebuildOr(false);
                    if (!rerun && Files.exists(classesDir.resolve(mainClass.replace('.', '/') + ".class"))) {
                        ctx.label("cache hit (" + mainClass + ".class)");
                        materializeFiles(script, header, classesDir);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("kotlinc " + script.getFileName());
                    Files.createDirectories(classesDir);
                    List<Path> compileCp = new ArrayList<>(ctx.require(CLASSPATH));
                    compileCp.add(ctx.require(KT_STDLIB));
                    KotlincRequest req = kotlincRequest(
                            script,
                            header,
                            kotlinSources(script, bytes, header, classesDir),
                            compileCp,
                            classesDir,
                            cacheDir,
                            ctx.require(WORKER_CP));
                    CompileResult result = WorkerCompileDriver.compile(req, WorkerEnv.strict());
                    if (!result.success()) {
                        ctx.error("kotlinc", result.output());
                        throw new RuntimeException("kotlinc failed");
                    }
                    materializeFiles(script, header, classesDir);
                    ctx.progress(1);
                })
                .build();
    }

    // --- .kts ------------------------------------------------------------

    /** The launcher a compiled {@code .kts} runs through; it depends on nothing. */
    static final ResourceToolJar KTS_LAUNCHER =
            new ResourceToolJar("kts-launcher", "jk-kts-launcher.jar", List.of("JkKtsMain.java"));

    /** {@link #KTS_LAUNCHER}'s main class. */
    static final String KTS_MAIN = "JkKtsMain";

    /** The superclass kotlinc gives a {@code .kts} compiled with the standard script definition. */
    private static final String KTS_SCRIPT_BASE = "kotlin/script/templates/standard/ScriptTemplateWithArgs";

    /** Version slot of {@link #KTS_LAUNCHER}'s tools directory: it compiles against nothing but the JDK. */
    private static final String JDK_ONLY = "jdk";

    /** The Kotlin distribution a {@code .kts} compiles with. */
    private static final BuildPlanKey<Path> KOTLIN_HOME = BuildPlanKey.scalar("kotlin-home", Path.class);

    /** How long compiling one script may take. */
    private static final Duration SCRIPT_COMPILE_TIMEOUT = Duration.ofMinutes(10);

    /**
     * {@code parse-script → (resolve-deps ∥ resolve-kotlinc) → compile-kotlin} for a {@code .kts}
     * script: compiled once into {@code tool-scripts/} and run by {@link #KTS_MAIN}. The compile is
     * the Kotlin distribution's own {@code K2JVMCompiler -d}, which compiles a script with the
     * standard definition ({@code args}) and never evaluates it. The script's declared deps
     * ({@code @file:DependsOn} / {@code //DEPS} / {@code //jk dep}) resolve through jk; the compiler
     * sees a copy with those annotations commented out.
     */
    public static BuildPlan ktsScriptBuildPlan(
            Path script, Path cacheDir, @Nullable URI repoUrl, boolean forceRecompile, List<Dependency> extraDeps)
            throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        ScriptHeader header =
                withExtras(ScriptHeaderParser.parseKotlin(new String(bytes, StandardCharsets.UTF_8)), extraDeps);
        String toolchain = "kotlin:" + kotlinVersion(header) + "|launcher:" + KTS_LAUNCHER.hash(JDK_ONLY);
        Path classesDir = classesDirFor(cacheDir, ScriptCacheKey.of(script, bytes, header, extraDeps, toolchain));

        Task resolveKotlin = Task.builder(TaskNames.RESOLVE_KOTLINC)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("provision kotlinc");
                    Path home = CompileToolchain.resolveKotlinHome(
                            cacheDir, header.kotlinVersion(), ctx::output, new ToolPlanProgress(ctx));
                    ctx.put(KOTLIN_HOME, home);
                    ctx.put(KT_STDLIB, home.resolve("lib/kotlin-stdlib.jar"));
                    ctx.progress(1);
                })
                .build();

        // After the Kotlin step: the run classpath carries the distribution's own script runtime.
        Task resolveDeps = Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.RESOLVE_KOTLINC)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    RepoGroup repos = buildRepos(header, repoUrl, new Http(), JkStores.storeCas());
                    try {
                        List<Path> classpath = new ArrayList<>(resolveClasspath(header.deps(), repos));
                        classpath.add(ctx.require(KOTLIN_HOME).resolve("lib/kotlin-script-runtime.jar"));
                        classpath.add(KTS_LAUNCHER.ensure(List.of(), JDK_ONLY));
                        ctx.put(CLASSPATH, classpath);
                    } catch (RuntimeException e) {
                        ctx.error("resolve", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("run-kts")
                .stateKeys(HEADER, MAIN_CLASS, CLASSES_DIR, CLASSPATH, KOTLIN_HOME, KT_STDLIB)
                .addTask(parseScriptStep(script, header, KTS_MAIN, classesDir, cacheDir))
                .addTask(resolveKotlin)
                .addTask(resolveDeps)
                .addTask(compileKtsStep(script, bytes, header, classesDir, forceRecompile))
                .build();
    }

    /** kotlinc over the script, then the marker naming its class; a cache hit only re-materializes the files. */
    @KeyOwner(
            step = TaskNames.COMPILE_KOTLIN,
            unpaired = "a script's compile, planned outside any module: nothing forecasts it")
    private static Task compileKtsStep(
            Path script, byte[] bytes, ScriptHeader header, Path classesDir, boolean forceRecompile) {
        return Task.builder(TaskNames.COMPILE_KOTLIN)
                .stage(BuildStage.COMPILE)
                .kind(TaskKind.CPU)
                .requires(TaskNames.RESOLVE_DEPS, TaskNames.RESOLVE_KOTLINC)
                .ticks(1)
                .execute(ctx -> {
                    if (cacheHit(forceRecompile, classesDir)) {
                        ctx.label("cache hit (" + script.getFileName() + ")");
                        materializeFiles(script, header, classesDir);
                    } else {
                        ctx.label("kotlinc " + script.getFileName());
                        PathUtil.deleteRecursively(classesDir);
                        Files.createDirectories(classesDir);
                        List<Path> compileCp = new ArrayList<>(ctx.require(CLASSPATH));
                        compileCp.add(ctx.require(KT_STDLIB));
                        int jvmTarget = header.release() != null
                                ? header.release()
                                : Runtime.version().feature();
                        Path javaHome = JavaHomes.runningJavaHome();
                        List<String> cmd = new ArrayList<>(List.of(
                                JdkFingerprint.java(javaHome).toString(),
                                "--sun-misc-unsafe-memory-access=allow",
                                "-cp",
                                ctx.require(KOTLIN_HOME)
                                        .resolve("lib/kotlin-compiler.jar")
                                        .toString(),
                                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                                "-nowarn",
                                "-no-stdlib",
                                "-no-reflect",
                                "-jvm-target",
                                String.valueOf(CompileSupport.kotlinJvmTarget(
                                        jvmTarget, Runtime.version().feature())),
                                "-cp",
                                Classpaths.join(compileCp),
                                "-d",
                                classesDir.toString()));
                        for (Path source : kotlinSources(script, bytes, header, classesDir)) cmd.add(source.toString());
                        ProcessBuilder pb = JavaHomes.underJdk(new ProcessBuilder(cmd), javaHome);
                        pb.redirectErrorStream(true);
                        Processes.Captured compiled = Processes.capture(JobWorkers.start(pb), SCRIPT_COMPILE_TIMEOUT);
                        if (compiled.timedOut() || compiled.exit() != 0) {
                            String out = compiled.output().strip();
                            ctx.error(
                                    "kotlinc",
                                    compiled.timedOut() ? "kotlinc timed out" : out.isEmpty() ? "kotlinc failed" : out);
                            throw new RuntimeException("kotlinc failed");
                        }
                        String scriptClass = ktsScriptClass(classesDir, script);
                        if (scriptClass == null) {
                            ctx.error("kotlinc", "kotlinc produced no script class for " + script.getFileName());
                            throw new RuntimeException("no script class");
                        }
                        materializeFiles(script, header, classesDir);
                        writeScriptMarker(classesDir, scriptClass);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The class kotlinc compiled {@code script} into: the top-level class in {@code classesDir} whose
     * superclass is the standard script base and whose source file is the script. Read from the class
     * files rather than re-deriving kotlinc's naming rule, so an odd file name cannot drift from it.
     */
    static @Nullable String ktsScriptClass(Path classesDir, Path script) throws IOException {
        String sourceName = script.getFileName().toString();
        List<String> found = new ArrayList<>();
        PathUtil.forEachRegularFile(classesDir, (file, attrs) -> {
            String name = classesDir.relativize(file).toString().replace('\\', '/');
            if (!name.endsWith(".class") || name.contains("$") || name.startsWith("META-INF/")) return;
            ClassModel model = ClassFile.of().parse(file);
            boolean isScript = model.superclass()
                    .map(c -> c.asInternalName().equals(KTS_SCRIPT_BASE))
                    .orElse(false);
            boolean fromIt = model.findAttribute(Attributes.sourceFile())
                    .map(a -> a.sourceFile().stringValue().equals(sourceName))
                    .orElse(false);
            if (isScript && fromIt) found.add(model.thisClass().asInternalName().replace('/', '.'));
        });
        return found.size() == 1 ? found.getFirst() : null;
    }

    // --- .groovy ---------------------------------------------------------

    /**
     * {@code parse-script → resolve-deps → compile-groovy} for a {@code .groovy} script: compiled once
     * into {@code tool-scripts/<key>/classes/script.jar} with its deps on the compiler's classpath,
     * and run by {@link GroovyHostJar#MAIN}. Groovy is the pinned build-logic Groovy unless a
     * {@code //GROOVY} line names a version.
     */
    public static BuildPlan groovyScriptBuildPlan(
            Path script, Path cacheDir, @Nullable URI repoUrl, boolean forceRecompile, List<Dependency> extraDeps)
            throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        ScriptHeader header =
                withExtras(ScriptHeaderParser.parse(new String(bytes, StandardCharsets.UTF_8)), extraDeps);
        String groovyVersion =
                header.groovyVersion() != null ? header.groovyVersion() : BuildLogicGroovyHost.GROOVY_VER;
        String toolchain = "groovy:" + groovyVersion + "|host:" + GroovyHostJar.JAR.hash(groovyVersion);
        Path classesDir = classesDirFor(cacheDir, ScriptCacheKey.of(script, bytes, header, extraDeps, toolchain));

        Task resolveDeps = Task.builder(TaskNames.RESOLVE_DEPS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = JkStores.storeCas();
                    RepoGroup repos = buildRepos(header, repoUrl, new Http(), cas);
                    try {
                        List<Path> groovy = groovyRuntime(groovyVersion, repos);
                        List<Path> classpath = new ArrayList<>(groovy);
                        for (Path dep : resolveClasspath(header.deps(), repos)) {
                            if (!classpath.contains(dep)) classpath.add(dep);
                        }
                        classpath.add(GroovyHostJar.ensure(groovy.getFirst(), groovyVersion));
                        ctx.put(CLASSPATH, classpath);
                    } catch (RuntimeException e) {
                        ctx.error("resolve", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("run-groovy")
                .stateKeys(HEADER, MAIN_CLASS, CLASSES_DIR, CLASSPATH)
                .addTask(parseScriptStep(script, header, GroovyHostJar.MAIN, classesDir, cacheDir))
                .addTask(resolveDeps)
                .addTask(compileGroovyStep(script, header, classesDir, forceRecompile))
                .build();
    }

    /**
     * Groovy's own jars for {@code version}, groovy.jar first: the pinned, digest-verified set (with
     * Ant and Ivy, so {@code @Grab} works) at jk's version, else {@code groovy} at the version asked.
     */
    private static List<Path> groovyRuntime(String version, RepoGroup repos) throws IOException, InterruptedException {
        if (version.equals(BuildLogicGroovyHost.GROOVY_VER))
            return List.of(BuildLogicGroovyHost.ensureJars(() -> repos));
        // Groovy moved from org.codehaus.groovy to org.apache.groovy with 4.0.
        String group = Character.isDigit(version.charAt(0)) && version.charAt(0) < '4' && version.indexOf('.') == 1
                ? "org.codehaus.groovy"
                : "org.apache.groovy";
        return resolveClasspath(List.of(ScriptHeaderParser.parseDependency(group + ":groovy:" + version)), repos);
    }

    /** The runner's {@code compile} into {@code classes/script.jar}, then the marker; a hit re-materializes. */
    @KeyOwner(
            step = TaskNames.COMPILE_GROOVY,
            unpaired = "a script's compile, planned outside any module: nothing forecasts it")
    private static Task compileGroovyStep(Path script, ScriptHeader header, Path classesDir, boolean forceRecompile) {
        return Task.builder(TaskNames.COMPILE_GROOVY)
                .stage(BuildStage.COMPILE)
                .kind(TaskKind.CPU)
                .requires(TaskNames.RESOLVE_DEPS)
                .ticks(1)
                .execute(ctx -> {
                    if (cacheHit(forceRecompile, classesDir)) {
                        ctx.label("cache hit (" + script.getFileName() + ")");
                        materializeFiles(script, header, classesDir);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("groovyc " + script.getFileName());
                    PathUtil.deleteRecursively(classesDir);
                    Files.createDirectories(classesDir);
                    Path jar = classesDir.resolve(GROOVY_SCRIPT_JAR);
                    Path javaHome = JavaHomes.runningJavaHome();
                    ProcessBuilder pb = JavaHomes.underJdk(
                            new ProcessBuilder(
                                    JdkFingerprint.java(javaHome).toString(),
                                    "-cp",
                                    Classpaths.join(ctx.require(CLASSPATH)),
                                    GroovyHostJar.HOST,
                                    "compile",
                                    script.toAbsolutePath().toString(),
                                    jar.toString()),
                            javaHome);
                    pb.redirectErrorStream(true);
                    Processes.Captured compiled = Processes.capture(JobWorkers.start(pb), SCRIPT_COMPILE_TIMEOUT);
                    if (compiled.timedOut() || compiled.exit() != 0 || !Files.isRegularFile(jar)) {
                        String out = compiled.output().strip();
                        ctx.error(
                                "groovyc",
                                compiled.timedOut() ? "groovyc timed out" : out.isEmpty() ? "groovyc failed" : out);
                        throw new RuntimeException("groovyc failed");
                    }
                    String scriptClass =
                            JarManifest.mainClass(jar).orElseThrow(() -> new IOException(jar + " names no Main-Class"));
                    materializeFiles(script, header, classesDir);
                    writeScriptMarker(classesDir, scriptClass);
                    ctx.progress(1);
                })
                .build();
    }

    /** Where a compiled Groovy tool script's classes live inside its classes directory; the launcher reads it there. */
    static final String GROOVY_SCRIPT_JAR = "script.jar";

    /** The marker a launcher reads to find the script class: one line, its binary name. */
    static final String SCRIPT_MARKER = "META-INF/jk-script";

    /** Write {@link #SCRIPT_MARKER} last, so its presence means the compile finished. */
    private static void writeScriptMarker(Path classesDir, String scriptClass) throws IOException {
        AtomicWrites.replace(classesDir.resolve(SCRIPT_MARKER), scriptClass + "\n");
    }

    /** Whether a launcher-run script's compiled classes can be used as they are. */
    private static boolean cacheHit(boolean forceRecompile, Path classesDir) {
        boolean rerun = forceRecompile || SessionContext.current().config().rebuildOr(false);
        return !rerun && Files.isRegularFile(classesDir.resolve(SCRIPT_MARKER));
    }

    // --- .jar ------------------------------------------------------------

    /** {@code inspect-jar → resolve-jar-deps} for a prebuilt jar (manifest main + embedded-POM deps). */
    public static BuildPlan jarBuildPlan(Path jar, Path cacheDir, @Nullable URI repoUrl) {
        Task inspect = Task.builder(TaskNames.INSPECT_JAR)
                .stage(BuildStage.RESOLVE)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("read manifest + embedded poms");
                    Optional<String> mainClass = JarManifest.mainClass(jar);
                    if (mainClass.isEmpty()) {
                        ctx.error("no-main-class", jar + " has no Main-Class in its MANIFEST.MF");
                        throw new RuntimeException("missing Main-Class");
                    }
                    ctx.put(MAIN_CLASS, mainClass.get());

                    List<Dependency> declaredDeps = new ArrayList<>();
                    Cas cas = JkStores.storeCas();
                    PomImporter poms = new PomImporter(RepoGroupBuilder.buildDefault(repoUrl, cas), cas);
                    for (JarManifest.EmbeddedPom p : JarManifest.scanEmbeddedPoms(jar)) {
                        if (!p.hasPomXml()) continue;
                        try {
                            var imported = poms.importFromBytes(Objects.requireNonNull(p.pomXml()));
                            var byScope = imported.jkBuild().dependencies().byScope();
                            for (Scope scope : EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME)) {
                                List<Dependency> scoped = byScope.get(scope);
                                if (scoped != null) declaredDeps.addAll(scoped);
                            }
                        } catch (RuntimeException e) {
                            ctx.warn(
                                    "pom-parse",
                                    "failed to parse embedded pom for " + p.coord() + ": " + e.getMessage());
                        }
                    }
                    if (JarManifest.hasModuleInfo(jar)) {
                        ctx.warn("jpms", jar.getFileName() + " is a JPMS module; running it from the classpath");
                    }
                    ctx.put(JAR_DECLARED_DEPS, declaredDeps);
                    ctx.progress(1);
                })
                .build();

        Task resolveJarDeps = Task.builder(TaskNames.RESOLVE_JAR_DEPS)
                .stage(BuildStage.RESOLVE)
                .kind(TaskKind.IO)
                .requires(TaskNames.INSPECT_JAR)
                .ticks(1)
                .execute(ctx -> {
                    List<Dependency> declaredDeps = ctx.require(JAR_DECLARED_DEPS);
                    List<Path> classpath = new ArrayList<>();
                    classpath.add(jar);
                    if (declaredDeps.isEmpty()) {
                        ctx.label("no embedded deps");
                        ctx.put(CLASSPATH, classpath);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("fetch " + declaredDeps.size() + " embedded deps");
                    Files.createDirectories(cacheDir);
                    Cas cas = JkStores.storeCas();
                    RepoGroup repos = RepoGroupBuilder.buildDefault(repoUrl, cas);
                    try {
                        classpath.addAll(resolveClasspath(declaredDeps, repos));
                    } catch (RuntimeException e) {
                        ctx.error("resolve", Errors.text(e));
                        throw e;
                    }
                    ctx.put(CLASSPATH, classpath);
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("run-jar")
                .stateKeys(MAIN_CLASS, JAR_DECLARED_DEPS, CLASSPATH)
                .addTask(inspect)
                .addTask(resolveJarDeps)
                .build();
    }

    /** {@code parse-script}: the header, the main class and the classes directory, and the entry stamped as used. */
    private static Task parseScriptStep(
            Path script, ScriptHeader header, String mainClass, Path classesDir, Path cacheDir) {
        return Task.builder(TaskNames.PARSE_SCRIPT)
                .stage(BuildStage.RESOLVE)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse " + script.getFileName());
                    ctx.put(HEADER, header);
                    ctx.put(MAIN_CLASS, mainClass);
                    ctx.put(CLASSES_DIR, classesDir);
                    Files.createDirectories(cacheDir);
                    ScriptCacheKey.stampUse(classesDir);
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The script and its {@code //SOURCES}, the script replaced by a line-preserving copy with its
     * {@code @file:DependsOn}/{@code @file:Repository} commented out when it has any: jk resolved
     * them, and kotlinc cannot compile them. The copy keeps the file name, which names a script's class.
     */
    private static List<Path> kotlinSources(Path script, byte[] bytes, ScriptHeader header, Path classesDir)
            throws IOException {
        List<Path> sources = withDeclaredSources(script, header);
        String neutralized = ScriptHeaderParser.neutralizeKotlinAnnotations(new String(bytes, StandardCharsets.UTF_8));
        if (neutralized != null) {
            Path copy = Files.createDirectories(classesDir.resolveSibling("src"))
                    .resolve(script.getFileName().toString());
            Files.writeString(copy, neutralized, StandardCharsets.UTF_8);
            sources.set(0, copy);
        }
        return sources;
    }

    /** The Kotlin a script compiles with: its {@code //KOTLIN} line's, else jk's default. */
    private static String kotlinVersion(ScriptHeader header) {
        String v = header.kotlinVersion();
        return v == null || v.isBlank() ? KotlinResolver.DEFAULT_VERSION : v;
    }

    /**
     * The worker compile of a Kotlin script's {@code sources} onto {@code compileCp} (its deps and the
     * version-matched stdlib, paired with {@code -no-stdlib}: the in-process compiler has no Kotlin
     * home to add one).
     */
    private static KotlincRequest kotlincRequest(
            Path script,
            ScriptHeader header,
            List<Path> sources,
            List<Path> compileCp,
            Path classesDir,
            Path cacheDir,
            List<Path> workerCp) {
        int jvmTarget =
                header.release() != null ? header.release() : Runtime.version().feature();
        Path workingDir = ActionKey.stateDir(
                ActionTree.INCREMENTAL_KOTLIN.under(CacheTree.ACTIONS.under(cacheDir)), "script", classesDir);
        return KotlincRequest.builder()
                .sources(sources)
                .classpath(compileCp)
                .outputDir(classesDir)
                .jvmTarget(CompileSupport.kotlinJvmTarget(
                        jvmTarget, Runtime.version().feature()))
                .workerClasspath(workerCp)
                .javaHome(JavaHomes.resolveJavaHome(scriptDir(script)))
                .workingDir(workingDir)
                .extraArgs(List.of("-no-stdlib"))
                .build();
    }

    // --- shared helpers --------------------------------------------------

    /** The header with {@code extraDeps} appended to its dependency list. */
    private static ScriptHeader withExtras(ScriptHeader header, List<Dependency> extraDeps) {
        if (extraDeps.isEmpty()) return header;
        List<Dependency> deps = new ArrayList<>(header.deps());
        deps.addAll(extraDeps);
        return new ScriptHeader(
                deps,
                header.release(),
                header.repos(),
                header.features(),
                header.javacOptions(),
                header.javaOptions(),
                header.sources(),
                header.files(),
                header.main(),
                header.gav(),
                header.description(),
                header.kotlinVersion(),
                header.groovyVersion());
    }

    /** The script plus its {@code //SOURCES} declarations, resolved against the script's dir. */
    private static List<Path> withDeclaredSources(Path script, ScriptHeader header) {
        List<Path> sources = new ArrayList<>();
        sources.add(script.toAbsolutePath());
        Path dir = Objects.requireNonNull(script.toAbsolutePath().getParent(), "script dir");
        for (String s : header.sources()) {
            Path p = dir.resolve(s).normalize();
            if (!sources.contains(p)) sources.add(p);
        }
        return sources;
    }

    /**
     * Copy each {@code //FILES target=source} resource into {@code classesDir} so it rides the
     * runtime classpath (JBang semantics). Source is relative to the script's dir; a bare name
     * means target == source. Runs on cache hits too — the resources must exist even when the
     * compile was skipped.
     */
    private static void materializeFiles(Path script, ScriptHeader header, Path classesDir) throws IOException {
        if (header.files().isEmpty()) return;
        Path dir = Objects.requireNonNull(script.toAbsolutePath().getParent(), "script dir");
        for (String spec : header.files()) {
            int eq = spec.indexOf('=');
            String target = eq >= 0 ? spec.substring(0, eq) : spec;
            String source = eq >= 0 ? spec.substring(eq + 1) : spec;
            Path from = dir.resolve(source).normalize();
            Path to = classesDir.resolve(target).normalize();
            if (!to.startsWith(classesDir)) {
                throw new IOException("//FILES target escapes the classpath dir: " + spec);
            }
            if (!Files.isRegularFile(from)) {
                throw new IOException("//FILES source not found: " + from + " (from `" + spec + "`)");
            }
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Kotlin's convention for top-level {@code main()}: {@code Foo.kt} → {@code FooKt}. */
    private static String kotlinMainClassName(Path script) {
        String name = script.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".kt")) {
            name = name.substring(0, name.length() - 3);
        }
        if (name.isEmpty()) return "Kt";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Kt";
    }

    /** {@code Foo.java} → {@code Foo}. (Matches the existing convention.) */
    private static String simpleMainClassName(Path script, String suffix) {
        String name = script.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length())
                : name;
    }

    /**
     * The repositories a script resolves against: the ones its header names, each routed through
     * the mirror that stands in for it as a POM-declared repository is; else the user's
     * repositories over the public baseline ({@link RepoGroupBuilder#buildDefault}); else the one
     * URL a test pins.
     */
    static RepoGroup buildRepos(ScriptHeader header, @Nullable URI repoUrl, Http http, Cas cas) {
        if (repoUrl != null || header.repos().isEmpty()) return RepoGroupBuilder.buildDefault(repoUrl, cas);
        List<MavenRepo> list = new ArrayList<>();
        for (URI uri : header.repos()) {
            list.add(RepoMirrors.apply(new MavenRepo("script-repo-" + list.size(), uri, http, cas)));
        }
        return new RepoGroup(list);
    }

    private static List<Path> resolveClasspath(List<Dependency> deps, RepoGroup repos)
            throws IOException, InterruptedException {
        if (deps.isEmpty()) return List.of();
        Resolution resolution = new NaiveResolver(new EffectivePomBuilder(repos)).resolve(deps);
        Set<String> ordered = new LinkedHashSet<>();
        for (Dependency d : deps) ordered.add(d.module());
        for (Resolution.ResolvedModule m : resolution.modules().values()) ordered.add(m.module());

        List<Path> jars = new ArrayList<>();
        for (String module : ordered) {
            Resolution.ResolvedModule m = resolution.modules().get(module);
            if (m == null) continue;
            Coordinate coord = m.coordinate();
            jars.add(repos.tryFetchArtifact(coord)
                    .orElseThrow(() ->
                            new MavenRepo.ArtifactNotFoundException("jar not found in any declared repo: " + coord))
                    .fetched()
                    .cachePath());
        }
        return jars;
    }

    /** The directory a script's JDK is resolved from; an absolute script path always has one. */
    private static Path scriptDir(Path script) {
        return Objects.requireNonNull(script.toAbsolutePath().getParent(), "script has no parent directory");
    }
}
