// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
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
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.runtime.PluginBuild;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.tool.AppLauncher;
import cc.jumpkick.tool.LauncherName;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Client exec plans: {@link #execPlan} decides the command line; the client runs it verbatim.
 * Project summary lives in {@link ProjectInfoPlans}.
 */
public final class ExecPlans {

    private ExecPlans() {}

    // ------------------------------------------------------------- exec plans

    /** Compute the plan for {@code kind} — never throws; failures ride {@code error}. */
    public static ExecPlan execPlan(
            Path dir, Path cache, String kind, @Nullable String mainOverride, @Nullable String binName) {
        return execPlan(dir, cache, kind, mainOverride, binName, null, null, "", Map.of(), null);
    }

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    public static ExecPlan execPlan(
            Path dir,
            Path cache,
            String kind,
            @Nullable String mainOverride,
            @Nullable String binName,
            @Nullable Path binDir,
            @Nullable Path libDir) {
        return execPlan(dir, cache, kind, mainOverride, binName, binDir, libDir, "", Map.of(), null);
    }

    /**
     * As above with the request's variant selection: the plan must describe the SELECTED product
     * {@code jk run --release} on an Android app resolves the AAB packaging (and its deploy command),
     * not the debug APK's. A non-null {@code debug} makes a {@code run} plan's JVM listen for a
     * debugger; no other kind reads it.
     */
    public static ExecPlan execPlan(
            Path dir,
            Path cache,
            @Nullable String kind,
            @Nullable String mainOverride,
            @Nullable String binName,
            @Nullable Path binDir,
            @Nullable Path libDir,
            String variant,
            Map<String, String> clientEnv,
            @Nullable DebugJvm debug) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            project = VariantApply.applyLenient(project, dir, Variants.Selection.parse(variant), clientEnv)
                    .build();
            BuildLayout layout = BuildLayout.of(dir, project);
            return switch (kind == null ? "" : kind) {
                case "run" -> runPlan(dir, cache, project, layout, false, clientEnv, debug);
                case "dev" -> runPlan(dir, cache, project, layout, true, clientEnv, null);
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
        Cas cas = JkStores.storeCas();
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
                "",
                List.of());
    }

    /**
     * jshell only loads {@code *.jar}/{@code *.zip}, but CAS classpath entries are extensionless
     * content hashes — alias them under a stable {@code <cache>/jshell-cp/} dir instead of a
     * fresh temp dir per request: this runs in the resident engine, where per-request
     * {@code deleteOnExit} temp dirs accumulate until engine exit. Aliases are hard
     * links keyed by source path, so repeat requests are idempotent and cost nothing.
     */
    static List<Path> jarAliased(Path cache, List<Path> jars) throws IOException {
        List<Path> out = new ArrayList<>(jars.size());
        Path aliasDir = CacheTree.JSHELL_CP.under(cache);
        for (Path jar : jars) {
            if (jar == null) continue;
            String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
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
     * (the loop recompiles into it, and DevTools watches it) with the RUN classpath. Under
     * {@code debug} the native binary is passed over — JDWP needs a JVM — and the chosen JVM
     * command line carries the agent flag right after the launcher.
     */
    private static ExecPlan runPlan(
            Path dir,
            Path cache,
            JkBuild project,
            BuildLayout layout,
            boolean dev,
            Map<String, String> clientEnv,
            @Nullable DebugJvm debug)
            throws IOException, InterruptedException {
        // Workspace root: pick the runnable module (declared [application] main, else unique scan).
        // Without this, jk run at the workspace coordinator fails even when e.g. app/ has main.
        // A root that is ITSELF runnable ([workspace] + [application] main + sources — "rare but
        // legal" per WorkspaceLoader) runs its own main: it can never appear in loadModules, so
        // the module scan would report "no launchable main".
        if (project.isWorkspaceRoot()) {
            String rootMain = project.mainClass();
            if (rootMain == null || rootMain.isBlank() || !CompileSupport.hasSources(dir)) {
                return runWorkspace(dir, cache, project, dev, clientEnv, debug);
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
            return devicePlan(dir, dev, deployCommand, dev ? DevSidecars.resolve(dir, project, clientEnv) : List.of());
        }
        Path javaHome = projectJavaHome(dir);
        String java = javaBin(javaHome);

        ExecPlan plan = dev ? null : packagedPlan(dir, layout, hostShape, javaHome, java, debug == null);
        if (plan == null) {
            plan = classpathPlan(
                    dir,
                    cache,
                    project,
                    layout,
                    dev,
                    javaHome,
                    java,
                    dev ? DevSidecars.resolve(dir, project, clientEnv) : List.of());
        }
        return debug == null ? plan : debugged(plan, debug);
    }

    /** {@code plan} with the JDWP agent as the first JVM option; an error plan rides unchanged. */
    static ExecPlan debugged(ExecPlan plan, DebugJvm debug) {
        if (plan.error() != null || plan.argv().size() < 2) return plan;
        List<String> argv = new ArrayList<>(plan.argv());
        argv.add(1, debug.agentArg());
        return plan.withArgv(argv, plan.display());
    }

    /** Device artifact: client runs the plugin deploy command (dev re-dispatches after rebuild). */
    private static ExecPlan devicePlan(Path dir, boolean dev, String deployCommand, List<ExecPlan.Sidecar> sidecars) {
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
                deployCommand,
                sidecars);
    }

    /**
     * {@code jk run} of a packaged artifact — native binary, assembly jar, or a self-contained
     * packager jar — or null when the classes-dir classpath run is the way to launch.
     */
    private static @Nullable ExecPlan packagedPlan(
            Path dir,
            BuildLayout layout,
            Optional<PluginDescriptor.Packaging> hostShape,
            Path javaHome,
            String java,
            boolean allowNative) {
        Path nativeBin = layout.nativeBinary();
        if (allowNative && Files.isRegularFile(nativeBin) && PathUtil.isRunnable(nativeBin)) {
            return runAck(
                    "run",
                    List.of(nativeBin.toAbsolutePath().toString()),
                    dir,
                    javaHome,
                    nativeBin.getFileName().toString(),
                    false,
                    false,
                    List.of(),
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
                    List.of(),
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
                        List.of(),
                        List.of());
            }
            return ExecPlan.error(
                    "run",
                    "self-contained jar not found at " + layout.mainJar() + " — run `jk build` first",
                    "missing");
        }
        return null;
    }

    /**
     * Classes-dir + RUN classpath: dev-scope deps ride; a classes-run packager's jar (e.g. Boot's
     * BOOT-INF nesting) never lands on a -cp.
     */
    private static ExecPlan classpathPlan(
            Path dir,
            Path cache,
            JkBuild project,
            BuildLayout layout,
            boolean dev,
            Path javaHome,
            String java,
            List<ExecPlan.Sidecar> sidecars)
            throws IOException, InterruptedException {
        List<Path> classpath = new ArrayList<>();
        boolean classesEntry = dev
                || PluginBuild.shape(project, dir).map(sh -> sh.classesRun()).orElse(false);
        classpath.add(classesEntry ? layout.classesDir() : layout.mainJar());

        boolean devtoolsInjected = false;
        boolean hotReload = false;
        Path lockFile = LockPaths.lockFile(dir);
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            classpath.addAll(new ClasspathResolver(JkStores.storeCas()).classpathFor(lock, ClasspathResolver.RUN));
            if (dev) hotReload = locksDevtools(lock);
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
                return ExecPlan.error(dev ? "dev" : "run", Errors.text(e), "missing");
            } catch (MainClassScanner.AmbiguousMainException e) {
                return ExecPlan.error(dev ? "dev" : "run", Errors.text(e), "ambiguous");
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
        return runAck(
                dev ? "dev" : "run", argv, dir, javaHome, display, hotReload, devtoolsInjected, watchRoots, sidecars);
    }

    /** Whether the lock already carries Spring Boot DevTools, in any of the spellings a lock uses. */
    private static boolean locksDevtools(Lockfile lock) {
        return lock.artifacts().stream().anyMatch(a -> {
            String n = a.name();
            return "org.springframework.boot:spring-boot-devtools".equals(n)
                    || "org.springframework.boot:spring-boot-devtools:jar:".equals(a.packageKey())
                    || (PackageId.isMavenPackageKey(n)
                            && "org.springframework.boot:spring-boot-devtools"
                                    .equals(PackageId.parse(n).ga()));
        });
    }

    /**
     * {@code jk run} at a workspace root: select the module to launch, then reuse the single-module
     * run plan. Preference: exactly one module with {@code [application] main}; else exactly one
     * module with a unique scanned {@code main} in its classes/jar; else a clear missing/ambiguous
     * error naming the candidates.
     */
    private static ExecPlan runWorkspace(
            Path root,
            Path cache,
            JkBuild rootBuild,
            boolean dev,
            Map<String, String> clientEnv,
            @Nullable DebugJvm debug)
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
            JkBuild mod = Objects.requireNonNull(modules.get(modDir));
            return runPlan(modDir, cache, mod, BuildLayout.of(modDir, mod), dev, clientEnv, debug);
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
        JkBuild mod = Objects.requireNonNull(modules.get(modDir));
        return runPlan(modDir, cache, mod, BuildLayout.of(modDir, mod), dev, clientEnv, debug);
    }

    private static ExecPlan runAck(
            String kind,
            List<String> argv,
            Path dir,
            Path javaHome,
            String display,
            boolean hotReload,
            boolean devtoolsInjected,
            List<String> watchRoots,
            List<ExecPlan.Sidecar> sidecars) {
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
                "",
                sidecars);
    }

    /**
     * {@code jk install}'s application half. Preference among the artifacts the manifest
     * <em>declares</em>, by what exists after the build: native binary (ALWAYS mode) → {@code
     * ~/.jk/bin}; else minified/fat → {@code <home>/lib/&lt;bin&gt;/} + {@code java -jar}; else
     * thin jar stays in the local repo and the script uses {@code java -cp}. Declared-only on
     * purpose: {@code target/} can hold leftovers from before a declaration was removed, and a
     * bare exists-check would install those stale bytes with every step looking honest.
     */
    private static ExecPlan installPlan(
            Path dir,
            Path cache,
            JkBuild project,
            BuildLayout layout,
            @Nullable String mainOverride,
            @Nullable String binName,
            @Nullable Path binDirOverride,
            @Nullable Path libDirOverride)
            throws IOException {
        if (isPluginWorker(dir, project)) {
            return installAck(List.of(), List.of(), "", "", "");
        }
        if (!project.isApplication()) {
            return ExecPlan.error(
                    "install", "not an application — declare [application] in jk.toml to make it installable");
        }

        var p = project.project();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        String productBin =
                project.installOpt().map(JkBuild.Install::productBin).orElse(null);
        if (productBin != null) {
            // jk's own client. The built native binary replaces the PATH client under <home>/bin —
            // the one name LauncherName refuses to every other install, because a tool launcher
            // there would truncate the product. No launcher script and no lib dir: the binary is
            // the whole install, and the client applies the link with the same parking as a
            // release update.
            Path nativeBin = layout.nativeBinary();
            if (!InstallPlans.installsNativeBinary(project, layout)) {
                return ExecPlan.error(
                        "install",
                        "[install] product-bin needs the native client binary at " + nativeBin
                                + " — the module must build native (`[native] enabled = \"always\"`)");
            }
            Path dest = binDir.resolve(BuildLayout.nativeExecutableFileName(productBin));
            return installAck(
                    List.of(nativeBin.toAbsolutePath().toString()), List.of(dest.toString()), "", "", dest.toString());
        }
        Path javaHome = projectJavaHome(dir);
        Path libRoot = libDirOverride != null ? libDirOverride : JkDirs.productLib();
        String nativeName =
                project.nativeConfigOpt().map(JkBuild.NativeConfig::name).orElse(null);

        if (InstallPlans.installsNativeBinary(project, layout)) {
            Path nativeBin = layout.nativeBinary();
            String bin = firstNonBlank(binName, nativeName, p.name());
            Path dest = LauncherName.resolveChild(binDir, BuildLayout.nativeExecutableFileName(bin));
            return installAck(
                    List.of(nativeBin.toAbsolutePath().toString()), List.of(dest.toString()), "", "", dest.toString());
        }

        String bin = firstNonBlank(binName, p.name());
        Path libDir = LauncherName.resolveChild(libRoot, bin);
        Path launcherPath = LauncherName.resolveChild(binDir, AppLauncher.launcherFileName(bin));

        Optional<Path> fatJar = InstallPlans.declaredFatJar(project, layout);
        if (fatJar.isPresent()) {
            return fatJarPlan(fatJar.get(), libDir, launcherPath, javaHome);
        }
        var shape = PluginBuild.shape(project, dir);
        boolean selfContainedJar = shape.map(sh -> sh.selfContained() && "jar".equals(sh.execMode()))
                .orElse(false);
        if (selfContainedJar && Files.isRegularFile(layout.mainJar())) {
            return fatJarPlan(layout.mainJar(), libDir, launcherPath, javaHome);
        }

        Coordinate coord = Coordinate.of(p.group(), p.name(), p.version());
        Path repoJar = JkStores.store()
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
                    new ClasspathResolver(JkStores.storeCas()).entriesFor(lock, ClasspathResolver.RUNTIME)) {
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

    private static String firstNonBlank(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return LauncherName.requireValid(v);
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
                "",
                List.of());
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
                    new ClasspathResolver(JkStores.storeCas()).entriesFor(lock, ClasspathResolver.RUNTIME)) {
                Path jar = entry.jar();
                if (jar == null || !Files.exists(jar)) continue;
                libNames.add(entry.artifact().moduleArtifact() + "-"
                        + entry.artifact().version() + ".jar");
                libPaths.add(jar.toAbsolutePath().toString());
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
                "",
                List.of());
    }

    // ------------------------------------------------------------- helpers

    private static String resolveMain(JkBuild project, BuildLayout layout, @Nullable String mainOverride)
            throws IOException {
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

    private static @Nullable Path fetchDevtools(JkBuild project, Path cache) {
        try {
            String bootVersion = project.pluginConfig("spring-boot")
                    .flatMap(c -> c.stringOpt("version"))
                    .orElse(null);
            if (bootVersion == null) return null;
            Cas cas = JkStores.storeCas();
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
}
