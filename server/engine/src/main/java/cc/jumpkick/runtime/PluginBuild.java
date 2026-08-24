// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Engine build-plugin code layer: describe-protocol discovery (file-cached), execution specs, and
 * per-step forks — never classloads plugin code. Static packaging shape is manifest-only
 * ({@link #shape}).
 */
public final class PluginBuild {

    private PluginBuild() {}

    /**
     * An installed plugin with a code layer, active on this project (owns a declared table).
     * {@code declaration} is the matching {@code [plugins]} entry for third-party plugins and
     * null for built-ins — it carries the coordinate the trust gate and jar lookup key on.
     */
    public record Active(
            PluginDescriptor manifest,
            PluginConfig config,
            Path moduleDir,
            cc.jumpkick.model.PluginDeclaration declaration) {}

    public static Optional<Active> activeCodePlugin(JkBuild project, Path moduleDir) {
        for (PluginDescriptor m : PluginTableRegistry.manifestsFor(moduleDir, project.plugins())) {
            if (m.code() == null) continue;
            Optional<PluginConfig> config = project.pluginConfig(m.id());
            if (config.isPresent()) {
                cc.jumpkick.model.PluginDeclaration declaration = PluginDescriptorOps.declarationOf(
                                moduleDir, project, m.id())
                        .orElse(null);
                return Optional.of(new Active(m, config.get(), moduleDir, declaration));
            }
        }
        return Optional.empty();
    }

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
            String transformsClasses,
            /**
             * Optional product stage wire ({@code generate}, {@code compile}, …). Empty/null → engine
             * infers from contributions / name.
             */
            String stage) {

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
    public record PackagerDecl(String name, List<String> inputs) {}

    /** One registered plugin command, as declared. */
    public record CommandDecl(String name, String description) {}

    public record Declarations(List<TaskDecl> steps, PackagerDecl packager, List<CommandDecl> commands) {

        public TaskDecl step(String name) {
            for (TaskDecl s : steps) if (s.name().equals(name)) return s;
            return null;
        }

        public CommandDecl command(String name) {
            for (CommandDecl v : commands) if (v.name().equals(name)) return v;
            return null;
        }
    }

    /** A step's scratch root — its declared output dirs resolve under this. */
    public static Path taskScratch(BuildLayout layout, String stepName) {
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
        String key = describeKey(active, project);
        Path cacheFile =
                layoutTarget.resolve("plugin").resolve(active.manifest().id() + "-describe-" + key + ".jsonl");
        List<String> lines;
        if (Files.isRegularFile(cacheFile)) {
            lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
        } else {
            Path spec = new cc.jumpkick.plugin.protocol.SpecWriter()
                    .op(
                            cc.jumpkick.plugin.protocol.PluginProtocol.OP_DESCRIBE,
                            null,
                            active.manifest().id())
                    .configValues(active.config().values())
                    .project(facts(project, project.mainClass()))
                    .writeTempSpec();
            try {
                lines = runWorker(active, cache, spec, null);
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
                            Jsonl.str(line, "name"),
                            Jsonl.strArray(line, "requires"),
                            Jsonl.strArray(line, "inputs"),
                            Jsonl.strArray(line, "outputs"),
                            Jsonl.strArray(line, "contributesClasses"),
                            Jsonl.strArray(line, "contributesResources"),
                            Jsonl.strArray(line, "contributesSources"),
                            Jsonl.strArray(line, "contributesTestClasspath"),
                            Jsonl.str(line, "transformsClasses"),
                            blankToNull(Jsonl.str(line, "stage"))));
                case "packager" -> packager = new PackagerDecl(Jsonl.str(line, "name"), Jsonl.strArray(line, "inputs"));
                case "command" ->
                    commands.add(new CommandDecl(Jsonl.str(line, "name"), Jsonl.str(line, "description")));
                default -> {
                    // labels etc. — irrelevant to declarations
                }
            }
        }
        return new Declarations(steps, packager, commands);
    }

    /**
     * What a describe reply depends on: the engine, the plugin's own version and config, and the
     * project facts — through the same {@link ProjectFacts#token()} the action keys use, so this
     * cache and the step/packager keys cannot disagree about which facts matter. Package-visible so
     * tests can pre-seed the describe cache without forking a worker.
     */
    static String describeKey(Active active, JkBuild project) {
        String key = BuildIdentity.cacheKeyVersion()
                + '|'
                + active.manifest().version()
                + '|'
                + configToken(active.config())
                + '|'
                + facts(project, project.mainClass()).token();
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

    /** The resolved packager-dependency artifacts, fetched into the CAS by coordinate. */
    public static Map<String, Path> fetchPackagerDependencies(JkBuild project, Path moduleDir, Cas cas)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        List<PluginContributions.PackagerDep> deps = PluginContributions.packagerDependencies(project, moduleDir);
        if (deps.isEmpty()) return out;
        cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        for (PluginContributions.PackagerDep dep : deps) {
            // ${config.version} may be a caret floor ("4"); resolve to a concrete release.
            String version = resolveToolVersion(repos, dep.module(), dep.version());
            out.put(dep.artifact(), fetchArtifact(repos, dep.module(), version));
        }
        return out;
    }

    /** Fetch {@code [[contribute.step-dependency]]} tool jars into the CAS by coordinate. */
    public static Map<String, Path> fetchStepDependencies(JkBuild project, Path moduleDir, Cas cas)
            throws IOException, InterruptedException {
        return fetchStepDependencies(project, moduleDir, cas, Map.of());
    }

    /** As above, with {@code [[sdk]]} revision pins (drift is reported, never ignored). */
    public static Map<String, Path> fetchStepDependencies(
            JkBuild project, Path moduleDir, Cas cas, Map<String, String> sdkPins)
            throws IOException, InterruptedException {
        return fetchStepDependencies(project, moduleDir, cas, sdkPins, false);
    }

    /**
     * As above; {@code lenient} omits failed provisions from the map (callers that need them fail
     * themselves — e.g. {@code jk android licenses} before any license is accepted).
     */
    public static Map<String, Path> fetchStepDependencies(
            JkBuild project, Path moduleDir, Cas cas, Map<String, String> sdkPins, boolean lenient)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        List<PluginContributions.StepDep> deps = PluginContributions.stepDependencies(project, moduleDir);
        if (deps.isEmpty()) return out;
        cc.jumpkick.repo.RepoGroup repos = null;
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
                cc.jumpkick.model.Coordinate coord = resolveCoordinate(repos, dep.coordinateSpec());
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
    private static Path toolClosureDir(PluginContributions.StepDep dep, cc.jumpkick.repo.RepoGroup repos, Cas cas)
            throws IOException, InterruptedException {
        // Resolve floating ${config.version} segments first so the CAS key tracks the concrete line.
        List<cc.jumpkick.model.Coordinate> roots = new ArrayList<>();
        roots.add(resolveCoordinate(repos, dep.coordinateSpec()));
        for (String w : dep.with()) {
            roots.add(resolveCoordinate(repos, w));
        }
        String managedByResolved = null;
        if (dep.managedBy() != null && !dep.managedBy().isBlank()) {
            cc.jumpkick.model.Coordinate bom = resolveCoordinate(repos, dep.managedBy());
            managedByResolved = bom.group() + ":" + bom.artifact() + ":" + bom.version();
        }

        String cacheKey = toolClosureCacheKey(roots, managedByResolved);
        Path dir = cas.root().resolve("plugin-tools").resolve(cacheKey);
        if (Files.isDirectory(dir)) {
            try (var listing = Files.list(dir)) {
                if (listing.findFirst().isPresent()) return dir;
            }
        }

        List<cc.jumpkick.model.Dependency> declared = new ArrayList<>();
        for (cc.jumpkick.model.Coordinate root : roots) {
            declared.add(new cc.jumpkick.model.Dependency(
                    root.group() + ":" + root.artifact(),
                    cc.jumpkick.model.VersionSelector.parse("=" + root.version())));
        }

        Map<String, String> bomConstraints = Map.of();
        if (managedByResolved != null) {
            bomConstraints = loadBomConstraints(repos, managedByResolved);
        }

        cc.jumpkick.resolver.Resolution resolution;
        if (!bomConstraints.isEmpty() || !dep.with().isEmpty()) {
            // One graph, BOM-aligned (or multi-root highest-wins under PubGrub).
            resolution = new cc.jumpkick.resolver.PubGrubResolver(repos, bomConstraints).resolve(declared);
        } else {
            resolution = new cc.jumpkick.resolver.NaiveResolver(new cc.jumpkick.repo.EffectivePomBuilder(repos))
                    .resolve(declared);
        }

        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".closure-");
        // Dedupe by GAV so package-id keys (g:a:type:classifier) don't double-link the same jar.
        LinkedHashSet<String> seenGav = new LinkedHashSet<>();
        for (var resolved : resolution.modules().values()) {
            cc.jumpkick.model.Coordinate coord = resolved.coordinate();
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
        for (cc.jumpkick.model.Coordinate root : roots) {
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
            cc.jumpkick.util.AtomicWrites.publishDir(staging, dir);
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
    static String toolClosureCacheKey(List<cc.jumpkick.model.Coordinate> roots, String managedByResolved) {
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
    private static Map<String, String> loadBomConstraints(cc.jumpkick.repo.RepoGroup repos, String bomGav)
            throws IOException, InterruptedException {
        // BOM coordinates are type=pom (default parse is jar).
        String spec = bomGav.contains("!") ? bomGav : bomGav + "!pom";
        cc.jumpkick.model.Coordinate bom = cc.jumpkick.model.Coordinate.parse(spec);
        cc.jumpkick.repo.EffectivePom bomPom = new cc.jumpkick.repo.EffectivePomBuilder(repos).build(bom);
        Map<String, String> constraints = new LinkedHashMap<>();
        for (cc.jumpkick.repo.Pom.Dep m : bomPom.managedDependencies()) {
            if (m.version() == null || m.version().isBlank()) continue;
            constraints.putIfAbsent(m.module(), m.version());
        }
        // Quarkus bootstrap-bom pins maven-resolver.* via ${maven-resolver.version} but often
        // omits named-locks from <dependencyManagement>; without a pin PubGrub highest-wins
        // pulls 2.x named-locks next to 1.9 api → NoSuchMethodError. Align the family.
        String resolverLine = bomPom.properties().get("maven-resolver.version");
        if (resolverLine != null && !resolverLine.isBlank()) {
            for (String art : List.of(
                    "maven-resolver-api",
                    "maven-resolver-spi",
                    "maven-resolver-util",
                    "maven-resolver-impl",
                    "maven-resolver-named-locks",
                    "maven-resolver-connector-basic",
                    "maven-resolver-transport-wagon",
                    "maven-resolver-transport-http",
                    "maven-resolver-transport-file")) {
                constraints.putIfAbsent("org.apache.maven.resolver:" + art, resolverLine);
            }
        }
        if (constraints.isEmpty()) {
            throw new IOException("managed-by BOM " + bomGav + " contributed no managed dependency pins");
        }
        return constraints;
    }

    /** The {@code [[sdk]]} revision pins of {@code lockFile}, or empty (no lock / none recorded). */
    public static Map<String, String> sdkPins(Path lockFile) {
        if (lockFile == null || !Files.isRegularFile(lockFile)) return Map.of();
        try {
            Map<String, String> pins = new LinkedHashMap<>();
            for (var e : cc.jumpkick.lock.LockfileReader.read(lockFile).sdk()) {
                pins.put(e.component(), e.revision());
            }
            return pins;
        } catch (Exception e) {
            return Map.of();
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
    static String resolveToolVersion(cc.jumpkick.repo.RepoGroup repos, String module, String versionSpec)
            throws IOException, InterruptedException {
        if (versionSpec == null || versionSpec.isBlank()) {
            throw new IllegalArgumentException("tool version is blank for " + module);
        }
        int colon = module.indexOf(':');
        if (colon <= 0 || colon != module.lastIndexOf(':')) {
            throw new IllegalArgumentException("tool module must be group:artifact — got " + module);
        }
        return cc.jumpkick.resolver.PlatformBomVersions.resolve(
                repos,
                module.substring(0, colon),
                module.substring(colon + 1),
                cc.jumpkick.model.VersionSelector.parse(versionSpec));
    }

    /**
     * {@code group:artifact:version[:classifier]} where the version segment may float. Bare is
     * exact — see {@link #resolveToolVersion} for why.
     */
    static cc.jumpkick.model.Coordinate resolveCoordinate(cc.jumpkick.repo.RepoGroup repos, String gav)
            throws IOException, InterruptedException {
        cc.jumpkick.model.Coordinate raw = cc.jumpkick.model.Coordinate.parse(gav);
        String resolved = resolveToolVersion(repos, raw.module(), raw.version());
        if (resolved.equals(raw.version())) return raw;
        return new cc.jumpkick.model.Coordinate(raw.group(), raw.artifact(), resolved, raw.classifier(), raw.type());
    }

    /** Fetch one {@code module:version} jar into the CAS and return its path. */
    private static Path fetchArtifact(cc.jumpkick.repo.RepoGroup repos, String module, String version)
            throws IOException, InterruptedException {
        int colon = module.indexOf(':');
        cc.jumpkick.model.Coordinate coord =
                cc.jumpkick.model.Coordinate.of(module.substring(0, colon), module.substring(colon + 1), version);
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
     */
    public static List<Path> productionClasspath(Path projectDir, Path cache, Path lockFile, JkBuild project)
            throws IOException {
        List<Path> classpath = new ArrayList<>();
        if (Files.exists(lockFile)) {
            var resolver = new cc.jumpkick.compile.ClasspathResolver(JkStores.cas(cache));
            classpath.addAll(resolver.classpathFor(
                    cc.jumpkick.lock.LockfileReader.read(lockFile), cc.jumpkick.compile.ClasspathResolver.RUNTIME));
        }
        try {
            var siblings = cc.jumpkick.config.WorkspaceClasspath.resolve(
                    projectDir, project, Set.of(cc.jumpkick.model.Scope.EXPORT, cc.jumpkick.model.Scope.MAIN));
            for (Path jar : siblings.jars()) {
                if (!classpath.contains(jar)) classpath.add(jar);
            }
            for (Path sibLock : siblings.siblingLockfiles()) {
                try {
                    var sib = cc.jumpkick.lock.LockfileReader.read(sibLock);
                    for (Path pth : new cc.jumpkick.compile.ClasspathResolver(JkStores.cas(cache))
                            .classpathFor(sib, cc.jumpkick.compile.ClasspathResolver.RUNTIME)) {
                        if (!classpath.contains(pth)) classpath.add(pth);
                    }
                } catch (Exception ignored) {
                    /* best-effort, mirrors shadow packaging */
                }
            }
        } catch (Exception ignored) {
            /* no workspace — fine */
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
            Path jar,
            boolean snapshot,
            Path container,
            String group,
            String artifact,
            String version) {

        public ProdEntry(String fileName, Path jar, boolean snapshot, Path container) {
            this(fileName, jar, snapshot, container, "", "", "");
        }
    }

    /**
     * The production RUNTIME entries a step sees ({@code In.runtimeEntries()}): lock-ordered
     * artifacts (an AAR rides as its exploded container + classes.jar) followed by workspace
     * sibling artifacts (a sibling that also produced a container artifact — e.g. an [android]
     * library's AAR next to its conventional classes jar — rides with that container attached).
     */
    public static List<ProdEntry> productionEntries(Path projectDir, Path cache, Path lockFile, JkBuild project)
            throws IOException {
        List<ProdEntry> out = new ArrayList<>();
        Cas cas = JkStores.cas(cache);
        if (Files.exists(lockFile)) {
            var resolver = new cc.jumpkick.compile.ClasspathResolver(cas);
            for (var entry : resolver.entriesFor(
                    cc.jumpkick.lock.LockfileReader.read(lockFile), cc.jumpkick.compile.ClasspathResolver.RUNTIME)) {
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
            var siblings = cc.jumpkick.config.WorkspaceClasspath.resolve(
                    projectDir, project, Set.of(cc.jumpkick.model.Scope.EXPORT, cc.jumpkick.model.Scope.MAIN));
            for (Path jar : siblings.jars()) {
                Path container = null;
                String name = jar.getFileName().toString();
                Path aar = jar.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".aar");
                if (Files.isRegularFile(aar)) {
                    container = cc.jumpkick.cache.ExplodedArchives.explodeFile(cas, aar);
                    name = aar.getFileName().toString();
                }
                out.add(new ProdEntry(name, Files.isRegularFile(jar) ? jar : null, true, container));
            }
        } catch (Exception ignored) {
            /* no workspace — fine */
        }
        return out;
    }

    /**
     * The main artifact's path under the packager's declared extension ({@code
     * target/lib/<name>-<version>.apk}) — the one place the extension swap lives.
     */
    public static Path mainArtifactPath(cc.jumpkick.layout.BuildLayout layout, Active active) {
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
    public static ProjectFacts facts(JkBuild project, String resolvedMain) {
        return new ProjectFacts(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                project.project().javaRelease(),
                resolvedMain,
                project.nativeConfig().isPresent(),
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
        cc.jumpkick.model.PluginDeclaration declaration = active.declaration();
        if (declaration != null) {
            if (!"cc.jumpkick".equals(declaration.group())) {
                String stateOverride = System.getProperty("jk.trust.state.dir");
                Path stateDir = stateOverride != null ? Path.of(stateOverride) : cc.jumpkick.util.JkDirs.state();
                cc.jumpkick.tool.TrustedPlugins trust;
                try {
                    trust = cc.jumpkick.tool.TrustedPlugins.load(stateDir);
                } catch (IOException e) {
                    trust = null;
                }
                if (trust == null || !trust.isTrusted(declaration.coordinate())) {
                    throw new IOException("plugin " + declaration.coordinateWithVersion()
                            + " is not trusted to run build code on this machine.\n"
                            + "Trust it first: jk trust plugin " + declaration.coordinate());
                }
            }
            return PluginDescriptorOps.jarFor(active.moduleDir(), declaration, cache)
                    .orElseThrow(() -> new IOException("plugin " + declaration.coordinateWithVersion()
                            + " is not in the local cache — run `jk sync` first"));
        }
        if (PluginTableRegistry.isBuiltIn(active.manifest().id())) {
            String worker = active.manifest().code().worker();
            Path locked = lockedFirstPartyJar(active.moduleDir(), worker, cache);
            if (locked != null) return locked;
            PluginJar workerJar = PluginJar.byArtifactId(worker)
                    .orElseThrow(() -> new IllegalStateException(
                            "plugin " + active.manifest().id() + " names unregistered worker " + worker));
            return workerJar.locate(JkStores.cas(cache));
        }
        throw new IOException("plugin " + active.manifest().id()
                + " has no matching [plugins] declaration — declare it (or run `jk sync`)");
    }

    /**
     * The jar the lock pinned for {@code workerArtifact}, or {@code null} when the lock has no
     * pin for it (newer-always-wins locate applies). A pin is law in both directions: a pin
     * whose bytes are nowhere is fetched at exactly the pinned version, and a pin that still
     * cannot be honored is a loud error — never a silent fall-through to whatever
     * {@code locate()} finds, which would run different bytes than the lock recorded.
     */
    static Path lockedFirstPartyJar(Path moduleDir, String workerArtifact, Path cache) throws IOException {
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(moduleDir);
        if (!Files.isRegularFile(lockFile)) return null;
        cc.jumpkick.lock.Lockfile lock;
        try {
            lock = cc.jumpkick.lock.LockfileReader.read(lockFile);
        } catch (Exception e) {
            throw new IOException("cannot read " + lockFile + ": " + e.getMessage(), e);
        }
        String coord = "cc.jumpkick:" + workerArtifact;
        for (var e : lock.plugins()) {
            if (!coord.equals(e.coordinate())) continue;
            var pinned = PluginDescriptorOps.pinnedLayoutJar(
                    JkStores.cas(cache), e.coordinate(), e.version(), e.sha256Hex());
            if (pinned.isPresent()) return pinned.get();
            String fetchFailure = null;
            try {
                cc.jumpkick.engine.plugin.PluginJar.fetchOfficial(
                        JkStores.cas(cache),
                        cc.jumpkick.repo.MavenLayout.artifactPath(
                                cc.jumpkick.model.Coordinate.ofModule(e.coordinate(), e.version())));
                pinned = PluginDescriptorOps.pinnedLayoutJar(
                        JkStores.cas(cache), e.coordinate(), e.version(), e.sha256Hex());
                if (pinned.isPresent()) return pinned.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                fetchFailure = "interrupted";
            } catch (Exception fetchEx) {
                fetchFailure = fetchEx.getMessage();
            }
            throw new IOException("jk-lock.toml pins " + coord + ":" + e.version()
                    + " (sha256 " + e.sha256Hex() + ") but no matching jar exists in the store"
                    + (fetchFailure != null
                            ? " and the official fetch failed: " + fetchFailure
                            : " and the official repo serves different bytes")
                    + " — run `jk lock` to re-pin against this jk");
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Fork the plugin on the spec and collect its protocol lines. Throws with the
     * plugin's own error message when it reports one (or exits non-zero without reporting).
     */
    public static List<String> runWorker(Active active, Path cache, Path spec, Consumer<String> onLabel)
            throws IOException, InterruptedException {
        Path jar = workerJarFor(active, cache);
        List<String> collected = new ArrayList<>();
        // Non-protocol output (stack traces land here — stderr is merged by PluginProcess).
        // Kept so a worker that dies without reporting a protocol error is still diagnosable.
        ArrayDeque<String> tail = new ArrayDeque<>();
        String[] error = new String[1];
        PluginClient client = new PluginClient(active.manifest().code().protocolPrefix())
                .on("label", line -> {
                    if (onLabel != null) onLabel.accept(Jsonl.str(line, "text"));
                })
                .on("error", line -> error[0] = Jsonl.str(line, "message"))
                .onOther(collected::add)
                .passthrough(line -> {
                    if (tail.size() >= 20) tail.removeFirst();
                    tail.addLast(line);
                });
        int exit = client.run(
                PluginLaunch.javaCommand(jar, spec, active.manifest().code().protocolPrefix()));
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
