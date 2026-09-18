// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.ExplodedArchives;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PlatformBomVersions;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import cc.jumpkick.runtime.base.PluginLaunch;
import cc.jumpkick.runtime.base.SdkComponents;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.tool.TrustedPlugins;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Engine build-plugin code layer: describe-protocol discovery (file-cached), execution specs, and
 * per-step forks — never classloads plugin code. Static packaging shape is manifest-only
 * ({@link #shape}).
 */
public final class PluginBuild {

    private PluginBuild() {}

    /** The active packager's static artifact descriptor, or empty — manifest data, no fork. */
    public static Optional<PluginDescriptor.Packaging> shape(JkBuild project, Path moduleDir) {
        for (PluginDescriptor m : PluginTableRegistry.manifestsFor(moduleDir, project.plugins())) {
            if (m.packaging() == null) continue;
            Optional<PluginConfig> config = project.pluginConfig(m.id());
            if (config.isPresent()) {
                // Config-conditional variants ([[packaging.variant]]): an [android] library
                // packages an AAR while an app packages an APK — same plugin, one manifest.
                return Optional.of(m.packaging().resolve(config.get()));
            }
        }
        return Optional.empty();
    }

    // ---- declarations (describe, file-cached) -------------------------------------------------

    /**
     * The {@code [code]} table of a plugin that has one. Every caller here has already established
     * that: a code plugin is precisely a manifest with this table, and {@link ActivePlugins#of}
     * skips the ones without it.
     */
    static PluginDescriptor.Code code(ActivePlugin active) {
        return Objects.requireNonNull(active.manifest().code(), "plugin [code] table");
    }

    /** A step's scratch root — its declared output dirs resolve under this. */
    public static Path taskScratch(BuildLayout layout, @Nullable String stepName) {
        return layout.moduleTargetDir().resolve("plugin").resolve(stepName);
    }

    /** Every dir the declared steps contribute as classes/resources, in declaration order. */
    public static List<Path> contributedDirs(PluginDeclarations decls, BuildLayout layout) {
        List<Path> out = new ArrayList<>();
        for (TaskDecl step : decls.steps()) {
            Path scratch = taskScratch(layout, step.name());
            for (String rel : step.contributesClasses()) out.add(scratch.resolve(rel));
            for (String rel : step.contributesResources()) out.add(scratch.resolve(rel));
        }
        return out;
    }

    /**
     * Plugin {@code describe} declarations, file-cached under the module target so a fully-cached
     * rebuild forks nothing.
     */
    public static PluginDeclarations declarations(
            ActivePlugin active, JkBuild project, Path moduleDir, Path cache, Path layoutTarget)
            throws IOException, InterruptedException {
        String key = describeKey(active, project, locateWorkerJar(active, cache));
        Path cacheFile =
                layoutTarget.resolve("plugin").resolve(active.manifest().id() + "-describe-" + key + ".jsonl");
        List<String> lines;
        if (Files.isRegularFile(cacheFile)) {
            lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
        } else {
            Path spec = new SpecWriter()
                    .op(PluginProtocol.OP_DESCRIBE, null, active.manifest().id())
                    .configValues(active.config().values())
                    .project(facts(project, project.mainClass()))
                    .writeTempSpec();
            try {
                lines = runWorker(active, cache, spec, WorkerEnv.strict(), null);
            } finally {
                Files.deleteIfExists(spec);
            }
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, lines, StandardCharsets.UTF_8);
        }

        return PluginDeclarations.decode(lines);
    }

    /**
     * What a describe reply depends on: the engine, the plugin's own version and config, the
     * content of the worker jar that answers, and the project facts — through the same {@link
     * ProjectFacts#token()} the action keys use, so this cache and the step/packager keys cannot
     * disagree about which facts matter. The jar's content is what the manifest version stands
     * for; a rebuilt plugin without a version bump declares different steps under the same
     * version, and keying on the version alone kept serving its previous describe reply. Package-
     * visible so tests can pre-seed the describe cache without forking a worker.
     */
    static String describeKey(ActivePlugin active, JkBuild project, @Nullable Path workerJar) throws IOException {
        String key = BuildIdentity.cacheKeyVersion()
                + '|'
                + active.manifest().version()
                + '|'
                + configToken(active.config())
                + '|'
                + facts(project, project.mainClass()).token()
                + '|'
                + (workerJar == null ? "worker:absent" : ClasspathFingerprint.entry(workerJar));
        return Hashing.sha256Hex(key.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** A stable render of the validated config — the token action keys carry for In.config(). */
    public static String configToken(PluginConfig config) {
        StringBuilder b = new StringBuilder(config.id());
        appendToken(b, config.values());
        return Hashing.sha256Hex(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    @SuppressWarnings("unchecked")
    private static void appendToken(StringBuilder b, Map<String, Object> values) {
        for (Map.Entry<String, Object> e : new TreeMap<>(values).entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m) {
                b.append('|').append(e.getKey()).append("={");
                appendToken(b, (Map<String, Object>) m);
                b.append('}');
            } else {
                b.append('|').append(e.getKey()).append('=').append(e.getValue());
            }
        }
    }

    // ---- execution -----------------------------------------------------------------------------

    /**
     * The resolved packager-dependency artifacts, fetched into the CAS by coordinate. The lock at
     * {@code lockFile} supplies the platform versions a {@code ${config.<key>}} segment reads.
     */
    public static Map<String, Path> fetchPackagerDependencies(JkBuild project, Path moduleDir, Cas cas, Path lockFile)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        List<PluginContributions.PackagerDep> deps =
                PluginContributions.packagerDependencies(project, moduleDir, platformPins(moduleDir, lockFile));
        if (deps.isEmpty()) return out;
        RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        for (PluginContributions.PackagerDep dep : deps) {
            // A manifest may float its tool ("^4"); resolve to a concrete release.
            String version = resolveToolVersion(repos, dep.module(), dep.version());
            out.put(dep.artifact(), fetchArtifact(repos, dep.module(), version));
        }
        return out;
    }

    /**
     * The whole {@code [[contribute.step-dependency]]} lane, fetched into the CAS — what a plugin
     * command receives beside its own lane. A build never calls this: its steps, packager and
     * compile classpath each take their slice through {@link StepTools}. {@code lenient} omits
     * failed provisions from the map (callers that need them fail themselves — e.g. {@code jk
     * android licenses} before any license is accepted). The lock at {@code lockFile}, when it
     * exists, supplies the sdk-component and platform pins.
     */
    public static Map<String, Path> fetchStepDependencies(
            JkBuild project, Path moduleDir, Cas cas, Path lockFile, boolean lenient)
            throws IOException, InterruptedException {
        List<PluginContributions.StepDep> lane =
                PluginContributions.stepDependencies(project, moduleDir, platformPins(moduleDir, lockFile));
        return fetchTools(lane, project, cas, sdkPins(lockFile), lenient);
    }

    /**
     * One module build's step-lane tools: the declared lane, derived once, partitioned by the
     * step or packager that reads each tool ({@code for-step}), plus every path fetched so far.
     * A step body, the packager and the compile classpath share one instance, so a tool two of
     * them read resolves once and a tool none of them read is never touched.
     */
    public static final class StepTools {
        private @Nullable List<PluginContributions.StepDep> lane;
        private final Map<String, Path> fetched = new ConcurrentHashMap<>();

        private synchronized List<PluginContributions.StepDep> lane(JkBuild project, Path moduleDir, Path lockFile) {
            List<PluginContributions.StepDep> l = lane;
            if (l == null) {
                l = PluginContributions.stepDependencies(project, moduleDir, platformPins(moduleDir, lockFile));
                lane = l;
            }
            return l;
        }

        /**
         * The tools {@code consumer} — a step or packager name — reads: every unscoped tool plus
         * the ones whose {@code for-step} names it, in declaration order.
         */
        public List<PluginContributions.StepDep> forConsumer(
                JkBuild project, Path moduleDir, Path lockFile, String consumer) {
            List<PluginContributions.StepDep> out = new ArrayList<>();
            for (PluginContributions.StepDep dep : lane(project, moduleDir, lockFile)) {
                if (dep.reaches(consumer)) out.add(dep);
            }
            return out;
        }

        /**
         * The declared tools under {@code names}, whatever their scope — a consumer that names its
         * tools itself ({@code [[contribute.provided-classpath]]}). Unknown names are left out.
         */
        public List<PluginContributions.StepDep> named(
                JkBuild project, Path moduleDir, Path lockFile, Collection<String> names) {
            List<PluginContributions.StepDep> out = new ArrayList<>();
            for (PluginContributions.StepDep dep : lane(project, moduleDir, lockFile)) {
                if (names.contains(dep.artifact())) out.add(dep);
            }
            return out;
        }

        /**
         * Fetch {@code deps} into the CAS, strictly: artifact name to path, in {@code deps} order.
         * A tool this build fetched earlier is handed back without a second resolve.
         */
        public Map<String, Path> fetch(
                List<PluginContributions.StepDep> deps, JkBuild project, Cas cas, Map<String, String> sdkPins)
                throws IOException, InterruptedException {
            List<PluginContributions.StepDep> missing = new ArrayList<>();
            for (PluginContributions.StepDep dep : deps) {
                if (!fetched.containsKey(dep.artifact())) missing.add(dep);
            }
            fetched.putAll(fetchTools(missing, project, cas, sdkPins, false));
            Map<String, Path> out = new LinkedHashMap<>();
            for (PluginContributions.StepDep dep : deps) {
                out.put(dep.artifact(), Objects.requireNonNull(fetched.get(dep.artifact()), dep.artifact()));
            }
            return out;
        }
    }

    /**
     * Fetch {@code [[contribute.command-dependency]]} tools the same way — the command lane.
     * Only plugin commands read these: a build provisions the step lane alone, so a command-only
     * tool is never fetched by a step or packager and joins no action key.
     */
    public static Map<String, Path> fetchCommandDependencies(
            JkBuild project, Path moduleDir, Cas cas, Path lockFile, boolean lenient)
            throws IOException, InterruptedException {
        List<PluginContributions.StepDep> lane =
                PluginContributions.commandDependencies(project, moduleDir, platformPins(moduleDir, lockFile));
        return fetchTools(lane, project, cas, sdkPins(lockFile), lenient);
    }

    private static Map<String, Path> fetchTools(
            List<PluginContributions.StepDep> deps,
            JkBuild project,
            Cas cas,
            Map<String, String> sdkPins,
            boolean lenient)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        if (deps.isEmpty()) return out;
        RepoGroup repos = null;
        for (PluginContributions.StepDep dep : deps) {
            try {
                if (dep.sdkComponent() != null) {
                    out.put(
                            dep.artifact(),
                            SdkComponents.resolve(dep.sdkComponent(), dep.sdkPath(), sdkPins.get(dep.sdkComponent())));
                    continue;
                }
                if (dep.url() != null) {
                    out.put(dep.artifact(), UrlTools.fetch(dep.url(), cas));
                    continue;
                }
                if (repos == null) repos = RepoGroupBuilder.buildFor(project, null, cas);
                if (dep.transitive()) {
                    out.put(dep.artifact(), ToolClosures.materialize(dep, repos, cas));
                    continue;
                }
                Coordinate coord = resolveCoordinate(repos, dep.coordinateSpec());
                out.put(
                        dep.artifact(),
                        repos.tryFetchArtifact(coord)
                                .orElseThrow(() -> new IOException("cannot fetch " + coord
                                        + " — the coordinate a plugin's step-dependency names must exist in a"
                                        + " declared repo"))
                                .fetched()
                                .cachePath());
            } catch (IOException | RuntimeException e) {
                if (!lenient) throw e;
                // Best-effort: the command that needs this tool reports the miss itself.
            }
        }
        return out;
    }

    /** The {@code [[sdk]]} revision pins of {@code lockFile}, or empty (no lock / none recorded). */
    public static Map<String, String> sdkPins(Path lockFile) {
        Lockfile lock = lockOrNull(lockFile);
        if (lock == null) return Map.of();
        Map<String, String> pins = new LinkedHashMap<>();
        for (var e : lock.sdk()) {
            pins.put(e.component(), e.revision());
        }
        return pins;
    }

    /**
     * {@link Lockfile#platformPins()} of {@code lockFile} as the module at {@code moduleDir} reads
     * it — a member's own partition rows before the workspace's — or empty (no lock / nothing
     * managed).
     */
    public static Map<String, String> platformPins(Path moduleDir, Path lockFile) {
        Lockfile lock = lockOrNull(lockFile);
        return lock == null
                ? Map.of()
                : MemberRows.view(lock, lockFile, moduleDir).platformPins();
    }

    /** The lock at {@code lockFile}, or null when there is none readable — a pin lookup before the first lock. */
    private static @Nullable Lockfile lockOrNull(Path lockFile) {
        if (!Files.isRegularFile(lockFile)) return null;
        try {
            return LockfileReader.read(lockFile);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Concrete version for a packager/step tool jar.
     *
     * <p><b>A bare version is exact.</b> A tool coordinate in a {@code jk-plugin.toml} is written
     * by the plugin author, not the project, and a literal like {@code 8.5.35} is a deliberate pin
     * — android's r8/aapt2/manifest-merger versions are chosen to match one AGP tools line. Only
     * an explicit {@code ^}/{@code ~} floats, and only then does this touch maven-metadata; an
     * exact spec costs no network at all. This is the opposite of the {@code jk.toml} dependency
     * convention on purpose.
     */
    static String resolveToolVersion(RepoGroup repos, String module, String versionSpec)
            throws IOException, InterruptedException {
        if (versionSpec == null || versionSpec.isBlank()) {
            throw new IllegalArgumentException("tool version is blank for " + module);
        }
        int colon = module.indexOf(':');
        if (colon <= 0 || colon != module.lastIndexOf(':')) {
            throw new IllegalArgumentException("tool module must be group:artifact — got " + module);
        }
        return PlatformBomVersions.resolve(
                repos, module.substring(0, colon), module.substring(colon + 1), VersionSelector.parse(versionSpec));
    }

    /**
     * {@code group:artifact:version[:classifier]} where the version segment may float. Bare is
     * exact — see {@link #resolveToolVersion} for why.
     */
    static Coordinate resolveCoordinate(RepoGroup repos, @Nullable String gav)
            throws IOException, InterruptedException {
        Coordinate raw = Coordinate.parse(Objects.requireNonNull(gav, "coordinate"));
        String resolved = resolveToolVersion(repos, raw.module(), raw.version());
        if (resolved.equals(raw.version())) return raw;
        return new Coordinate(raw.group(), raw.artifact(), resolved, raw.classifier(), raw.type());
    }

    /** Fetch one {@code module:version} jar into the CAS and return its path. */
    private static Path fetchArtifact(RepoGroup repos, String module, String version)
            throws IOException, InterruptedException {
        int colon = module.indexOf(':');
        Coordinate coord = Coordinate.of(module.substring(0, colon), module.substring(colon + 1), version);
        return repos.tryFetchArtifact(coord)
                .orElseThrow(() -> new IOException("cannot fetch " + coord
                        + " — the version a plugin's packager-dependency names must exist in a declared repo"))
                .fetched()
                .cachePath();
    }

    /**
     * The production classpath a step sees ({@code In.runtimeClasspath()}): lockfile RUNTIME jars
     * + workspace sibling jars (and their transitive RUNTIME deps). The module's own classes dir
     * is NOT included — the plugin adds {@code exec.classesDir()} itself.
     *
     * <p>{@code cas} is the planner's artifact store, the one {@code resolve-deps} synced into.
     * Every checksummed row of the module's lock, and of each sibling's own lock, must be on disk
     * there; a missing one fails the step naming the row, as the compile classpaths do, so the
     * runtime closure a step or packager ships is never silently short of a jar.
     */
    public static List<Path> productionClasspath(Path projectDir, Cas cas, Path lockFile, JkBuild project)
            throws IOException {
        return closure(projectDir, cas, lockFile, project, ClasspathResolver.RUNTIME);
    }

    /**
     * The module's compile classpath as a step body sees it: the lock's COMPILE_MAIN closure
     * ({@code provided} included, runtime-only absent) followed by the workspace siblings' jars and
     * their compile closures — {@link #productionClasspath} over the other scope set.
     */
    public static List<Path> compileClasspath(Path projectDir, Cas cas, Path lockFile, JkBuild project)
            throws IOException {
        return closure(projectDir, cas, lockFile, project, ClasspathResolver.COMPILE_MAIN);
    }

    /** The lock's closure under {@code scopes}, then the siblings' jars and their closures, deduplicated in order. */
    private static List<Path> closure(Path projectDir, Cas cas, Path lockFile, JkBuild project, Set<Scope> scopes)
            throws IOException {
        List<Path> classpath = new ArrayList<>();
        var resolver = new ClasspathResolver(cas);
        if (Files.exists(lockFile)) {
            classpath.addAll(resolver.classpathFor(LockfileReader.read(lockFile), scopes, true, project));
        }
        WorkspaceClasspath.Result siblings = siblingsOrNone(projectDir, project);
        for (Path jar : siblings.jars()) {
            if (!classpath.contains(jar)) classpath.add(jar);
        }
        for (Path pth : resolver.siblingClasspath(siblings.siblingLocks(), scopes, true)) {
            if (!classpath.contains(pth)) classpath.add(pth);
        }
        return classpath;
    }

    /** The MAIN/EXPORT workspace siblings of {@code projectDir}; a module outside any workspace has none. */
    private static WorkspaceClasspath.Result siblingsOrNone(Path projectDir, JkBuild project) {
        try {
            return WorkspaceClasspath.resolve(projectDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
        } catch (IOException | RuntimeException e) {
            Log.debug("productionClasspath: no workspace", e);
            return new WorkspaceClasspath.Result(List.of(), List.of());
        }
    }

    /**
     * One production runtime entry the engine hands a step/packager (container-aware).
     *
     * <p>{@code group}/{@code artifact}/{@code version} come from the lock; they are empty for a
     * workspace sibling. Steps need them because the jar path points into the content-addressed
     * store, where nothing about the coordinate survives.
     */
    public record ProdEntry(
            String fileName,
            @Nullable Path jar,
            boolean snapshot,
            @Nullable Path container,
            String group,
            String artifact,
            String version) {

        public ProdEntry(String fileName, @Nullable Path jar, boolean snapshot, @Nullable Path container) {
            this(fileName, jar, snapshot, container, "", "", "");
        }
    }

    /**
     * The production RUNTIME entries a step sees ({@code In.runtimeEntries()}): lock-ordered
     * artifacts (an AAR rides as its exploded container + classes.jar) followed by workspace
     * sibling artifacts (a sibling that also produced a container artifact — e.g. an [android]
     * library's AAR next to its conventional classes jar — rides with that container attached).
     * Lock rows resolve against {@code cas} as in {@link #productionClasspath}: a row that is not
     * on disk fails the step by name instead of leaving the packaged closure.
     */
    public static List<ProdEntry> productionEntries(Path projectDir, Cas cas, Path lockFile, JkBuild project)
            throws IOException {
        List<ProdEntry> out = new ArrayList<>();
        if (Files.exists(lockFile)) {
            var resolver = new ClasspathResolver(cas);
            for (var entry :
                    resolver.entriesFor(LockfileReader.read(lockFile), ClasspathResolver.RUNTIME, true, project)) {
                var a = entry.artifact();
                String ext = entry.container() != null ? ".aar" : ".jar";
                out.add(new ProdEntry(
                        a.moduleArtifact() + "-" + a.version() + ext,
                        entry.jar(),
                        a.version().contains("SNAPSHOT"),
                        entry.container(),
                        a.moduleGroup(),
                        a.moduleArtifact(),
                        a.version()));
            }
        }
        try {
            var siblings = WorkspaceClasspath.resolve(projectDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
            for (Path jar : siblings.jars()) {
                Path container = null;
                String name = jar.getFileName().toString();
                Path aar = jar.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".aar");
                if (Files.isRegularFile(aar)) {
                    container = ExplodedArchives.explodeFile(cas, aar);
                    name = aar.getFileName().toString();
                }
                out.add(new ProdEntry(name, Files.isRegularFile(jar) ? jar : null, true, container));
            }
        } catch (Exception e) {
            /* no workspace — fine */
            Log.debug("ProdEntry: no workspace", e);
        }
        return out;
    }

    /**
     * The test runtime entries a step sees ({@code In.testRuntimeEntries()}): the lock's test
     * closure in lock order, then the workspace siblings the test scopes reach. What the forked
     * test JVM's classpath is made of, as entries with coordinates.
     */
    public static List<ProdEntry> testRuntimeEntries(Path projectDir, Path lockFile, JkBuild project)
            throws IOException {
        List<ProdEntry> out = new ArrayList<>();
        if (Files.exists(lockFile)) {
            var resolver = new ClasspathResolver(JkStores.storeCas());
            for (var entry :
                    resolver.entriesFor(LockfileReader.read(lockFile), ClasspathResolver.TEST, false, project)) {
                var a = entry.artifact();
                out.add(new ProdEntry(
                        a.moduleArtifact() + "-" + a.version() + ".jar",
                        entry.jar(),
                        a.version().contains("SNAPSHOT"),
                        entry.container(),
                        a.moduleGroup(),
                        a.moduleArtifact(),
                        a.version()));
            }
        }
        try {
            var siblings = WorkspaceClasspath.resolve(
                    projectDir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST, Scope.TEST_DEV));
            for (Path jar : siblings.siblingClosureJars()) {
                out.add(new ProdEntry(jar.getFileName().toString(), Files.isRegularFile(jar) ? jar : null, true, null));
            }
        } catch (Exception e) {
            /* no workspace — fine */
            Log.debug("testRuntimeEntries: no workspace", e);
        }
        return out;
    }

    /**
     * The main artifact's path under the packager's declared extension ({@code
     * target/lib/<name>-<version>.apk}) — the one place the extension swap lives.
     */
    public static Path mainArtifactPath(BuildLayout layout, ActivePlugin active) {
        Path jarPath = layout.mainJar();
        var packaging = active.manifest().packaging();
        if (packaging != null) packaging = packaging.resolve(active.config());
        String ext = packaging == null ? "jar" : packaging.artifactExtension();
        if (!"jar".equals(ext)) {
            String fileName = jarPath.getFileName().toString();
            jarPath = jarPath.resolveSibling(fileName.substring(0, fileName.length() - ".jar".length()) + "." + ext);
        }
        return jarPath;
    }

    /** The fact set a plugin body sees; {@link ProjectFacts#token()} is the same set as a key. */
    public static ProjectFacts facts(JkBuild project, @Nullable String resolvedMain) {
        return new ProjectFacts(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                project.project().javaRelease(),
                resolvedMain,
                project.nativeConfigOpt().isPresent(),
                project.project().isKotlin(),
                project.manifest());
    }

    /**
     * The jar a plugin's code hooks fork: first-party plugins name a registered {@link PluginJar};
     * a third-party plugin IS its jar — the [plugins]-declared, lock-pinned, SHA-verified jar
     * from the CAS — and it forks only once its {@link #trustKey trust key} is trusted: the engine
     * refuses untrusted third-party code with the {@code jk trust plugin} remediation.
     */
    static Path workerJarFor(ActivePlugin active, Path cache) throws IOException {
        PluginDeclaration declaration = active.declaration();
        if (declaration != null && !"cc.jumpkick".equals(declaration.group())) {
            String stateOverride = System.getProperty("jk.trust.state.dir");
            Path stateDir = stateOverride != null ? Path.of(stateOverride) : JkDirs.state();
            TrustedPlugins trust;
            try {
                trust = TrustedPlugins.load(stateDir);
            } catch (IOException e) {
                trust = null;
            }
            if (trust == null || !trust.isTrusted(trustKey(declaration)))
                throw new IOException(trustRefusal(declaration));
        }
        return workerJarPath(active, cache);
    }

    /**
     * What {@code trusted-plugins.toml} must list for {@code declaration} to fork: a Maven pin's
     * {@code group:artifact}; a path pin's {@code sha256:<hex>} — the bytes the row already pins,
     * never the alias {@code path:<name>}, which is machine-wide and would survive a swap of the
     * jar behind it.
     */
    static String trustKey(PluginDeclaration declaration) {
        return declaration.isPathPin() ? "sha256:" + declaration.sha256() : declaration.coordinate();
    }

    /** The refusal a fork of an untrusted plugin fails with, naming the one command that trusts it. */
    static String trustRefusal(PluginDeclaration declaration) {
        String what = declaration.isPathPin()
                ? "plugin " + declaration.alias() + " (the jar pinned by path, sha256 " + declaration.sha256() + ")"
                : "plugin " + declaration.coordinateWithVersion();
        return what + " is not trusted to run build code on this machine.\n" + "Trust it first: jk trust plugin "
                + trustKey(declaration);
    }

    /**
     * The worker jar's location with no trust judgement, or {@code null} when it is nowhere on
     * this machine: the describe cache keys on the jar's content before anything forks, and a
     * plugin whose jar is missing simply keys as absent and fails at the fork with the message
     * that names the remedy.
     */
    static @Nullable Path locateWorkerJar(ActivePlugin active, Path cache) {
        try {
            return workerJarPath(active, cache);
        } catch (IOException | RuntimeException absent) {
            return null;
        }
    }

    private static Path workerJarPath(ActivePlugin active, Path cache) throws IOException {
        PluginDeclaration declaration = active.declaration();
        if (declaration != null) {
            return PluginDescriptorOps.jarFor(active.moduleDir(), declaration, cache)
                    .orElseThrow(() -> new IOException("plugin " + declaration.coordinateWithVersion()
                            + " is not in the local cache — run `jk sync` first"));
        }
        // A built-in plugin is the running jk's own copy: the lock's row for it records which jk
        // built last (see FirstPartyPins), it does not choose the bytes.
        if (PluginTableRegistry.isBuiltIn(active.manifest().id())) {
            String worker = code(active).worker();
            PluginJar workerJar = PluginJar.byArtifactId(worker)
                    .orElseThrow(() -> new IllegalStateException(
                            "plugin " + active.manifest().id() + " names unregistered worker " + worker));
            return workerJar.locate(JkStores.storeCas());
        }
        throw new IOException("plugin " + active.manifest().id()
                + " has no matching [plugins] declaration — declare it (or run `jk sync`)");
    }

    /**
     * The SDK floor a pinned third-party plugin forks with: the {@code [[artifact]]} rows its
     * consumer's lock carries under the plugin scope, resolved on this machine. Empty for a
     * first-party or workspace plugin, whose classpath its own POM or the workspace supplies.
     */
    static List<Path> sdkFloor(ActivePlugin active) throws IOException {
        PluginDeclaration declaration = active.declaration();
        if (declaration == null || !PluginSdkFloor.needsFloor(declaration)) return List.of();
        Path lockFile = LockPaths.lockFile(active.moduleDir());
        List<Path> floor = Files.isRegularFile(lockFile)
                ? PluginSdkFloor.classpath(LockfileReader.read(lockFile), JkStores.storeCas())
                : List.of();
        if (floor.isEmpty()) {
            throw new IOException("plugin " + active.manifest().id() + ": "
                    + PluginSdkFloor.missing(declaration.coordinate(), PluginSdkFloor.version(active.manifest())));
        }
        return floor;
    }

    /** As {@link #runWorker(ActivePlugin, Path, Path, WorkerEnv, Consumer, Consumer)} with no diagnostic sink. */
    public static List<String> runWorker(
            ActivePlugin active, Path cache, Path spec, WorkerEnv env, @Nullable Consumer<String> onLabel)
            throws IOException, InterruptedException {
        return runWorker(active, cache, spec, env, onLabel, null);
    }

    /**
     * Fork the plugin on the spec and collect its protocol lines. Throws with the plugin's own
     * error message when it reports one (or exits non-zero without reporting). {@code onDiagnostic}
     * receives each {@code diagnostic} reply as it arrives — before the throw, so a failing step's
     * located findings still reach the report.
     */
    public static List<String> runWorker(
            ActivePlugin active,
            Path cache,
            Path spec,
            WorkerEnv env,
            @Nullable Consumer<String> onLabel,
            @Nullable Consumer<String> onDiagnostic)
            throws IOException, InterruptedException {
        Path jar = workerJarFor(active, cache);
        List<String> collected = new ArrayList<>();
        // Non-protocol output (stack traces land here — stderr is merged by PluginProcess).
        // Kept so a worker that dies without reporting a protocol error is still diagnosable.
        ArrayDeque<String> tail = new ArrayDeque<>();
        @Nullable String[] error = new String[1];
        PluginClient client = new PluginClient(code(active).protocolPrefix())
                .on("label", line -> {
                    if (onLabel != null) onLabel.accept(Jsonl.str(line, "text"));
                })
                .on("error", line -> error[0] = Jsonl.str(line, "message"))
                .on(PluginProtocol.DIAGNOSTIC, line -> {
                    if (onDiagnostic != null) onDiagnostic.accept(line);
                    else collected.add(line);
                })
                .onOther(collected::add)
                .passthrough(line -> {
                    if (tail.size() >= 20) tail.removeFirst();
                    tail.addLast(line);
                });
        int exit =
                client.run(PluginLaunch.javaCommand(jar, spec, code(active).protocolPrefix(), sdkFloor(active)), env);
        if (error[0] != null) {
            throw new IOException(error[0]);
        }
        if (exit != 0) {
            String detail = tail.isEmpty() ? "" : "\n" + String.join("\n", tail);
            throw new IOException("plugin worker " + active.manifest().id() + " failed (exit " + exit + ")" + detail);
        }
        return collected;
    }
}
