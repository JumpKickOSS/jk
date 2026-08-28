// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.engine.protocol.ExecPlan;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Linking;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jdk.JdkResolver;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.MainClassScanner;
import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.plugin.PluginModule;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.tool.AppLauncher;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Client exec plans: {@link #projectInfo} (parse/summarize) and {@link #execPlan} (decide, don't
 * execute). Client runs the returned plan verbatim.
 */
public final class ExecPlans {

    private ExecPlans() {}

    // ------------------------------------------------------------- project info

    /** Summarize the project at {@code dir} — never throws; failures ride {@code error}. */
    public static ProjectInfo projectInfo(Path dir) {
        return projectInfo(dir, null, null);
    }

    /**
     * As {@link #projectInfo(Path)} with optional {@code -m}/{@code --affected-since} filters.
     * When either selector is set, {@code moduleDirs}/{@code moduleNames} are the selection
     * (empty match is success, not an error). Invalid selectors ride {@code error}.
     */
    public static ProjectInfo projectInfo(Path dir, String modulesSpec, String affectedSince) {
        return projectInfo(dir, modulesSpec, affectedSince, true);
    }

    /**
     * {@code counts=false} skips the source/test tree walks — identity/selection callers on hot
     * paths (build/compile/release loops) never need them; only {@code jk status} does (JK-2162).
     */
    public static ProjectInfo projectInfo(Path dir, String modulesSpec, String affectedSince, boolean counts) {
        try {
            Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
            if (!Files.exists(buildFile)) {
                return ProjectInfo.error("no jk.toml in " + dir);
            }
            // parse() is already applyWorkspace(dir, parseLocal(file)) — calling it again here walked
            // for the root, re-parsed it and re-loaded every member a second time, per request
            // (JK-1042).
            JkBuild build = JkBuildParser.parse(buildFile);
            build = applyLockModulePin(dir, build);

            String workspaceRootDir = "";
            List<JkBuild> envSources = new ArrayList<>(List.of(build));
            List<String> moduleDirs = new ArrayList<>();
            List<String> moduleNames = new ArrayList<>();
            Path wsRoot = null;
            JkBuild rootBuild = build;
            if (build.isWorkspaceRoot()) {
                wsRoot = dir;
                workspaceRootDir = dir.toString();
            } else {
                var root = WorkspaceLocator.findRoot(dir);
                if (root.isPresent()) {
                    wsRoot = root.get();
                    workspaceRootDir = wsRoot.toString();
                    try {
                        rootBuild = JkBuildParser.parse(wsRoot.resolve(ManifestPaths.MANIFEST));
                    } catch (Exception ignored) {
                        rootBuild = build;
                    }
                }
            }
            if (wsRoot != null && rootBuild.isWorkspaceRoot()) {
                try {
                    for (var e : WorkspaceLoader.loadModules(wsRoot, rootBuild).entrySet()) {
                        moduleDirs.add(e.getKey().toAbsolutePath().normalize().toString());
                        moduleNames.add(e.getValue().project().name());
                        envSources.add(e.getValue()); // a member declares what the root does not
                    }
                } catch (Exception ignored) {
                    for (String m : rootBuild.workspace().modules()) {
                        Path abs = wsRoot.resolve(m).toAbsolutePath().normalize();
                        moduleDirs.add(abs.toString());
                        moduleNames.add(abs.getFileName().toString());
                    }
                }
            } else {
                moduleDirs.add(dir.toAbsolutePath().normalize().toString());
                moduleNames.add(build.project().name());
            }

            if ((modulesSpec != null && !modulesSpec.isBlank())
                    || (affectedSince != null && !affectedSince.isBlank())) {
                Path selectRoot = wsRoot != null ? wsRoot : dir;
                JkBuild selectBuild = wsRoot != null ? rootBuild : build;
                var hit = ModuleSelection.resolveOptional(selectRoot, selectBuild, modulesSpec, affectedSince);
                if (hit != null && !hit.ok()) {
                    return ProjectInfo.error(hit.errorMessage());
                }
                List<String> filteredDirs = new ArrayList<>();
                List<String> filteredNames = new ArrayList<>();
                if (hit != null) {
                    for (Path p : hit.moduleDirs()) {
                        String abs = p.toAbsolutePath().normalize().toString();
                        int idx = moduleDirs.indexOf(abs);
                        filteredDirs.add(abs);
                        filteredNames.add(
                                idx >= 0
                                        ? moduleNames.get(idx)
                                        : p.getFileName().toString());
                    }
                }
                moduleDirs = filteredDirs;
                moduleNames = filteredNames;
            }

            int sourceCount = 0, testCount = 0;
            if (counts) {
                List<Path> countDirs = moduleDirs.isEmpty()
                        ? List.of(dir)
                        : moduleDirs.stream().map(Path::of).toList();
                for (Path mod : countDirs) {
                    sourceCount += countSources(mod, true);
                    testCount += countSources(mod, false);
                }
            }

            Path lockFile = LockPaths.lockFile(dir);
            boolean hasLock = Files.exists(lockFile);
            String lockJdk = "";
            if (hasLock) {
                try {
                    var pin = LockfileReader.read(lockFile).jdk();
                    if (pin != null) lockJdk = pin.fingerprint();
                } catch (IOException ignored) {
                    // unreadable lock — summarized as jdk-unknown, not an error
                }
            }

            var format = build.format();
            var boot = build.pluginConfig(JkBuild.SPRING_BOOT_ID).orElse(null);
            var testTags = JkBuildParser.parseTestTags(buildFile);
            return new ProjectInfo(
                    null,
                    sanitizeIdentity(build.project().group()),
                    build.project().name(),
                    sanitizeIdentity(build.project().version()),
                    sanitizeJdk(build.project().jdk()),
                    build.project().javaRelease(),
                    build.project().isKotlin(),
                    build.project().kotlin() == null
                            ? ""
                            : build.project().kotlin().raw(),
                    build.project().isGroovy(),
                    build.project().groovy() == null
                            ? ""
                            : build.project().groovy().raw(),
                    SourceLayout.isSimpleLayout(build.project(), dir),
                    build.isWorkspaceRoot(),
                    workspaceRootDir,
                    moduleDirs,
                    build.isApplication(),
                    build.mainClass() == null ? "" : build.mainClass(),
                    build.assembly(),
                    build.application()
                            .map(JkBuild.Application::config)
                            .filter(c -> c != null && !c.isBlank())
                            .orElse(""),
                    build.nativeMode().name(),
                    orEmpty(build.graal()),
                    build.isSpringBoot(),
                    boot == null ? "" : boot.stringOpt("version").orElse(""),
                    orEmpty(format.style()),
                    orEmpty(format.java()),
                    orEmpty(format.kotlin()),
                    format.optimizeImports(),
                    format.importOrder(),
                    format.removeUnusedImports(),
                    hasLock,
                    lockJdk,
                    layoutOf(build, dir, BuildLayout::mainJar),
                    layoutOf(build, dir, BuildLayout::assemblyJar),
                    layoutOf(build, dir, BuildLayout::nativeBinary),
                    layoutOf(build, dir, BuildLayout::nativeLibrary),
                    pathDeps(build),
                    layoutOf(build, dir, BuildLayout::sourcesJar),
                    layoutOf(build, dir, BuildLayout::javadocJar),
                    VariantApply.envRefs(envSources),
                    moduleNames,
                    sourceCount,
                    testCount,
                    build.nativeExplicitlyDisabled(),
                    layoutOf(build, dir, BuildLayout::classesDir),
                    layoutOf(build, dir, BuildLayout::testClassesDir),
                    layoutOf(build, dir, BuildLayout::kotlinClassesDir),
                    layoutOf(build, dir, BuildLayout::groovyClassesDir),
                    layoutOf(build, dir, BuildLayout::testResultsDir),
                    testTags.includeTags(),
                    testTags.excludeTags(),
                    hasLock && LockFreshness.isStale(dir, lockFile),
                    build.project().isScala(),
                    build.project().scala() == null
                            ? ""
                            : build.project().scala().raw(),
                    CompileSupport.coordinatorOnly(build, dir),
                    build.install().map(JkBuild.Install::productLib).orElse(""));
        } catch (RuntimeException | IOException e) {
            return ProjectInfo.error(Errors.text(e));
        }
    }

    private static String sanitizeIdentity(String value) {
        if (value == null || value.isBlank() || Project.VERSION_FROM_WORKSPACE.equals(value)) return "";
        return value;
    }

    private static String sanitizeJdk(String jdk) {
        if (jdk == null || jdk.isBlank() || Project.VERSION_FROM_WORKSPACE.equals(jdk)) return "";
        return jdk;
    }

    /** Apply a matching {@code [[module]]} lock pin for display identity. */
    private static JkBuild applyLockModulePin(Path cwd, JkBuild build) {
        try {
            Path lockFile = LockPaths.lockFile(cwd);
            if (!Files.isRegularFile(lockFile)) return build;
            Lockfile lock = LockfileReader.read(lockFile);
            if (lock.modules().isEmpty()) return build;
            Path owner = LockPaths.lockOwnerDir(cwd).toAbsolutePath().normalize();
            String rel = owner.relativize(cwd.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            if (rel.isEmpty()) rel = ".";
            final String pathKey = rel;
            Lockfile.ModuleEntry pin = lock.modules().stream()
                    .filter(m ->
                            pathKey.equals(m.path()) || build.project().name().equals(m.name()))
                    .findFirst()
                    .orElse(null);
            if (pin == null) return build;
            var p = build.project();
            String group = blankOrSentinel(p.group()) ? pin.group() : p.group();
            String version = blankOrSentinel(p.version()) ? pin.version() : p.version();
            int javaRelease = p.java() > 0 ? p.java() : (pin.java() != null ? pin.java() : 0);
            if (group.equals(p.group())
                    && version.equals(p.version())
                    && javaRelease == p.java()
                    && !p.inheritsFromWorkspace()) {
                return build;
            }
            var resolved = new Project(
                    group,
                    p.name(),
                    version,
                    p.jdk(),
                    javaRelease,
                    p.kotlin(),
                    p.groovy(),
                    p.scala(),
                    p.sourcesMode(),
                    p.description(),
                    p.m2integration(),
                    p.m2install(),
                    p.layout(),
                    Set.of());
            return build.withProject(resolved);
        } catch (Exception e) {
            return build;
        }
    }

    private static boolean blankOrSentinel(String value) {
        return value == null || value.isBlank() || Project.VERSION_FROM_WORKSPACE.equals(value);
    }

    static int countSources(Path module, boolean main) {
        AtomicInteger n = new AtomicInteger();
        List<Path> roots = new ArrayList<>();
        if (main) {
            roots.add(module.resolve("src/main/java"));
            roots.add(module.resolve("src/main/kotlin"));
            roots.add(module.resolve("src/main/groovy"));
        } else {
            roots.add(module.resolve("src/test/java"));
            roots.add(module.resolve("src/test/kotlin"));
            roots.add(module.resolve("src/test/groovy"));
            roots.add(module.resolve("test/src"));
        }
        for (Path root : roots.stream().distinct().toList()) {
            if (!Files.isDirectory(root)) continue;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy")) {
                            n.incrementAndGet();
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
                // best-effort counts
            }
        }
        return n.get();
    }

    /** Libraries declared as path dependencies — publish/export refuse them (consume-only). */
    private static List<String> pathDeps(JkBuild build) {
        List<String> out = new ArrayList<>();
        for (var byScope : build.dependencies().byScope().entrySet()) {
            for (var dep : byScope.getValue()) {
                if (dep.isPath()) out.add(dep.library());
            }
        }
        return out;
    }

    private static String layoutOf(JkBuild build, Path dir, Function<BuildLayout, Path> f) {
        try {
            return f.apply(BuildLayout.of(dir, build)).toAbsolutePath().toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------- exec plans

    /** Compute the plan for {@code kind} — never throws; failures ride {@code error}. */
    public static ExecPlan execPlan(Path dir, Path cache, String kind, String mainOverride, String binName) {
        return execPlan(dir, cache, kind, mainOverride, binName, null, null, "", Map.of());
    }

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    public static ExecPlan execPlan(
            Path dir, Path cache, String kind, String mainOverride, String binName, Path binDir, Path libDir) {
        return execPlan(dir, cache, kind, mainOverride, binName, binDir, libDir, "", Map.of());
    }

    /**
     * As above with the request's variant selection: the plan must describe the SELECTED product
     * {@code jk run --release} on an Android app resolves the AAB packaging (and its deploy command),
     * not the debug APK's.
     */
    public static ExecPlan execPlan(
            Path dir,
            Path cache,
            String kind,
            String mainOverride,
            String binName,
            Path binDir,
            Path libDir,
            String variant,
            Map<String, String> clientEnv) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            project = VariantApply.applyLenient(project, dir, Variants.Selection.parse(variant), clientEnv)
                    .build();
            BuildLayout layout = BuildLayout.of(dir, project);
            return switch (kind) {
                case "run" -> runPlan(dir, cache, project, layout, false);
                case "dev" -> runPlan(dir, cache, project, layout, true);
                case "install" -> installPlan(dir, cache, project, layout, mainOverride, binName, binDir, libDir);
                case "aot-cache" -> aotCachePlan(dir, cache, project, layout);
                case "jshell" -> jshellPlan(dir, cache, project, layout);
                default -> ExecPlan.error(kind, "unknown exec-plan kind: " + kind);
            };
        } catch (RuntimeException | IOException e) {
            return ExecPlan.error(kind, Errors.text(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecPlan.error(kind, "interrupted");
        }
    }

    /** Compile-main classpath for {@code jk jshell}: classes dir first, then lock artifacts. */
    private static ExecPlan jshellPlan(Path dir, Path cache, JkBuild project, BuildLayout layout) throws IOException {
        if (project.isWorkspaceRoot()) {
            return ExecPlan.error(
                    "jshell", "run from a module directory (workspace roots have no single compile classpath)");
        }
        Path classes = layout.classesDir();
        if (!Files.isDirectory(classes)) {
            return ExecPlan.error(
                    "jshell", "no classes at " + classes + " — run `jk build --skip-tests` or drop `--no-build`");
        }
        Path lockFile = LockPaths.lockFile(dir);
        if (!Files.isRegularFile(lockFile)) {
            return ExecPlan.error("jshell", "no jk-lock.toml — lock refresh did not produce one");
        }
        Lockfile lock = LockfileReader.read(lockFile);
        Cas cas = JkStores.cas(cache.resolve("cas"));
        List<Path> depCp = new ClasspathResolver(cas).classpathFor(lock, ClasspathResolver.COMPILE_MAIN);
        List<String> paths = new ArrayList<>();
        paths.add(classes.toAbsolutePath().toString());
        int missing = 0;
        List<Path> present = new ArrayList<>();
        for (Path p : depCp) {
            if (p == null) continue;
            if (Files.exists(p)) present.add(p);
            else missing++;
        }
        for (Path p : jarAliased(cache, present)) paths.add(p.toString());
        String display = missing > 0 ? missing + " lock classpath entry(ies) missing on disk — run `jk sync`" : "";
        return new ExecPlan(
                null,
                "",
                "jshell",
                List.of(),
                dir.toString(),
                display,
                "",
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "",
                false,
                classes.toAbsolutePath().toString(),
                "",
                "",
                List.of(),
                paths,
                "");
    }

    /**
     * jshell only loads {@code *.jar}/{@code *.zip}, but CAS classpath entries are extensionless
     * content hashes — alias them under a stable {@code <cache>/jshell-cp/} dir instead of a
     * fresh temp dir per request: this runs in the resident engine, where per-request
     * {@code deleteOnExit} temp dirs accumulate until engine exit (JK-2159). Aliases are hard
     * links keyed by source path, so repeat requests are idempotent and cost nothing.
     */
    static List<Path> jarAliased(Path cache, List<Path> jars) throws IOException {
        List<Path> out = new ArrayList<>(jars.size());
        Path aliasDir = CacheTree.JSHELL_CP.under(cache);
        for (Path jar : jars) {
            if (jar == null) continue;
            String name = jar.getFileName().toString().toLowerCase();
            if (Files.isDirectory(jar) || name.endsWith(".jar") || name.endsWith(".zip")) {
                out.add(jar);
                continue;
            }
            // Path-keyed prefix so equal-named blobs from different roots cannot collide; CAS
            // blob content is immutable, so an existing alias (a hard link) is always current.
            String key = Integer.toHexString(jar.toAbsolutePath().toString().hashCode());
            Path alias = aliasDir.resolve(key + "-" + jar.getFileName() + ".jar");
            if (!Files.exists(alias)) Linking.linkOrCopy(jar, alias);
            out.add(alias);
        }
        return out;
    }

    /**
     * {@code jk run} / {@code jk dev}: pick the most self-contained artifact (native > shadow >
     * classes-dir) and assemble the full command line. Dev mode always runs from the classes dir
     * (the loop recompiles into it, and DevTools watches it) with the RUN classpath.
     */
    private static ExecPlan runPlan(Path dir, Path cache, JkBuild project, BuildLayout layout, boolean dev)
            throws IOException, InterruptedException {
        // Workspace root: pick the runnable module (declared [application] main, else unique scan).
        // Without this, jk run at the workspace coordinator fails even when e.g. app/ has main.
        // A root that is ITSELF runnable ([workspace] + [application] main + sources — "rare but
        // legal" per WorkspaceLoader) runs its own main: it can never appear in loadModules, so
        // the module scan would report "no launchable main".
        if (project.isWorkspaceRoot()) {
            String rootMain = project.mainClass();
            if (rootMain == null || rootMain.isBlank() || !CompileSupport.hasSources(dir)) {
                return runWorkspace(dir, cache, project, dev);
            }
        }
        // A device-mode artifact (an APK) is not host-runnable — the plugin's deploy command is
        // the run story; a generic java exec would be nonsense.
        var hostShape = PluginBuild.shape(project, dir);
        if (hostShape.map(sh -> "none".equals(sh.execMode())).orElse(false)) {
            return ExecPlan.error(
                    dev ? "dev" : "run",
                    "this project packages a non-executable artifact (exec-mode=none, e.g. a library)"
                            + " — there is nothing to run");
        }
        var deviceShape = hostShape.filter(sh -> "device".equals(sh.execMode()));
        if (deviceShape.isPresent()) {
            String deployCommand = deviceShape.get().deployCommand();
            if (deployCommand.isEmpty()) {
                return ExecPlan.error(
                        dev ? "dev" : "run",
                        "this project packages a device artifact (exec-mode=device) — it cannot run on the"
                                + " host JVM; deploy it with the owning plugin's command instead");
            }
            // Device artifact: client runs the plugin deploy command (dev re-dispatches after rebuild).
            List<String> deviceWatch = new ArrayList<>();
            if (dev) {
                if (Files.isDirectory(dir.resolve("src")))
                    deviceWatch.add(dir.resolve("src").toString());
                if (Files.isDirectory(dir.resolve("res")))
                    deviceWatch.add(dir.resolve("res").toString());
                Path deviceManifest = dir.resolve("AndroidManifest.xml");
                if (Files.isRegularFile(deviceManifest)) deviceWatch.add(deviceManifest.toString());
            }
            return new ExecPlan(
                    null,
                    "",
                    dev ? "dev" : "run",
                    List.of(),
                    dir.toString(),
                    "deploy → device (" + deployCommand + ")",
                    "",
                    false,
                    false,
                    deviceWatch,
                    List.of(),
                    List.of(),
                    "",
                    "",
                    "",
                    false,
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    deployCommand);
        }
        Path javaHome = projectJavaHome(dir);
        String java = javaBin(javaHome);

        if (!dev) {
            Path nativeBin = layout.nativeBinary();
            if (Files.isRegularFile(nativeBin) && PathUtil.isRunnable(nativeBin)) {
                return runAck(
                        "run",
                        List.of(nativeBin.toAbsolutePath().toString()),
                        dir,
                        javaHome,
                        nativeBin.getFileName().toString(),
                        false,
                        false,
                        List.of());
            }
            Path assemblyJar = layout.assemblyJar();
            if (Files.isRegularFile(assemblyJar)) {
                return runAck(
                        "run",
                        List.of(java, "-jar", assemblyJar.toAbsolutePath().toString()),
                        dir,
                        javaHome,
                        "java -jar " + dir.relativize(assemblyJar),
                        false,
                        false,
                        List.of());
            }
            // Self-contained packager output (Quarkus fast-jar / Boot fat-jar): run via -jar.
            // Do not fall through to -cp + scanned main — the thin Class-Path layout or nested
            // BOOT-INF is not a normal compile classpath, and Application main is not enough.
            if (hostShape
                    .map(sh -> sh.selfContained() && "jar".equals(sh.execMode()))
                    .orElse(false)) {
                Path mainJar = layout.mainJar();
                if (Files.isRegularFile(mainJar)) {
                    return runAck(
                            "run",
                            List.of(java, "-jar", mainJar.toAbsolutePath().toString()),
                            dir,
                            javaHome,
                            "java -jar " + dir.relativize(mainJar),
                            false,
                            false,
                            List.of());
                }
                return ExecPlan.error(
                        "run",
                        "self-contained jar not found at " + layout.mainJar() + " — run `jk build` first",
                        "missing");
            }
        }

        // Classes-dir + RUN classpath: dev-scope deps ride; a classes-run packager's jar
        // (e.g. Boot's BOOT-INF nesting) never lands on a -cp.
        List<Path> classpath = new ArrayList<>();
        boolean classesEntry = dev
                || PluginBuild.shape(project, dir).map(sh -> sh.classesRun()).orElse(false);
        classpath.add(classesEntry ? layout.classesDir() : layout.mainJar());

        boolean devtoolsInjected = false;
        boolean hotReload = false;
        Path lockFile = LockPaths.lockFile(dir);
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            classpath.addAll(new ClasspathResolver(JkStores.cas(cache)).classpathFor(lock, ClasspathResolver.RUN));
            if (dev) {
                hotReload = lock.artifacts().stream().anyMatch(a -> {
                    String n = a.name();
                    return "org.springframework.boot:spring-boot-devtools".equals(n)
                            || "org.springframework.boot:spring-boot-devtools:jar:".equals(a.packageKey())
                            || (PackageId.isMavenPackageKey(n)
                                    && "org.springframework.boot:spring-boot-devtools"
                                            .equals(PackageId.parse(n).ga()));
                });
            }
        }
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(dir, project, ClasspathResolver.RUN);
        classpath.addAll(siblings.jars());

        if (dev && !hotReload && project.pluginConfig("spring-boot").isPresent()) {
            // Tier 2: fetch devtools version-matched to the declared Boot line; offline
            // degrades silently to process-restart mode. DELIBERATE residue: hot-reload
            // shaping folds into the plugin SPI's run hooks when that step lands.
            Path devtools = fetchDevtools(project, cache);
            if (devtools != null) {
                classpath.add(devtools);
                hotReload = true;
                devtoolsInjected = true;
            }
        }

        String mainClass;
        if (project.mainClass() != null) {
            mainClass = project.mainClass();
        } else {
            try {
                mainClass = MainClassScanner.scanUnique(layout.classesDir());
            } catch (MainClassScanner.NoMainFoundException e) {
                return ExecPlan.error(dev ? "dev" : "run", e.getMessage(), "missing");
            } catch (MainClassScanner.AmbiguousMainException e) {
                return ExecPlan.error(dev ? "dev" : "run", e.getMessage(), "ambiguous");
            }
        }

        // Honor the jar's manifest when we can. A plain jar carries `Main-Class` only for a declared
        // `[application] main` (a *discovered* main is never written to the manifest), and `java -jar`
        // ignores `-cp` — so `-jar` is correct exactly when the main is declared AND the jar is the
        // whole classpath (no runtime deps/siblings/devtools). Otherwise deps must ride a `-cp` and we
        // name the class explicitly. Dev / classes-run modes launch from the classes dir, never `-jar`.
        boolean runnableJar = !classesEntry && classpath.size() == 1 && project.mainClass() != null;
        List<String> argv = new ArrayList<>();
        argv.add(java);
        String display;
        if (runnableJar) {
            Path jar = classpath.get(0);
            argv.add("-jar");
            argv.add(jar.toAbsolutePath().toString());
            display = "java -jar " + dir.relativize(jar);
        } else {
            boolean hasDeps = classpath.size() > 1;
            Path first = classpath.get(0);
            argv.add("-cp");
            argv.add(Classpaths.join(classpath));
            argv.add(mainClass);
            display = (hasDeps ? "java -cp … " : "java -cp " + dir.relativize(first) + " ") + mainClass;
        }

        List<String> watchRoots = new ArrayList<>();
        if (dev && Files.isDirectory(dir.resolve("src")))
            watchRoots.add(dir.resolve("src").toString());
        return runAck(dev ? "dev" : "run", argv, dir, javaHome, display, hotReload, devtoolsInjected, watchRoots);
    }

    /**
     * {@code jk run} at a workspace root: select the module to launch, then reuse the single-module
     * run plan. Preference: exactly one module with {@code [application] main}; else exactly one
     * module with a unique scanned {@code main} in its classes/jar; else a clear missing/ambiguous
     * error naming the candidates.
     */
    private static ExecPlan runWorkspace(Path root, Path cache, JkBuild rootBuild, boolean dev)
            throws IOException, InterruptedException {
        String kind = dev ? "dev" : "run";
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, rootBuild);
        if (modules.isEmpty()) {
            return ExecPlan.error(kind, "workspace has no modules — nothing to run", "missing");
        }

        // 1) Declared [application] main wins — but only when it is unambiguous. Several declared
        // apps must be an error naming the candidates: silently launching whichever is listed
        // first means reordering [workspace].modules changes what `jk run` executes.
        List<Path> declaredApps = new ArrayList<>();
        for (var e : modules.entrySet()) {
            String m = e.getValue().mainClass();
            if (m != null && !m.isBlank()) declaredApps.add(e.getKey());
        }
        if (declaredApps.size() == 1) {
            Path modDir = declaredApps.get(0);
            JkBuild mod = modules.get(modDir);
            return runPlan(modDir, cache, mod, BuildLayout.of(modDir, mod), dev);
        }
        if (declaredApps.size() > 1) {
            String names = declaredApps.stream()
                    .map(d -> root.relativize(d).toString())
                    .collect(Collectors.joining(", "));
            return ExecPlan.error(
                    kind,
                    "multiple modules declare [application] main (" + names + ") — run one explicitly: jk " + kind
                            + " <module>",
                    "ambiguous");
        }

        // 2) Best-effort scan: classes dir first, then main jar.
        Map<String, Path> mainToModule = new LinkedHashMap<>();
        for (var e : modules.entrySet()) {
            Path modDir = e.getKey();
            BuildLayout layout = BuildLayout.of(modDir, e.getValue());
            List<String> found = new ArrayList<>();
            if (Files.isDirectory(layout.classesDir())) {
                found.addAll(MainClassScanner.scan(layout.classesDir()));
            }
            if (found.isEmpty() && Files.isRegularFile(layout.mainJar())) {
                found.addAll(MainClassScanner.scan(layout.mainJar()));
            }
            for (String main : found) {
                Path prev = mainToModule.putIfAbsent(main, modDir);
                if (prev != null && !prev.equals(modDir)) {
                    // Same FQCN in two modules — treat as ambiguous
                    return ExecPlan.error(
                            kind,
                            "multiple modules define main class " + main + " ("
                                    + root.relativize(prev)
                                    + " and "
                                    + root.relativize(modDir)
                                    + ")",
                            "ambiguous");
                }
            }
        }
        if (mainToModule.isEmpty()) {
            return ExecPlan.error(
                    kind,
                    "no launchable main found in workspace modules — set `[application] main = \"...\"` "
                            + "on the app module (or run from that module directory)",
                    "missing");
        }
        // Same rule as declared apps: scanned mains across SEVERAL modules are ambiguous.
        Set<Path> scannedModules = new LinkedHashSet<>(mainToModule.values());
        if (scannedModules.size() > 1) {
            String names = scannedModules.stream()
                    .map(d -> root.relativize(d).toString())
                    .collect(Collectors.joining(", "));
            return ExecPlan.error(
                    kind,
                    "multiple modules contain a runnable main (" + names + ") — run one explicitly: jk " + kind
                            + " <module>",
                    "ambiguous");
        }
        Path modDir = scannedModules.iterator().next();
        JkBuild mod = modules.get(modDir);
        return runPlan(modDir, cache, mod, BuildLayout.of(modDir, mod), dev);
    }

    private static ExecPlan runAck(
            String kind,
            List<String> argv,
            Path dir,
            Path javaHome,
            String display,
            boolean hotReload,
            boolean devtoolsInjected,
            List<String> watchRoots) {
        return new ExecPlan(
                null,
                "",
                kind,
                argv,
                dir.toString(),
                display,
                javaHome.toString(),
                hotReload,
                devtoolsInjected,
                watchRoots,
                List.of(),
                List.of(),
                "",
                "",
                "",
                false,
                "",
                "",
                "",
                List.of(),
                List.of(),
                "");
    }

    /**
     * {@code jk install}'s application half. Preference by what exists after the build: native
     * binary → {@code ~/.local/bin}; else minified/fat → {@code <data>/lib/&lt;bin&gt;/} + {@code
     * java -jar}; else thin jar stays in the local repo and the script uses {@code java -cp}.
     */
    private static ExecPlan installPlan(
            Path dir,
            Path cache,
            JkBuild project,
            BuildLayout layout,
            String mainOverride,
            String binName,
            Path binDirOverride,
            Path libDirOverride)
            throws IOException {
        if (isPluginWorker(dir, project)) {
            return installAck(List.of(), List.of(), "", "", "");
        }
        if (!project.isApplication()) {
            return ExecPlan.error(
                    "install", "not an application — declare [application] in jk.toml to make it installable");
        }

        var p = project.project();
        Path javaHome = projectJavaHome(dir);
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path libRoot = libDirOverride != null ? libDirOverride : JkDirs.productLib();
        String nativeName =
                project.nativeConfig().map(JkBuild.NativeConfig::name).orElse(null);

        Path nativeBin = layout.nativeBinary();
        if (Files.isRegularFile(nativeBin)) {
            String bin = firstNonBlank(binName, nativeName, p.name());
            Path dest = binDir.resolve(BuildLayout.nativeExecutableFileName(bin));
            return installAck(
                    List.of(nativeBin.toAbsolutePath().toString()), List.of(dest.toString()), "", "", dest.toString());
        }

        String bin = firstNonBlank(binName, p.name());
        Path libDir = libRoot.resolve(bin);
        Path launcherPath = binDir.resolve(AppLauncher.launcherFileName(bin));

        Path minified = layout.minifiedJar();
        if (Files.isRegularFile(minified)) {
            return fatJarPlan(minified, libDir, launcherPath, javaHome);
        }
        Path assembly = layout.assemblyJar();
        if (Files.isRegularFile(assembly)) {
            return fatJarPlan(assembly, libDir, launcherPath, javaHome);
        }
        var shape = PluginBuild.shape(project, dir);
        boolean selfContainedJar = shape.map(sh -> sh.selfContained() && "jar".equals(sh.execMode()))
                .orElse(false);
        if (selfContainedJar && Files.isRegularFile(layout.mainJar())) {
            return fatJarPlan(layout.mainJar(), libDir, launcherPath, javaHome);
        }

        Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
        Path repoJar = JkStores.storeRootFor(cache)
                .resolve("repos")
                .resolve(RepoArtifactResolver.JK_LOCAL)
                .resolve(MavenLayout.artifactPath(coord));
        if (!Files.isRegularFile(repoJar)) {
            repoJar = layout.mainJar();
        }
        List<Path> classpath = new ArrayList<>();
        classpath.add(repoJar);
        Path lockFile = resolveLockFile(dir);
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            for (ClasspathResolver.Entry entry :
                    new ClasspathResolver(JkStores.cas(cache)).entriesFor(lock, ClasspathResolver.RUNTIME)) {
                if (Files.exists(entry.jar())) classpath.add(entry.jar());
            }
        }
        WorkspaceClasspath.Result siblings =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME));
        for (Path sib : siblings.jars()) {
            classpath.add(sib);
        }
        String script = AppLauncher.renderScript(javaHome, resolveMain(project, layout, mainOverride), classpath);
        return installAck(List.of(), List.of(), launcherPath.toString(), script, launcherPath.toString());
    }

    private static ExecPlan fatJarPlan(Path src, Path libDir, Path launcherPath, Path javaHome) {
        Path dest = libDir.resolve(src.getFileName().toString());
        String script = AppLauncher.renderJarScript(javaHome, dest);
        return installAck(
                List.of(src.toAbsolutePath().toString()),
                List.of(dest.toString()),
                launcherPath.toString(),
                script,
                launcherPath.toString());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "app";
    }

    static boolean isPluginWorker(Path dir, JkBuild project) {
        if (PluginModule.isWorker(dir)) return true;
        String main = project.mainClass();
        return main != null && "cc.jumpkick.plugin.process.PluginMain".equals(main);
    }

    private static ExecPlan installAck(
            List<String> linkSrcs, List<String> linkDests, String launcherPath, String script, String binPath) {
        return new ExecPlan(
                null,
                "",
                "install",
                List.of(),
                "",
                "",
                "",
                false,
                false,
                List.of(),
                linkSrcs,
                linkDests,
                launcherPath,
                script,
                binPath,
                false,
                "",
                "",
                "",
                List.of(),
                List.of(),
                "");
    }

    /** {@code jk build --aot-cache}: everything the client's layout/training step needs. */
    private static ExecPlan aotCachePlan(Path dir, Path cache, JkBuild project, BuildLayout layout) throws IOException {
        Path mainJar = layout.mainJar();
        if (!Files.isRegularFile(mainJar)) {
            return ExecPlan.error("aot-cache", "jar not found at " + mainJar + " — build before --aot-cache");
        }
        int major = Project.majorOf(project.project().jdk());
        String tier = major >= 25 ? "aot" : "cds";

        List<String> libNames = new ArrayList<>();
        List<String> libPaths = new ArrayList<>();
        Path lockFile = LockPaths.lockFile(dir);
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            for (ClasspathResolver.Entry entry :
                    new ClasspathResolver(JkStores.cas(cache)).entriesFor(lock, ClasspathResolver.RUNTIME)) {
                if (!Files.exists(entry.jar())) continue;
                libNames.add(entry.artifact().moduleArtifact() + "-"
                        + entry.artifact().version() + ".jar");
                libPaths.add(entry.jar().toAbsolutePath().toString());
            }
        }
        // A self-contained executable jar (Boot-style) trains via -jar; anything else names
        // its main. The ExecPlan field keeps its wire name (`boot`) — it means exactly this.
        boolean executableJar = PluginBuild.shape(project, dir)
                .map(sh -> sh.selfContained() && "jar".equals(sh.execMode()))
                .orElse(false);
        String mainClass = "";
        if (!executableJar) {
            mainClass = project.mainClass() != null ? project.mainClass() : MainClassScanner.scanUnique(mainJar);
        }
        Path javaHome = projectJavaHome(dir);
        return new ExecPlan(
                null,
                "",
                "aot-cache",
                List.of(),
                dir.toString(),
                "",
                javaHome.toString(),
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "",
                executableJar,
                mainJar.toAbsolutePath().toString(),
                tier,
                mainClass,
                libNames,
                libPaths,
                "");
    }

    // ------------------------------------------------------------- helpers

    private static String resolveMain(JkBuild project, BuildLayout layout, String mainOverride) throws IOException {
        if (mainOverride != null && !mainOverride.isBlank()) return mainOverride;
        if (project.mainClass() != null) return project.mainClass();
        return MainClassScanner.scanUnique(layout.classesDir());
    }

    /** The project-pinned JDK when resolvable; the engine's own JVM home otherwise. */
    private static Path projectJavaHome(Path dir) {
        try {
            return JdkResolver.forProject(dir, JkDirs.jdks())
                    .map(InstalledJdk::home)
                    .orElseGet(JavaHomes::runningJavaHome);
        } catch (IOException e) {
            return JavaHomes.runningJavaHome();
        }
    }

    private static Path fetchDevtools(JkBuild project, Path cache) {
        try {
            String bootVersion = project.pluginConfig("spring-boot")
                    .flatMap(c -> c.stringOpt("version"))
                    .orElse(null);
            if (bootVersion == null) return null;
            Cas cas = JkStores.cas(cache);
            return RepoGroupBuilder.buildFor(project, null, cas)
                    .tryFetchArtifact(Coordinate.of("org.springframework.boot", "spring-boot-devtools", bootVersion))
                    .map(hit -> hit.fetched().cachePath())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Path resolveLockFile(Path projectDir) throws IOException {
        Path lockFile = LockPaths.lockFile(projectDir);
        if (!Files.exists(lockFile)) {
            var rootOpt = WorkspaceLocator.findRoot(projectDir);
            if (rootOpt.isPresent()) {
                Path candidate = LockPaths.lockFile(rootOpt.get());
                if (Files.exists(candidate)) return candidate;
            }
        }
        return lockFile;
    }

    private static String javaBin(Path javaHome) {
        return JdkFingerprint.java(javaHome).toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
