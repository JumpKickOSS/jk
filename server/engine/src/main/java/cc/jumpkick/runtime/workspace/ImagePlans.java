// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ManifestImage;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.MainClassScanner;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.PluginBuild;
import cc.jumpkick.runtime.TestSupport;
import cc.jumpkick.runtime.base.ImageCredentials;
import cc.jumpkick.runtime.base.PluginLaunch;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk image} plan: full build plus OCI tail (Jib plugin or docker/podman Dockerfile
 * mode). Success details ride structured keys ({@link #IMAGE_REF}/{@link #TARBALL_PATH}/…).
 */
public final class ImagePlans {

    private ImagePlans() {}

    public static final BuildPlanKey<ImageConfig> CONFIG = BuildPlanKey.scalar("image-config", ImageConfig.class);
    public static final BuildPlanKey<Path> TARBALL_PATH = BuildPlanKey.scalar("tarball-path", Path.class);

    private static final BuildPlanKey<RuntimeJars> RUNTIME_JARS =
            BuildPlanKey.scalar("runtime-jars", RuntimeJars.class);

    public static final BuildPlanKey<String> IMAGE_REF = BuildPlanKey.scalar("image-ref", String.class);

    /**
     * The base image template used when no {@code image.base} is set in the project or global
     * config. {@code {java-major-version}} is replaced with the project's resolved JDK major.
     */
    public static final String DEFAULT_BASE_TEMPLATE =
            "bellsoft/liberica-runtime-container:jre-{java-major-version}-slim-glibc";

    /**
     * Build the image plan for {@code projectDir}: the core plan (via {@link
     * BuildPlanner#coreBuilder}) plus the image-plan/write-image tail. {@code tarballArg} is
     * tri-state exactly like {@code --tarball}'s optional value: {@code null} (no tarball), {@code
     * ""} (default layout path), or an explicit path. {@code decorate} is the request-level Inputs
     * decoration the workspace orchestrator applies so the IMAGE branch honors the same knobs as
     * PACKAGE; {@code null} = none.
     */
    public static BuildPlan imageBuildPlan(
            Path projectDir,
            Path cache,
            @Nullable Path jdksDir,
            boolean skipTests,
            boolean verbose,
            @Nullable String mainClass,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg,
            @Nullable UnaryOperator<BuildPlanner.Inputs> decorate) {
        Path jkBuildPath = ManifestPaths.manifestIn(projectDir);
        Path lockFile = LockPaths.lockFile(projectDir);
        boolean compact = ModuleLayout.isCompact(projectDir);
        int estimatedTestCount = TestSupport.estimateAllSuiteTestCount(projectDir, compact);
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                projectDir,
                cache,
                jkBuildPath,
                lockFile,
                projectDir,
                1,
                estimatedTestCount,
                null,
                jdksDir,
                skipTests,
                verbose,
                false,
                false,
                Set.of(),
                SessionContext.current());
        if (decorate != null) inputs = decorate.apply(inputs);

        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
        builder.stateKeys(CONFIG, TARBALL_PATH, RUNTIME_JARS, IMAGE_REF)
                .addTask(imagePlanStep(
                        projectDir, cache, jkBuildPath, mainClass, registry, tag, tarballArg, dockerExecutableArg))
                .addTask(writeImageStep(projectDir, cache, mainClass));
        return builder.terminal(TaskNames.WRITE_IMAGE).build();
    }

    /** Resolve the image config and, in Jib mode, the main class and the dependency layers. */
    private static Task imagePlanStep(
            Path projectDir,
            Path cache,
            Path jkBuildPath,
            @Nullable String mainClass,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg) {
        return Task.builder(TaskNames.IMAGE_PLAN)
                .stage(BuildStage.IMAGE)
                .requires(TaskNames.PACKAGE_JAR)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve image config");
                    JkBuild project = ctx.require(BuildPlanner.PROJECT);
                    BuildLayout layout = ctx.require(BuildPlanner.LAYOUT);
                    Path tarballPath = resolveTarballPath(tarballArg, layout);
                    if (tarballPath != null) ctx.put(TARBALL_PATH, tarballPath);
                    ImageConfig config = buildConfig(jkBuildPath, project, registry, tag, dockerExecutableArg);
                    ctx.put(CONFIG, config);
                    boolean dockerfileMode =
                            config.dockerFile() != null && !config.dockerFile().isBlank();
                    if (!dockerfileMode) {
                        // Jib mode: main class required; load dep jars for classpath layering.
                        String chosen = resolveMainClass(mainClass, config, project, projectDir);
                        if (chosen == null || chosen.isBlank()) {
                            ctx.error("no-main", "no main class — pass --main, set image.main, or set project.main.");
                            throw new RuntimeException("missing main class");
                        }
                        // This module's production runtime closure — the same set the fat jar
                        // nests — layered by how often each part churns. Never the whole workspace
                        // lock, and never the CAS alone: with [m2] integration on, the jars live
                        // in ~/.m2.
                        ctx.put(
                                RUNTIME_JARS,
                                runtimeJars(layout.moduleRoot(), project, LockPaths.lockFile(layout.moduleRoot())));
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** Dockerfile mode shells out to docker/podman; Jib mode restores from the cache or forks the worker. */
    private static Task writeImageStep(Path projectDir, Path cache, @Nullable String mainClass) {
        return Task.builder(TaskNames.WRITE_IMAGE)
                .stage(BuildStage.IMAGE)
                .kind(TaskKind.IO)
                .requires(TaskNames.IMAGE_PLAN)
                .weight(() -> EffortWeights.ociWeight(projectDir))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPlanner.PROJECT);
                    BuildLayout layout = ctx.require(BuildPlanner.LAYOUT);
                    ImageConfig config = ctx.require(CONFIG);
                    Path tarballPath = ctx.get(TARBALL_PATH).orElse(null);

                    // Dockerfile mode: shell out to docker/podman build; skip Jib entirely.
                    if (config.dockerFile() != null && !config.dockerFile().isBlank()) {
                        boolean daemonMode = tarballPath == null
                                && (config.registry() == null
                                        || config.registry().isBlank());
                        String exe = config.dockerExecutable() != null ? config.dockerExecutable() : "docker";
                        ctx.label((daemonMode ? "build into " : "build + push via ") + exe + " (" + config.dockerFile()
                                + ")");
                        try {
                            String ref = runDockerfileBuild(ctx, config, projectDir, tarballPath, project);
                            ctx.put(IMAGE_REF, ref);
                        } catch (RuntimeException e) {
                            ctx.error("image", Errors.text(e));
                            throw e;
                        }
                        ctx.progress(1);
                        return;
                    }

                    RuntimeJars jars = ctx.require(RUNTIME_JARS);
                    Path classesDir = PluginBuild.shape(project, projectDir)
                                    .map(sh -> sh.layeredImage())
                                    .orElse(false)
                            ? layout.classesDir()
                            : null;

                    String chosen = resolveMainClass(mainClass, config, project, projectDir);

                    // Which worker built it, by content: the artifact id is a compile-time constant
                    // and the release version does not move between local builds, so neither can
                    // tell a rebuilt worker from the one whose output is already in the cache.
                    Path workerJar = PluginJar.IMAGE_BUILDER.locate(JkStores.storeCas());
                    ImageWrite.restoreOrBuild(
                            ctx,
                            project,
                            layout,
                            config,
                            cache,
                            tarballPath,
                            jars,
                            classesDir,
                            chosen,
                            workerJar,
                            base -> runImageWorker(
                                    cache,
                                    workerJar,
                                    project,
                                    layout,
                                    config,
                                    base,
                                    chosen,
                                    jars,
                                    classesDir,
                                    tarballPath));
                })
                .build();
    }

    /**
     * What the image tail of {@code plan} did — the reference it pushed or loaded, the tarball it
     * wrote, and the repository:tag it resolved — or {@code null} when the plan never reached its
     * image step. Daemon mode is the default when there is neither a tarball nor a registry.
     */
    public static ModuleOutcome.@Nullable Image outcomeOf(BuildPlan plan) {
        ImageConfig config = plan.get(CONFIG).orElse(null);
        Path tarball = plan.get(TARBALL_PATH).orElse(null);
        String reference = plan.get(IMAGE_REF).orElse(null);
        if (config == null && tarball == null && reference == null) return null;
        JkBuild project = plan.get(BuildPlanner.PROJECT).orElse(null);
        boolean daemonMode = tarball == null
                && (config == null
                        || config.registry() == null
                        || config.registry().isBlank());
        String daemon = !daemonMode
                ? null
                : config != null && config.dockerExecutable() != null ? config.dockerExecutable() : "docker";
        String name = project == null ? null : project.project().name();
        String version = project == null ? null : project.project().version();
        if (config != null && name != null && version != null) {
            name = config.repository(name);
            version = config.tagOr(version);
        }
        return new ModuleOutcome.Image(reference, tarball != null ? tarball.toString() : null, name, version, daemon);
    }

    private static @Nullable Path resolveTarballPath(@Nullable String tarballArg, BuildLayout layout) {
        if (tarballArg == null) return null;
        if (tarballArg.isBlank()) return layout.ociImageTar();
        return Path.of(tarballArg);
    }

    /**
     * Build the resolved {@link ImageConfig} for this invocation.
     *
     * <ol>
     *   <li>Parse project-local {@code [image]} from {@code jk.toml}.
     *   <li>Parse user-global {@code [image]} from {@code ~/.jk/config.toml} (if present).
     *   <li>Merge: project values win over global values.
     *   <li>Substitute {@code {java-major-version}} in {@code image.base} with the project's JDK
     *       major (falling back to 21 when undeclared).
     *   <li>Apply {@link #DEFAULT_BASE_TEMPLATE} when no base is set after all layers.
     * </ol>
     */
    private static ImageConfig buildConfig(
            Path jkBuild,
            JkBuild project,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String dockerExecutableArg)
            throws IOException {
        // Merge user-global [image] from ~/.jk/config.toml underneath the project layer.
        ManifestImage.ImageConfigData data =
                ManifestImage.merge(JkBuildParser.imageConfig(jkBuild), GlobalConfig.image());

        // Resolve the java major version for template substitution.
        int javaMajor = project.project().javaRelease();
        if (javaMajor <= 0) javaMajor = 21; // LTS fallback

        // Apply {java-major-version} substitution and default base.
        String base = data.base();
        if (base == null || base.isBlank()) base = DEFAULT_BASE_TEMPLATE;
        base = base.replace("{java-major-version}", String.valueOf(javaMajor));

        // Docker/Podman executable: CLI flag > image.docker-executable > auto-detect.
        String dockerExe = dockerExecutableArg != null ? dockerExecutableArg : data.dockerExecutable();
        if (dockerExe == null || dockerExe.isBlank()) dockerExe = detectDockerExecutable();

        return new ImageConfig(
                base,
                data.name(),
                data.user(),
                data.ports(),
                data.env(),
                data.labels(),
                registry != null ? registry : data.registry(),
                tag != null ? tag : data.tag(),
                data.platforms(),
                data.main(),
                dockerExe,
                data.dockerFile(),
                Boolean.TRUE.equals(data.aotCache()));
    }

    /**
     * The worker spec for one image build: every {@code [image]} value the worker needs, plus the
     * two the engine alone knows — the resolved {@code base} the action key was built from, and the
     * jk cache root the worker writes its base-JRE tree under.
     */
    public static SpecWriter imageWorkerSpec(
            Path cache,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            String base,
            @Nullable String chosen,
            RuntimeJars jars,
            @Nullable Path classesDir,
            @Nullable Path tarballPath)
            throws IOException {
        boolean daemonMode = tarballPath == null
                && (config.registry() == null || config.registry().isBlank());
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_IMAGE, null, "jk-image-builder")
                .configString("artifact", project.project().name())
                .configString("version", project.project().version())
                .configString("mainClass", chosen)
                .configString("mode", tarballPath != null ? "tarball" : daemonMode ? "daemon" : "push");
        if (base != null) sw.configString("base", base);
        ImageCredentials.write(sw, base, config, project, tarballPath == null && !daemonMode, layout.moduleRoot());
        // The jk cache root. The worker extracts a base JRE (50–200 MB) to train an AOT cache
        // against; that tree belongs under the root BASE_JRE's bound covers, not in the
        // module's target/, where nothing reclaims it and `jk clean` throws it away.
        sw.configString("jkCache", cache.toAbsolutePath().toString());
        if (config.name() != null) sw.configString("name", config.name());
        if (config.user() != null) sw.configString("user", config.user());
        if (config.registry() != null) sw.configString("registry", config.registry());
        if (config.tag() != null) sw.configString("tag", config.tag());
        if (tarballPath != null)
            sw.configString("tarball", tarballPath.toAbsolutePath().toString());
        if (config.dockerExecutable() != null) sw.configString("dockerExecutable", config.dockerExecutable());
        if (config.aotCache()) sw.configBool("aotCache", true);
        if (!config.ports().isEmpty()) {
            sw.configList("ports", config.ports().stream().map(String::valueOf).toList());
        }
        if (!config.env().isEmpty()) {
            sw.configList(
                    "env",
                    config.env().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList());
        }
        if (!config.labels().isEmpty()) {
            sw.configList(
                    "labels",
                    config.labels().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList());
        }
        if (!config.platforms().isEmpty()) sw.configList("platforms", config.platforms());
        sw.artifact(layout.mainJar());
        // Releases ship as the stable layer; snapshots and workspace siblings as the volatile one.
        for (Path dep : jars.releases()) sw.entry(jars.name(dep), dep, false, null);
        for (Path dep : jars.snapshots()) sw.entry(jars.name(dep), dep, true, null);
        for (Path dep : jars.siblings()) sw.entry(jars.name(dep), dep, true, null);
        if (classesDir != null) sw.layout(Map.of("classesDir", classesDir));
        // A packager that produced a complete runnable tree: ship that, not a lock-derived
        // classpath. Declared but missing is a hard error — falling back to the lock classpath
        // ships a broken image (Quarkus needs quarkus-run.jar, not Application on a 200-jar lock
        // classpath).
        var shape = PluginBuild.shape(project, layout.moduleRoot());
        String appDir = shape.map(sh -> sh.appDir()).orElse("");
        String appJar = shape.map(sh -> sh.appJar()).orElse("");
        if (!appDir.isBlank() && !appJar.isBlank()) {
            Path appRoot = layout.moduleTargetDir().resolve(appDir);
            if (!Files.isDirectory(appRoot)) {
                throw new RuntimeException("image needs the packager tree at "
                        + appRoot
                        + " (plugin packaging.app-dir="
                        + appDir
                        + ") — build the module first so "
                        + appJar
                        + " exists");
            }
            Path jarInTree = appRoot.resolve(appJar);
            if (!Files.isRegularFile(jarInTree)) {
                throw new RuntimeException(
                        "image packager tree is missing " + jarInTree + " (packaging.app-jar=" + appJar + ")");
            }
            sw.configString("appDir", appRoot.toAbsolutePath().toString());
            sw.configString("appJar", appJar);
        }
        return sw;
    }

    /**
     * Fork the image worker. {@code base} is the resolved (digest-pinned where the registry could
     * be asked) base reference the action key was built from — not {@code config.base()}, whose tag
     * may point somewhere else by the time Jib pulls.
     */
    private static String runImageWorker(
            Path cache,
            Path workerJar,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            String base,
            @Nullable String chosen,
            RuntimeJars jars,
            @Nullable Path classesDir,
            @Nullable Path tarballPath) {
        try {
            SpecWriter sw =
                    imageWorkerSpec(cache, project, layout, config, base, chosen, jars, classesDir, tarballPath);
            Path spec = ImageCredentials.newSpecFile();
            try {
                Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
                @Nullable String[] ref = {null};
                @Nullable String[] workerError = {null};
                StringBuilder diag = new StringBuilder();
                int exit = new PluginClient("##JKIM:")
                        .on(PluginProtocol.RESULT, json -> ref[0] = Jsonl.str(json, "ref"))
                        .on(PluginProtocol.ERROR, json -> workerError[0] = Jsonl.str(json, PluginProtocol.MESSAGE))
                        .passthrough(ln -> diag.append(ln).append('\n'))
                        .run(PluginLaunch.javaCommand(workerJar, spec));
                if (workerError[0] != null) throw new RuntimeException("image worker: " + workerError[0]);
                if (exit != 0) {
                    String d = diag.length() > 0 ? diag.toString().trim() : null;
                    throw new RuntimeException("image worker failed" + (d != null ? ": " + d : " (exit " + exit + ")"));
                }
                String built = ref[0];
                return built != null ? built : "";
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("image worker interrupted", e);
        }
    }

    /**
     * Build an OCI image using the project's Dockerfile. Runs {@code docker build -f <file> -t
     * <ref> [--platform <p>]* <projectDir>}, then (for tarball mode) {@code docker save}, or (for
     * push mode) {@code docker push}. Daemon mode requires no extra step — the build loads directly
     * into the local runtime. Build output streams via {@code ctx.output()} (over the wire, in the
     * engine-hosted case).
     */
    private static String runDockerfileBuild(
            TaskContext ctx, ImageConfig config, Path projectDir, @Nullable Path tarballPath, JkBuild project)
            throws IOException, InterruptedException {
        String exe = config.dockerExecutable() != null ? config.dockerExecutable() : "docker";
        Path dockerfile = projectDir.resolve(config.dockerFile()).normalize();
        String name = project.project().name();
        String version = project.project().version();
        String ref = config.targetReference(name, version);

        // docker build -f <dockerfile> -t <ref> [--platform <p>]* <context>
        List<String> buildCmd = new ArrayList<>();
        buildCmd.add(exe);
        buildCmd.add("build");
        buildCmd.add("-f");
        buildCmd.add(dockerfile.toString());
        buildCmd.add("-t");
        buildCmd.add(ref);
        for (String platform : config.platforms()) {
            buildCmd.add("--platform");
            buildCmd.add(platform);
        }
        buildCmd.add(projectDir.toString());

        runSubprocess(ctx, buildCmd, projectDir);

        if (tarballPath != null) {
            Files.createDirectories(tarballPath.getParent());
            runSubprocess(ctx, List.of(exe, "save", "-o", tarballPath.toString(), ref), projectDir);
        } else if (config.registry() != null && !config.registry().isBlank()) {
            runSubprocess(ctx, List.of(exe, "push", ref), projectDir);
        }
        return ref;
    }

    /** Run a subprocess, streaming each output line via {@code ctx.output()}. */
    private static void runSubprocess(TaskContext ctx, List<String> cmd, Path cwd)
            throws IOException, InterruptedException {
        Process p =
                JobWorkers.start(new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true));
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ctx.output(line);
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException(cmd.get(0) + " " + cmd.get(1) + " failed (exit " + exit + ")");
        }
    }

    /**
     * Auto-detect the local container runtime by probing {@code docker} then {@code podman} on
     * {@code PATH}. Returns null when neither responds: sending a made-up {@code "docker"}
     * downstream made the trainer's carefully written no-runtime diagnostic unreachable — the
     * plugin auto-detects (docker/podman/nerdctl) when nothing is configured.
     */
    private static @Nullable String detectDockerExecutable() {
        for (String candidate : new String[] {"docker", "podman"}) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception e) {
                Log.debug("detectDockerExecutable: Exception ignored", e);
            }
        }
        return null;
    }

    /**
     * Everything the OCI tarball is a function of, one token per input. The list is the definition
     * of the packaging cache's promise, so it is a named function rather than a literal inside the
     * task body — an input that is missing here is an image served from a stale cache.
     *
     * <p>{@code base} is the <em>resolved</em> base reference (see {@link BaseImageDigest}) and
     * {@code workerJar} is hashed by content: the plugin's artifact id is a compile-time constant
     * and the release version does not move between local builds, so neither identifies the worker
     * that actually produced the tarball. {@code appTree} is {@link #appTreeToken}'s value.
     */
    public static List<String> imageTokens(
            Path mainJar,
            RuntimeJars jars,
            @Nullable Path classesDir,
            @Nullable String mainClass,
            @Nullable String base,
            ImageConfig config,
            String appTree,
            Path workerJar)
            throws IOException {
        return List.of(
                "mainjar:" + ClasspathFingerprint.entry(mainJar),
                "deps:" + ClasspathFingerprint.of(jars.releases()),
                "snapshots:" + ClasspathFingerprint.of(jars.snapshots()),
                "siblings:" + ClasspathFingerprint.of(jars.siblings()),
                "classes:" + (classesDir == null ? "" : ClasspathFingerprint.entry(classesDir)),
                "main:" + mainClass,
                "base:" + base,
                "cfg:" + imageConfigToken(config),
                "apptree:" + appTree,
                "worker:" + ClasspathFingerprint.entry(workerJar));
    }

    /** Stable serialization of the image config for the packaging cache key. */
    private static String imageConfigToken(ImageConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("base=").append(c.base()).append(';');
        sb.append("user=").append(c.user()).append(';');
        sb.append("registry=").append(c.registry()).append(';');
        sb.append("tag=").append(c.tag()).append(';');
        sb.append("ports=").append(new TreeSet<>(c.ports())).append(';');
        sb.append("env=").append(new TreeMap<>(c.env())).append(';');
        sb.append("labels=").append(new TreeMap<>(c.labels())).append(';');
        sb.append("platforms=").append(new ArrayList<>(c.platforms())).append(';');
        // aot-cache changes the shipped layers (trained app tree + app.aot) and dockerFile
        // switches the build path entirely — both are part of what the tarball is a function of.
        sb.append("aot=").append(c.aotCache()).append(';');
        sb.append("dockerfile=").append(c.dockerFile()).append(';');
        return sb.toString();
    }

    /**
     * Resolve the main class to use for the OCI image entrypoint. Priority:
     *
     * <ol>
     *   <li>{@code --main} CLI flag
     *   <li>{@code image.main} in jk.toml
     *   <li>{@code [application].main} in jk.toml
     *   <li>The sole {@code [application].main} across all workspace modules (fails if more than one)
     * </ol>
     *
     * Returns {@code null} when no main class can be determined. Error text is plain — the client
     * renders (and themes) it.
     */
    private static @Nullable String resolveMainClass(
            @Nullable String cliMain, ImageConfig config, JkBuild project, Path projectDir) {
        if (cliMain != null && !cliMain.isBlank()) return cliMain;
        if (config.main() != null && !config.main().isBlank()) return config.main();
        if (project.mainClass() != null) {
            return project.mainClass();
        }
        // main-scan fallback: the compiled classes carry exactly one main (same scan the
        // packager uses for its entry attribute) — [application].main stays optional.
        if (PluginBuild.shape(project, projectDir).map(sh -> sh.mainScan()).orElse(false)) {
            try {
                return MainClassScanner.scanUnique(
                        BuildLayout.of(projectDir, project).classesDir());
            } catch (IOException ignored) {
                // fall through to the workspace scan / null
            }
        }
        // Workspace fallback: scan modules for an [application].main.
        if (!project.isWorkspaceRoot()) return null;
        try {
            var modules = WorkspaceLoader.loadModules(projectDir, project);
            List<String> mains = modules.values().stream()
                    .map(JkBuild::mainClass)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (mains.size() == 1) return mains.get(0);
            if (mains.size() > 1) {
                throw new RuntimeException("Multiple main classes discovered. Set image.main in jk.toml.");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Log.debug("resolveMainClass: Exception ignored", e);
        }
        return null;
    }

    /**
     * A module's runtime jars for the image, in layers from least to most volatile: locked release
     * versions, locked {@code -SNAPSHOT} versions, and workspace siblings' thin jars, which have no
     * lock row and change on every rebuild of that module. {@code names} is each locked jar's
     * coordinate file name (see {@link #jarNames}); a sibling keeps its own file name.
     */
    public record RuntimeJars(List<Path> releases, List<Path> snapshots, List<Path> siblings, Map<Path, String> names) {

        /** The file name {@code jar} ships under in {@code /app/libs}. */
        String name(Path jar) {
            String named = names.get(jar);
            return named != null ? named : jar.getFileName().toString();
        }
    }

    /**
     * The module's production runtime closure: its declared externals and its workspace siblings'
     * transitive closure through the lock, plus the siblings' own thin jars. Located the way every
     * other classpath is (the store, or {@code ~/.m2} when integration is on), so the layers hold
     * exactly what the fat jar would nest.
     */
    static RuntimeJars runtimeJars(Path moduleDir, JkBuild project, Path lockFile) throws IOException {
        return split(
                ModuleRuntimeClasspath.jars(moduleDir, project, lockFile, JkStores.storeCas()), lockRows(lockFile));
    }

    /** Pure half of {@link #runtimeJars}: layer by lock row, and name every locked jar. */
    static RuntimeJars split(List<Path> jars, Map<Path, Lockfile.Artifact> rows) throws IOException {
        List<Path> releases = new ArrayList<>();
        List<Path> snapshots = new ArrayList<>();
        List<Path> siblings = new ArrayList<>();
        for (Path jar : jars) {
            Lockfile.Artifact row = rows.get(jar);
            if (row == null) siblings.add(jar);
            else if (row.version().contains("SNAPSHOT")) snapshots.add(jar);
            else releases.add(jar);
        }
        return new RuntimeJars(releases, snapshots, siblings, jarNames(rows));
    }

    /** Every lock row by the path its jar resolves to, whatever scope it is in. */
    static Map<Path, Lockfile.Artifact> lockRows(Path lockFile) throws IOException {
        if (!Files.exists(lockFile)) return Map.of();
        Map<Path, Lockfile.Artifact> rows = new LinkedHashMap<>();
        ClasspathResolver resolver = new ClasspathResolver(JkStores.storeCas());
        for (ClasspathResolver.Entry entry :
                resolver.entriesFor(LockfileReader.read(lockFile), EnumSet.allOf(Scope.class))) {
            rows.put(entry.jar(), entry.artifact());
        }
        return rows;
    }

    /**
     * {@code <artifact>-<version>[-<classifier>].jar} for every row. Lock rows are keyed {@code
     * g:a:type:classifier}, so two classifier variants of one GA (netty's per-arch natives) or one
     * artifactId under two groups are distinct rows — they must land as distinct file names, or
     * the tar layer silently keeps only the last one. Colliding names are qualified with the
     * group; a residual collision fails the build. Package-visible for tests.
     */
    static Map<Path, String> jarNames(Map<Path, Lockfile.Artifact> rows) throws IOException {
        Map<Path, String> names = new LinkedHashMap<>();
        Map<String, Set<Path>> byName = new LinkedHashMap<>();
        for (var row : rows.entrySet()) {
            String base = coordinateJarName(row.getValue());
            names.put(row.getKey(), base);
            byName.computeIfAbsent(base, k -> new LinkedHashSet<>()).add(row.getKey());
        }
        for (var e : byName.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Set<String> qualified = new HashSet<>();
            for (Path jar : e.getValue()) {
                String withGroup = Objects.requireNonNull(rows.get(jar)).moduleGroup() + "-" + e.getKey();
                if (!qualified.add(withGroup)) {
                    throw new IOException("image dependency jar name collision: multiple lock rows map to " + withGroup
                            + " — cannot lay out /app/libs without losing one");
                }
                names.put(jar, withGroup);
            }
        }
        return names;
    }

    private static String coordinateJarName(Lockfile.Artifact pkg) {
        String classifier = "";
        if (PackageId.isMavenPackageKey(pkg.name())) {
            String c = PackageId.parse(pkg.name()).classifier();
            if (c != null) classifier = c;
        }
        return pkg.moduleArtifact() + "-" + pkg.version() + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
    }
}
