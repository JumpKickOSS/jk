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
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
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
import cc.jumpkick.repo.EffectivePom;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.LockOrchestrator;
import cc.jumpkick.resolver.NaiveResolver;
import cc.jumpkick.resolver.PlatformBomVersions;
import cc.jumpkick.resolver.PlatformConstraints;
import cc.jumpkick.resolver.PubGrubResolver;
import cc.jumpkick.resolver.Resolution;
import cc.jumpkick.runtime.base.PluginDescriptorOps;
import cc.jumpkick.runtime.base.PluginLaunch;
import cc.jumpkick.runtime.base.SdkComponents;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.tool.TrustedPlugins;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * An installed plugin with a code layer, active on this project (owns a declared table); the
     * module's set is {@link ActivePlugins#of}. {@code declaration} is the matching
     * {@code [plugins]} entry for third-party plugins and null for built-ins — it carries the
     * coordinate the trust gate and jar lookup key on.
     */
    public record Active(
            PluginDescriptor manifest,
            PluginConfig config,
            Path moduleDir,
            @Nullable PluginDeclaration declaration) {}

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

    /** One registered task, as declared over the describe protocol. */
    public record TaskDecl(
            String name,
            List<String> requires,
            List<String> inputs,
            List<String> outputs,
            List<String> contributesClasses,
            List<String> contributesResources,
            List<String> contributesSources,
            List<String> contributesTestClasspath,
            /** The classes-replacing output dir ({@code TaskSpec.transformsClasses}), or null. */
            @Nullable String transformsClasses,
            /**
             * Optional product stage wire ({@code generate}, {@code compile}, …). Empty/null → engine
             * infers from contributions / name.
             */
            @Nullable String stage) {

        /** True when this task replaces the module's classes dir downstream. */
        public boolean transforms() {
            return transformsClasses != null && !transformsClasses.isBlank();
        }

        /** True when this task feeds the compiler source set. */
        public boolean sourceGenerating() {
            return contributesSources != null && !contributesSources.isEmpty();
        }

        /** True when this task only contributes to the test runtime classpath. */
        public boolean testOnly() {
            return contributesTestClasspath != null
                    && !contributesTestClasspath.isEmpty()
                    && !sourceGenerating()
                    && (contributesClasses == null || contributesClasses.isEmpty())
                    && (contributesResources == null || contributesResources.isEmpty())
                    && !transforms();
        }

        /**
         * True when package/classes consumers must wait on this task (post-compile work, transforms,
         * class/resource contributions — not pure source generation).
         */
        public boolean packageTime() {
            if (sourceGenerating() && !transforms() && (contributesClasses == null || contributesClasses.isEmpty())) {
                return false;
            }
            if (testOnly()) return false;
            return transforms()
                    || (contributesClasses != null && !contributesClasses.isEmpty())
                    || (contributesResources != null && !contributesResources.isEmpty())
                    || (inputs != null && inputs.contains("classes"));
        }
    }

    /** The registered packager, as declared. */
    /**
     * The {@code [code]} table of a plugin that has one. Every caller here has already established
     * that: a code plugin is precisely a manifest with this table, and {@link ActivePlugins#of}
     * skips the ones without it.
     */
    static PluginDescriptor.Code code(Active active) {
        return Objects.requireNonNull(active.manifest().code(), "plugin [code] table");
    }

    public record PackagerDecl(String name, List<String> inputs) {}

    /** One registered plugin command, as declared. */
    public record CommandDecl(String name, String description) {}

    public record Declarations(
            List<TaskDecl> steps, @Nullable PackagerDecl packager, List<CommandDecl> commands) {

        public @Nullable TaskDecl step(String name) {
            for (TaskDecl s : steps) if (s.name().equals(name)) return s;
            return null;
        }

        public @Nullable CommandDecl command(@Nullable String name) {
            for (CommandDecl v : commands) if (v.name().equals(name)) return v;
            return null;
        }
    }

    /** A step's scratch root — its declared output dirs resolve under this. */
    public static Path taskScratch(BuildLayout layout, @Nullable String stepName) {
        return layout.moduleTargetDir().resolve("plugin").resolve(stepName);
    }

    /** Every dir the declared steps contribute as classes/resources, in declaration order. */
    public static List<Path> contributedDirs(Declarations decls, BuildLayout layout) {
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
    public static Declarations declarations(
            Active active, JkBuild project, Path moduleDir, Path cache, Path layoutTarget)
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

        return decode(lines);
    }

    /** Decode a describe reply's declaration lines (the cached file's exact content). */
    static Declarations decode(List<String> lines) {
        List<TaskDecl> steps = new ArrayList<>();
        PackagerDecl packager = null;
        List<CommandDecl> commands = new ArrayList<>();
        for (String line : lines) {
            switch (String.valueOf(Jsonl.str(line, "t"))) {
                case "task", "step" ->
                    steps.add(new TaskDecl(
                            Jsonl.requiredStr(line, "name"),
                            Jsonl.strArray(line, "requires"),
                            Jsonl.strArray(line, "inputs"),
                            Jsonl.strArray(line, "outputs"),
                            Jsonl.strArray(line, "contributesClasses"),
                            Jsonl.strArray(line, "contributesResources"),
                            Jsonl.strArray(line, "contributesSources"),
                            Jsonl.strArray(line, "contributesTestClasspath"),
                            Jsonl.str(line, "transformsClasses"),
                            blankToNull(Jsonl.str(line, "stage"))));
                case "packager" ->
                    packager = new PackagerDecl(Jsonl.requiredStr(line, "name"), Jsonl.strArray(line, "inputs"));
                case "command" ->
                    commands.add(
                            new CommandDecl(Jsonl.requiredStr(line, "name"), Jsonl.requiredStr(line, "description")));
                default -> {
                    // labels etc. — irrelevant to declarations
                }
            }
        }
        return new Declarations(steps, packager, commands);
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
    static String describeKey(Active active, JkBuild project, @Nullable Path workerJar) throws IOException {
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
                PluginContributions.packagerDependencies(project, moduleDir, platformPins(lockFile));
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
                PluginContributions.stepDependencies(project, moduleDir, platformPins(lockFile));
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
                l = PluginContributions.stepDependencies(project, moduleDir, platformPins(lockFile));
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
                PluginContributions.commandDependencies(project, moduleDir, platformPins(lockFile));
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
                if (repos == null) repos = RepoGroupBuilder.buildFor(project, null, cas);
                if (dep.transitive()) {
                    out.put(dep.artifact(), toolClosureDir(dep, repos, cas));
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

    /**
     * Materialize a transitive step-dep's runtime closure under the CAS for {@code -cp <dir>/*}.
     *
     * <p>When {@code managed-by} and/or {@code with} are set, roots resolve as <em>one</em> graph
     * under BOM pins (PubGrub) — Maven-like tool classpath alignment, not freestyle dual trees.
     */
    private static Path toolClosureDir(PluginContributions.StepDep dep, RepoGroup repos, Cas cas)
            throws IOException, InterruptedException {
        // Resolve floating ${config.version} segments first so the CAS key tracks the concrete line.
        List<Coordinate> roots = new ArrayList<>();
        roots.add(resolveCoordinate(repos, dep.coordinateSpec()));
        for (String w : dep.with()) {
            roots.add(resolveCoordinate(repos, w));
        }
        String managedByResolved = null;
        if (dep.managedBy() != null && !dep.managedBy().isBlank()) {
            Coordinate bom = resolveCoordinate(repos, dep.managedBy());
            managedByResolved = bom.group() + ":" + bom.artifact() + ":" + bom.version();
        }

        String cacheKey = toolClosureCacheKey(roots, managedByResolved);
        Path dir = cas.root().resolve("plugin-tools").resolve(cacheKey);
        if (Files.isDirectory(dir)) {
            try (var listing = Files.list(dir)) {
                if (listing.findFirst().isPresent()) return dir;
            }
        }

        List<Dependency> declared = new ArrayList<>();
        for (Coordinate root : roots) {
            declared.add(
                    new Dependency(root.group() + ":" + root.artifact(), VersionSelector.parse("=" + root.version())));
        }

        Map<String, String> bomConstraints = Map.of();
        if (managedByResolved != null) {
            bomConstraints = loadBomConstraints(repos, managedByResolved);
        }

        Resolution resolution;
        if (!bomConstraints.isEmpty() || !dep.with().isEmpty()) {
            // One graph, BOM-aligned (or multi-root highest-wins under PubGrub).
            resolution = new PubGrubResolver(repos, bomConstraints).resolve(declared);
        } else {
            resolution = new NaiveResolver(new EffectivePomBuilder(repos)).resolve(declared);
        }

        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".closure-");
        // Dedupe by GAV so package-id keys (g:a:type:classifier) don't double-link the same jar.
        LinkedHashSet<String> seenGav = new LinkedHashSet<>();
        for (var resolved : resolution.modules().values()) {
            Coordinate coord = resolved.coordinate();
            if (!seenGav.add(coord.toGav())) continue;
            Path jar = repos.tryFetchArtifact(coord)
                    .orElseThrow(() -> new IOException("cannot fetch " + coord
                            + " — a transitive step-dependency's closure must exist in a declared repo"))
                    .fetched()
                    .cachePath();
            Path alias = staging.resolve(coord.artifact() + "-" + coord.version() + ".jar");
            try {
                Files.createLink(alias, jar);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(jar, alias);
            }
        }
        // Ensure declared roots are present even if the solver key form differed.
        for (Coordinate root : roots) {
            if (!seenGav.add(root.toGav())) continue;
            Path jar = repos.tryFetchArtifact(root)
                    .orElseThrow(() -> new IOException("cannot fetch " + root
                            + " — a transitive step-dependency's closure must exist in a declared repo"))
                    .fetched()
                    .cachePath();
            Path alias = staging.resolve(root.artifact() + "-" + root.version() + ".jar");
            try {
                Files.createLink(alias, jar);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(jar, alias);
            }
        }
        try {
            AtomicWrites.publishDir(staging, dir);
        } catch (IOException e) {
            if (!Files.isDirectory(dir)) throw e; // lost a race → the winner's dir serves
        }
        return dir;
    }

    /**
     * Stable CAS dir name for a tool closure (resolved roots + resolved BOM). Short keys stay
     * readable; long ones hash.
     */
    // Package-private for ToolClosureCacheKeyTest.
    static String toolClosureCacheKey(List<Coordinate> roots, @Nullable String managedByResolved) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) sb.append("__");
            sb.append(roots.get(i).toGav().replace(':', '_'));
        }
        if (managedByResolved != null && !managedByResolved.isBlank()) {
            sb.append("__bom_").append(managedByResolved.replace(':', '_'));
        }
        // Keep path components reasonable on case-sensitive FS / path length limits. The lookup
        // is `Files.isDirectory(dir)` with no content check, so a collision silently serves one
        // closure's jars for another — hash the whole key rather than truncating it and hoping
        // the tail differs in 32 bits of String.hashCode.
        String key = sb.toString();
        if (key.length() > 180) {
            String artifact = roots.isEmpty() ? "tools" : roots.getFirst().artifact();
            return Hashing.sha256Hex(key.getBytes(StandardCharsets.UTF_8)).substring(0, 40) + "_" + artifact;
        }
        return key;
    }

    /**
     * Load {@code group:artifact → version} pins from a BOM POM (and its imported BOMs via
     * EffectivePom expansion).
     */
    private static Map<String, String> loadBomConstraints(RepoGroup repos, String bomGav)
            throws IOException, InterruptedException {
        // BOM coordinates are type=pom (default parse is jar).
        String spec = bomGav.contains("!") ? bomGav : bomGav + "!pom";
        Coordinate bom = Coordinate.parse(spec);
        EffectivePom bomPom = new EffectivePomBuilder(repos).build(bom);
        return bomConstraintsOf(bomPom, bomGav);
    }

    /**
     * The {@code group:artifact → version} pins {@code bomPom} manages, with the maven-resolver
     * family aligned by its owner — {@link LockOrchestrator#alignMavenResolverFamily}, the same
     * derivation the lock path applies (the {@code maven-resolver.version} property, else a
     * managed api/impl pin), so a 2.x named-locks cannot land next to a 1.9 api on the tool
     * classpath either. The provenance map is the lock path's concern; this path discards it.
     */
    static Map<String, String> bomConstraintsOf(EffectivePom bomPom, String bomGav) throws IOException {
        Map<String, String> constraints = new LinkedHashMap<>();
        for (Pom.Dep m : bomPom.managedDependencies()) {
            if (m.version() == null || m.version().isBlank()) continue;
            constraints.putIfAbsent(m.module(), m.version());
        }
        PlatformConstraints.alignMavenResolverFamily(constraints, new HashMap<>(), bomPom, bomGav);
        if (constraints.isEmpty()) {
            throw new IOException("managed-by BOM " + bomGav + " contributed no managed dependency pins");
        }
        return constraints;
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

    /** {@link Lockfile#platformPins()} of {@code lockFile}, or empty (no lock / nothing managed). */
    public static Map<String, String> platformPins(Path lockFile) {
        Lockfile lock = lockOrNull(lockFile);
        return lock == null ? Map.of() : lock.platformPins();
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
     * Every checksummed row of the module's lock must be on disk there; a missing one fails the
     * step naming the row, as the compile classpaths do, so the runtime closure a step or packager
     * ships is never silently short of a jar.
     */
    public static List<Path> productionClasspath(Path projectDir, Cas cas, Path lockFile, JkBuild project)
            throws IOException {
        List<Path> classpath = new ArrayList<>();
        var resolver = new ClasspathResolver(cas);
        if (Files.exists(lockFile)) {
            classpath.addAll(resolver.classpathFor(LockfileReader.read(lockFile), ClasspathResolver.RUNTIME, true));
        }
        try {
            var siblings = WorkspaceClasspath.resolve(projectDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
            for (Path jar : siblings.jars()) {
                if (!classpath.contains(jar)) classpath.add(jar);
            }
            for (Path sibLock : siblings.siblingLockfiles()) {
                try {
                    var sib = LockfileReader.read(sibLock);
                    for (Path pth : resolver.classpathFor(sib, ClasspathResolver.RUNTIME, true)) {
                        if (!classpath.contains(pth)) classpath.add(pth);
                    }
                } catch (Exception e) {
                    /* best-effort, mirrors shadow packaging */
                    Log.debug("sdkPins: best-effort, mirrors shadow packaging", e);
                }
            }
        } catch (Exception e) {
            /* no workspace — fine */
            Log.debug("sdkPins: no workspace", e);
        }
        return classpath;
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
            for (var entry : resolver.entriesFor(LockfileReader.read(lockFile), ClasspathResolver.RUNTIME, true)) {
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
     * The main artifact's path under the packager's declared extension ({@code
     * target/lib/<name>-<version>.apk}) — the one place the extension swap lives.
     */
    public static Path mainArtifactPath(BuildLayout layout, Active active) {
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
     * from the CAS — and it forks only once its coordinate is trusted (plugin-refactor Posture A:
     * the engine refuses untrusted third-party code with the {@code jk trust plugin} remediation).
     */
    static Path workerJarFor(Active active, Path cache) throws IOException {
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
            if (trust == null || !trust.isTrusted(declaration.coordinate())) {
                throw new IOException("plugin " + declaration.coordinateWithVersion()
                        + " is not trusted to run build code on this machine.\n"
                        + "Trust it first: jk trust plugin " + declaration.coordinate());
            }
        }
        return workerJarPath(active, cache);
    }

    /**
     * The worker jar's location with no trust judgement, or {@code null} when it is nowhere on
     * this machine: the describe cache keys on the jar's content before anything forks, and a
     * plugin whose jar is missing simply keys as absent and fails at the fork with the message
     * that names the remedy.
     */
    static @Nullable Path locateWorkerJar(Active active, Path cache) {
        try {
            return workerJarPath(active, cache);
        } catch (IOException | RuntimeException absent) {
            return null;
        }
    }

    private static Path workerJarPath(Active active, Path cache) throws IOException {
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

    private static @Nullable String blankToNull(@Nullable String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * The SDK floor a pinned third-party plugin forks with: the {@code [[artifact]]} rows its
     * consumer's lock carries under the plugin scope, resolved on this machine. Empty for a
     * first-party or workspace plugin, whose classpath its own POM or the workspace supplies.
     */
    static List<Path> sdkFloor(Active active) throws IOException {
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

    /** As {@link #runWorker(Active, Path, Path, WorkerEnv, Consumer, Consumer)} with no diagnostic sink. */
    public static List<String> runWorker(
            Active active, Path cache, Path spec, WorkerEnv env, @Nullable Consumer<String> onLabel)
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
            Active active,
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
