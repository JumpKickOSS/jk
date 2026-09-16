// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.TestEngines;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Converts a Maven {@code pom.xml} into a {@link JkBuild} plus an {@link ImportReport} of
 * unfaithful constructs. The POM is read as Maven's effective model ({@link EffectiveModel}):
 * parents flattened, {@code dependencyManagement} applied, BOM imports and properties resolved,
 * profiles active on this machine folded in. Coordinates, scoped deps (bare versions → exact pins),
 * BOM imports, repositories, the compiler level and arguments, annotation processors, source roots,
 * the test and packaging plugins and the inactive profiles' payloads are mapped; other POM features
 * land in the report, which also names what each parent contributed.
 */
public final class PomImporter {

    public record Result(JkBuild jkBuild, ImportReport report) {}

    /**
     * Outcome of importing a multi-module POM tree: the root's {@link JkBuild} (carrying the
     * workspace block), each module's {@link JkBuild} keyed by the relative module path, one
     * aggregated {@link ImportReport} spanning the root + every child, and every pom.xml of the
     * tree the import read (the root, each module's, the ones inactive profiles list included).
     */
    public record WorkspaceImportResult(
            JkBuild root, Map<String, JkBuild> modules, ImportReport report, Set<Path> pomFiles) {}

    /** Fetches a file the POM names by URL (a generator's remote spec); an {@link IOException} is the row's reason. */
    @FunctionalInterface
    public interface RemoteFile {
        byte[] fetch(URI uri) throws IOException;
    }

    private final RepoModelResolver resolver;
    private final RemoteFile remote;

    /** Parents and BOM imports are fetched through {@code repos}, plus any {@code <repository>} the POM declares. */
    public PomImporter(RepoGroup repos, Cas cas) {
        this(repos, cas, overHttp(new Http()));
    }

    /** {@code remote} answers the URLs a POM's generator plugins read their specs from. */
    public PomImporter(RepoGroup repos, Cas cas, RemoteFile remote) {
        this.resolver = new RepoModelResolver(repos, cas);
        this.remote = remote;
    }

    private static RemoteFile overHttp(Http http) {
        return uri -> {
            try {
                HttpResponse<byte[]> response = http.get(uri);
                if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode());
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }

    public Result importFrom(Path pomXml) throws IOException {
        Path file = pomXml.toAbsolutePath();
        return importModel(EffectiveModel.build(Files.readAllBytes(file), file, resolver.newCopy(), null), remote);
    }

    /** A POM with no file behind it (an archive's embedded pom.xml): no {@code relativePath} lookup. */
    public Result importFromBytes(byte[] xml) {
        return importModel(EffectiveModel.build(xml, null, resolver.newCopy(), null), remote);
    }

    private static Result importModel(EffectiveModel em, RemoteFile remote) {
        ImportReport.Builder report = ImportReport.builder();
        reportInheritanceFailure(em, report);
        GeneratorPlugins.Generators generators = GeneratorPlugins.map(em.model(), remote, report);
        SourceTreePlugins.SourceTree sourceTree = SourceTreePlugins.map(em, generators.outputRoots(), report);
        Project project = mapProject(em, report, sourceTree);
        List<Pom.Dep> processorPaths = PluginFacts.annotationProcessorPaths(em.model());
        Map<Scope, List<Dependency>> byScope = mapDependencies(em, report, processorPaths);
        mapProcessorPaths(processorPaths, byScope, report);
        ProfileMapping.Mapped profiles = ProfileMapping.map(em, report);
        addOptionalDeps(byScope, profiles.optionalDeps());
        List<Repository> repositories = new ArrayList<>(em.model().getRepositories());
        repositories.addAll(profiles.repositories());
        List<RepositorySpec> repos = mapRepositories(repositories, report);
        warnUnsupportedSections(em, report, /* isWorkspaceRoot= */ false);

        String mainClass = PluginFacts.mainClass(em.model());
        TestPlugins.TestSettings tests = TestPlugins.map(em.model(), report);
        PackagingPlugins.Packaging packaging = PackagingPlugins.map(em.model(), mainClass, report);
        JkBuild.Application application =
                mainClass != null ? new JkBuild.Application(mainClass, packaging.fatJar()) : null;
        JkBuild jkBuild = JkBuild.builder(project)
                .dependencies(new JkBuild.Dependencies(byScope))
                .repositories(repos)
                .features(profiles.features())
                .profiles(toProfiles(profiles.profiles()))
                .application(application)
                .nativeConfig(packaging.nativeConfig())
                .pluginConfig(packaging.springBoot())
                .pluginConfig(generators.openapi())
                .build(buildBlock(em.model(), sourceTree, tests))
                .build();
        Map<String, String> manifest = PluginFacts.manifestEntries(em.model());
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        return new Result(jkBuild, report.build());
    }

    /**
     * {@code [javac] args} from {@code <compilerArgs>}; {@code [build]} / {@code [test]} extra source
     * roots; {@code [test]} tag filters, JVM flags and system properties from Surefire and Failsafe.
     */
    private static JkBuild.Build buildBlock(
            Model model, SourceTreePlugins.SourceTree sourceTree, TestPlugins.TestSettings tests) {
        // A POM's direct version is the version Maven used, whatever a transitive asked for.
        JkBuild.Build build = JkBuild.Build.EMPTY.withPinPolicy(PinPolicy.NEAREST);
        List<String> args = PluginFacts.compilerArgs(model);
        if (!args.isEmpty()) build = build.withJavac(new JavacConfig(Map.of(), args));
        if (!sourceTree.extraSrc().isEmpty()) build = build.withExtraSrc(sourceTree.extraSrc());
        if (!sourceTree.testExtraSrc().isEmpty()) build = build.withTestExtraSrc(sourceTree.testExtraSrc());
        if (!tests.includeTags().isEmpty() || !tests.excludeTags().isEmpty()) {
            build = build.withTestTags(tests.includeTags(), tests.excludeTags());
        }
        if (!tests.jvm().isEmpty()) build = build.withTestJvm(tests.jvm());
        return build;
    }

    private static Profiles toProfiles(List<ProfileMapping.CompilerProfile> mapped) {
        Map<String, Profile> byName = new LinkedHashMap<>();
        for (ProfileMapping.CompilerProfile p : mapped) {
            byName.put(p.id(), new Profile(p.id(), null, p.javac(), p.jvmArgs()));
        }
        return new Profiles(byName);
    }

    /** A parent no repository has is a Tier-3 row: the POM is imported on its own declarations. */
    private static void reportInheritanceFailure(EffectiveModel em, ImportReport.Builder report) {
        if (em.failure() == null) return;
        Parent parent = em.raw().getParent();
        String subject = parent == null
                ? "the effective model could not be built"
                : "`<parent>` " + parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion()
                        + " could not be resolved";
        report.error(subject
                + " (" + em.failure() + "); nothing was inherited, and a dependency whose version the parent"
                + " managed is written as `=unresolved`.");
    }

    /**
     * Import a multi-module Maven build into a workspace-root {@code JkBuild} plus per-module
     * builds. No {@code <modules>} anywhere → single-POM import. The reactor is walked the way
     * Maven walks it ({@link ReactorModules}): aggregators recurse, active profiles contribute, and
     * sibling POMs answer parent and BOM lookups first, so the reactor never goes to the network
     * for itself.
     */
    public WorkspaceImportResult importWorkspace(Path rootPom) throws IOException {
        Path rootFile = rootPom.toAbsolutePath();
        byte[] rootXml = Files.readAllBytes(rootFile);
        Model rootRaw = EffectiveModel.rawModel(rootXml);
        if (!ReactorModules.declaresModules(rootRaw)) {
            Result single = importModel(EffectiveModel.build(rootXml, rootFile, resolver.newCopy(), null), remote);
            return new WorkspaceImportResult(single.jkBuild(), Map.of(), single.report(), Set.of(rootFile));
        }

        ImportReport.Builder report = ImportReport.builder();
        ReactorModelResolver reactor = new ReactorModelResolver(resolver);
        ReactorModules.Reactor found = ReactorModules.collect(rootFile, rootXml, rootRaw, reactor, report);
        List<ReactorModules.Leaf> leaves = found.modules();
        EffectiveModel rootModel = reactor.effective(rootFile);
        reportInheritanceFailure(rootModel, report);
        if (leaves.isEmpty() && found.boms().isEmpty()) reportInactiveModules(rootModel, report);
        SourceTreePlugins.SourceTree rootSourceTree = SourceTreePlugins.map(rootModel, List.of(), report);
        Project rootProject = mapProject(rootModel, report, rootSourceTree);
        warnUnsupportedSections(rootModel, report, /* isWorkspaceRoot= */ true);
        String rootMainClass = PluginFacts.mainClass(rootModel.model());
        JkBuild.Application rootApplication =
                rootMainClass != null ? new JkBuild.Application(rootMainClass, false) : null;

        Map<String, JkBuild> moduleBuilds = new LinkedHashMap<>();
        for (ReactorModules.Leaf leaf : leaves) {
            Result child = importModel(leaf.model(), remote);
            moduleBuilds.put(leaf.path(), child.jkBuild());
            for (ImportReport.Issue issue : child.report().issues()) {
                String prefixed = "[" + leaf.path() + "] " + issue.message();
                if (issue.severity() == ImportReport.Severity.ERROR) {
                    report.error(prefixed);
                } else {
                    report.warning(prefixed);
                }
            }
        }
        SiblingNames.report(moduleBuilds, report);
        ShadedSiblings.report(leaves, report);
        // The workspace root is a coordination point — no deps of its own — but it owns the one
        // repository list the workspace lock resolves against, so every member's `<repositories>`
        // is hoisted onto it.
        JkBuild rootJkBuild = JkBuild.builder(rootProject)
                .workspace(new Workspace(
                        leaves.stream().map(ReactorModules.Leaf::path).toList()))
                .repositories(hoistRepositories(
                        mapRepositories(rootModel.model().getRepositories(), report), moduleBuilds.values()))
                .application(rootApplication)
                .build(JkBuild.Build.EMPTY.withPinPolicy(PinPolicy.NEAREST))
                .build();
        // Rewrite inter-module Maven deps to workspace edges (and test-jar → kind=tests).
        Map<String, String> siblingByGa = siblingGaIndex(rootJkBuild, moduleBuilds.values());
        Set<String> sharedNames = SiblingNames.shared(moduleBuilds);
        Map<String, String> bomByGa = bomGaIndex(found.boms());
        Set<String> importedBoms = new HashSet<>();
        Map<String, JkBuild> rewritten = new LinkedHashMap<>();
        for (var e : moduleBuilds.entrySet()) {
            rewritten.put(
                    e.getKey(),
                    rewriteSiblingDeps(
                            e.getValue(),
                            siblingByGa,
                            sharedNames,
                            bomByGa,
                            found.unbuilt(),
                            importedBoms,
                            e.getKey(),
                            report));
        }
        for (String bom : bomByGa.values()) {
            if (importedBoms.contains(bom)) continue;
            report.warning("`" + bom + "` is a BOM (packaging `pom`, a `<dependencyManagement>` table and nothing"
                    + " else) that no module of the reactor imports; it is not a workspace module.");
        }
        return new WorkspaceImportResult(rootJkBuild, rewritten, report.build(), found.pomFiles());
    }

    /** {@code group:artifact} → root-relative path for every BOM leaf of the reactor. */
    private static Map<String, String> bomGaIndex(List<ReactorModules.Leaf> boms) {
        Map<String, String> ga = new LinkedHashMap<>();
        for (ReactorModules.Leaf bom : boms) {
            Model model = bom.model().model();
            ga.put(model.getGroupId() + ":" + model.getArtifactId(), bom.path());
        }
        return ga;
    }

    /** Modules listed only in profiles Maven would not activate here leave nothing to build: a Tier-3 row says which. */
    private static void reportInactiveModules(EffectiveModel root, ImportReport.Builder report) {
        List<String> inactive = new ArrayList<>();
        for (var profile : root.raw().getProfiles()) {
            if (!profile.getModules().isEmpty() && !root.isActive(profile)) inactive.add(profile.getId());
        }
        if (inactive.isEmpty()) return;
        report.error("`<modules>` are declared only in profiles that are not active on this machine ("
                + String.join(", ", inactive) + "); no module was imported, so the workspace builds nothing."
                + " Activate one with Maven's `-P` and re-import, or list the modules at the top level.");
    }

    /**
     * Map {@code group:artifact} → sibling {@link Project#name()} for every unit in the
     * workspace (root + members) so inter-module deps become {@code workspace = true}.
     */
    private static Map<String, String> siblingGaIndex(JkBuild root, Collection<JkBuild> modules) {
        Map<String, String> ga = new LinkedHashMap<>();
        ga.put(
                root.project().group() + ":" + root.project().name(),
                root.project().name());
        for (JkBuild m : modules) {
            ga.put(m.project().group() + ":" + m.project().name(), m.project().name());
        }
        return ga;
    }

    /**
     * Convert deps whose GA matches a workspace sibling into workspace edges; an edge to a name in
     * {@code sharedNames} carries the dependency's group so it picks one member. Maven
     * {@code <type>test-jar</type>} becomes {@code kind = "tests"} (Mill testModuleDeps). A BOM of
     * the reactor leaves {@code [platform]}: its managed versions are already on the declared
     * dependencies, and the lock fetches a BOM from a repository, which a reactor BOM is not in. A
     * dependency on a reactor POM the workspace does not build ({@code unbuilt}: an aggregator, a
     * module of an inactive profile) is dropped with a row, since no repository has it either.
     */
    private static JkBuild rewriteSiblingDeps(
            JkBuild module,
            Map<String, String> siblingByGa,
            Set<String> sharedNames,
            Map<String, String> bomByGa,
            Map<String, ReactorModules.Unbuilt> unbuilt,
            Set<String> importedBoms,
            String moduleKey,
            ImportReport.Builder report) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        boolean changed = false;
        for (Scope scope : Scope.values()) {
            List<Dependency> in = module.dependencies().of(scope);
            if (in.isEmpty()) continue;
            List<Dependency> out = new ArrayList<>(in.size());
            for (Dependency d : in) {
                String bomPath = scope == Scope.PLATFORM ? bomByGa.get(d.module()) : null;
                if (bomPath != null) {
                    changed = true;
                    importedBoms.add(bomPath);
                    report.warning("[" + moduleKey + "] `<dependencyManagement>` imports the reactor BOM `" + bomPath
                            + "` (" + d.module() + "); its managed versions are applied to the declared dependencies"
                            + " and no `[platform]` row is written, because the lock fetches a BOM from a repository"
                            + " and a reactor BOM is not published, so transitive versions follow the resolver.");
                    continue;
                }
                String siblingName = siblingByGa.get(d.module());
                if (siblingName == null) {
                    ReactorModules.Unbuilt reactorPom = unbuilt.get(d.module());
                    if (reactorPom != null) {
                        changed = true;
                        String row = "[" + moduleKey + "] " + reactorPom.row(d.module(), scope == Scope.PLATFORM);
                        if (scope == Scope.PLATFORM && reactorPom.lossless()) report.warning(row);
                        else report.error(row);
                        continue;
                    }
                    // External test-jar keeps kind=tests (lock/resolve map to g:a:test-jar:tests).
                    out.add(d);
                    if (d.isTestsKind()) changed = true;
                    continue;
                }
                changed = true;
                if (scope == Scope.PLATFORM) {
                    report.warning("[" + moduleKey + "] `<dependencyManagement>` imports the sibling BOM " + d.module()
                            + "; its managed versions are applied to the declared dependencies and no `[platform]`"
                            + " entry is written, because a workspace module is not a published BOM, so transitive"
                            + " versions follow the resolver.");
                    continue;
                }
                // Library handle matches the sibling project name so `{ workspace = true }` resolves.
                // mapDependencies already forced tests-kind deps into a test scope, so kind is
                // carried as-is — never emitted where the parser would reject it.
                Dependency ws = sharedNames.contains(siblingName)
                        ? Dependency.workspace(siblingName, d.group())
                        : Dependency.workspace(siblingName);
                ws = ws.withOptional(d.optional());
                if (d.isTestsKind()) {
                    ws = ws.withKind(DependencyKind.TESTS);
                }
                out.add(ws);
            }
            byScope.put(scope, out);
        }
        if (!changed) return module;
        JkBuild.Builder out = JkBuild.builder(module.project())
                .dependencies(new JkBuild.Dependencies(byScope))
                .repositories(module.repositories())
                .profiles(module.profiles())
                .features(module.features())
                .workspace(module.workspace())
                .manifest(module.manifest())
                .plugins(module.plugins())
                .application(module.applicationOpt().orElse(null))
                .nativeConfig(module.nativeConfigOpt().orElse(null))
                .build(module.build())
                .format(module.format())
                .variants(module.variants());
        for (var config : module.pluginConfigs().values()) {
            out.pluginConfig(config);
        }
        return out.build();
    }

    // --- project ------------------------------------------------------------

    /**
     * {@code java =} is the language level: the declared compiler level, raised to jk's floor when
     * older (with a row saying so), 25 when nothing declares one. {@code jdk =} is written only for
     * a toolchain the POM pins on purpose — a level alone never provisions a runtime.
     */
    private static Project mapProject(
            EffectiveModel em, ImportReport.Builder report, SourceTreePlugins.SourceTree sourceTree) {
        Model model = em.model();
        Parent parent = model.getParent();
        String group = model.getGroupId() != null ? model.getGroupId() : parent != null ? parent.getGroupId() : null;
        String version = model.getVersion() != null ? model.getVersion() : parent != null ? parent.getVersion() : null;
        if (group == null || group.isBlank()) {
            throw new PomParseException("POM has no <groupId> and no <parent><groupId>");
        }
        if (version == null || version.isBlank()) {
            throw new PomParseException("POM has no <version> and no <parent><version>");
        }
        if (model.getArtifactId() == null) {
            throw new PomParseException("POM missing required <artifactId>");
        }
        version = concreteVersion(version, model, report);
        int level = javaLevel(model, report);
        String description = model.getDescription();
        if (description != null && description.isBlank()) description = null;
        VersionSelector kotlin = kotlinFrom(model, report);
        // A Kotlin project sets `kotlin` and leaves `java` at 0 (mutually exclusive).
        int java = kotlin != null ? 0 : level;
        return Project.builder(group, model.getArtifactId(), version)
                .jdk(toolchainPin(model, report))
                .java(java)
                .kotlin(kotlin)
                .sourcesMode(sourceTree.sources())
                .javadocMode(sourceTree.javadoc())
                .description(description)
                .build();
    }

    /**
     * A CI-friendly version the effective model left uninterpolated names a property no POM in the
     * chain defines (Maven takes it from {@code -D}); it is filled from the model's own properties
     * where it can be, and what is left is written as {@link CiFriendlyVersions#FALLBACK} with a row
     * naming the property.
     */
    private static String concreteVersion(String version, Model model, ImportReport.Builder report) {
        if (!CiFriendlyVersions.hasPlaceholder(version)) return version;
        String filled = CiFriendlyVersions.interpolate(version, model.getProperties()::getProperty);
        List<String> missing = CiFriendlyVersions.unresolved(filled);
        if (missing.isEmpty()) return filled;
        report.warning("`<version>" + version + "</version>` references " + String.join(", ", missing)
                + ", which no POM in the chain defines (Maven takes it from `-D" + missing.getFirst()
                + "=…` on the command line); written as `version = \"" + CiFriendlyVersions.FALLBACK + "\"`.");
        return CiFriendlyVersions.FALLBACK;
    }

    private static int javaLevel(Model model, ImportReport.Builder report) {
        Optional<PluginFacts.CompilerLevel> declared = PluginFacts.compilerLevel(model);
        if (declared.isEmpty()) return 25;
        int release = declared.get().release();
        if (release >= PluginFacts.JAVA_FLOOR) return release;
        report.warning(declared.get().origin() + " declared " + release + "; jk's floor is " + PluginFacts.JAVA_FLOOR
                + "; bytecode level raised — written as `java = " + PluginFacts.JAVA_FLOOR + "`.");
        return PluginFacts.JAVA_FLOOR;
    }

    private static @Nullable String toolchainPin(Model model, ImportReport.Builder report) {
        Optional<String> pin = PluginFacts.toolchainJdk(model);
        if (pin.isEmpty()) return null;
        String spec = pin.get();
        if (Project.majorOf(spec) < PluginFacts.JAVA_FLOOR) {
            report.warning("toolchain pin `" + spec + "` is below jk's floor of " + PluginFacts.JAVA_FLOOR
                    + "; no `jdk` written — the host JDK compiles for `java = " + PluginFacts.JAVA_FLOOR + "`.");
            return null;
        }
        report.warning("`<toolchains>` pins JDK " + spec + "; written as `jdk = \"" + spec
                + "\"`, which provisions that runtime. Drop the key if the level alone is what you meant.");
        return spec;
    }

    /**
     * The Kotlin compiler version when {@code kotlin-maven-plugin} is declared anywhere in the chain;
     * a floating {@link KotlinResolver#DEFAULT_VERSION} (pinned by {@code jk lock}) when nothing
     * pins it. {@code null} for a Java project.
     */
    private static @Nullable VersionSelector kotlinFrom(Model model, ImportReport.Builder report) {
        Optional<PluginFacts.Kotlin> kotlin = PluginFacts.kotlin(model);
        if (kotlin.isEmpty()) return null;
        String version = kotlin.get().version();
        if (version == null) {
            report.warning("kotlin-maven-plugin recognised without a resolvable version; project.kotlin"
                    + " is `latest` — `jk lock` picks the current stable, then `jk update` moves it.");
            return VersionSelector.parse("latest");
        }
        return VersionSelector.parse(version);
    }

    // --- dependencies -------------------------------------------------------

    /** {@code processorPaths} count as users of a managed version too, so their pins are not reported unused. */
    private static Map<Scope, List<Dependency>> mapDependencies(
            EffectiveModel em, ImportReport.Builder report, List<Pom.Dep> processorPaths) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        Set<String> used = new HashSet<>();
        for (Pom.Dep path : processorPaths) used.add(path.module() + ":jar");
        Map<String, List<String>> managedBy = new LinkedHashMap<>();
        Map<String, List<String>> inheritedFrom = new LinkedHashMap<>();
        List<Pom.Dep> plainProcessors = new ArrayList<>();
        for (EffectiveModel.Declared declared : em.dependencies(report)) {
            used.add(declared.key());
            if (processorPaths.isEmpty() && KnownProcessors.recognizes(declared.dep())) {
                plainProcessors.add(declared.dep());
            }
            if (!declared.own()) {
                inheritedFrom
                        .computeIfAbsent(declared.source(), k -> new ArrayList<>())
                        .add(declared.dep().module());
            } else if (declared.versionManaged() && !declared.source().startsWith("this POM")) {
                managedBy
                        .computeIfAbsent(declared.source(), k -> new ArrayList<>())
                        .add(declared.dep().module());
            }
            mapDependency(declared.dep(), byScope, report);
        }
        mapManagement(em.management(used), byScope, report);
        managedBy.forEach((source, modules) ->
                report.warning("versions for " + String.join(", ", modules) + " managed by " + source + "."));
        inheritedFrom.forEach((source, modules) ->
                report.warning("dependencies " + String.join(", ", modules) + " inherited from " + source + "."));
        KnownProcessors.write(plainProcessors, byScope, report);
        uniquifyHandles(byScope, report);
        return byScope;
    }

    private static void mapDependency(Pom.Dep dep, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        if ("system".equalsIgnoreCase(dep.scope())) {
            report.error("`<dependency>` with `<scope>system</scope>` is rejected ("
                    + dep.module()
                    + "). Move it to a git dependency or a local repository.");
            return;
        }
        String unmappedType = DependencyMapping.unmappedType(dep);
        if (unmappedType != null) {
            report.error("`<type>" + unmappedType + "</type>` on " + dep.module()
                    + " names an artifact jk has no manifest spelling for; the dependency was not written."
                    + " A jar of the same module is `{ group, name, version }`, a classified jar adds"
                    + " `classifier`.");
            return;
        }
        if (dep.optional()) {
            report.warning("`<dependency><optional>true</optional></dependency>` on "
                    + dep.module()
                    + " — jk has no `<optional>`; emitted as a normal dep."
                    + " Use a feature flag if it should be opt-in.");
        }
        if (!dep.exclusions().isEmpty()) {
            report.warning("`<exclusions>` on "
                    + dep.module()
                    + " — exclusion support lands in a later slice; exclusions were dropped.");
        }
        DependencyMapping.warnUnresolvedVersion(dep, report);
        Scope scope = DependencyMapping.scope(dep.scope());
        if (DependencyMapping.isPom(dep)) {
            report.warning("`<type>pom</type>` on " + dep.module()
                    + " is written to [platform-dependencies]: its dependencyManagement governs versions, and"
                    + " the libraries it lists are not on the classpath — declare the ones the code uses.");
            scope = Scope.PLATFORM;
        }
        Dependency d = raiseToEngineFloor(DependencyMapping.toDependency(dep), scope, report);
        // kind=tests is only legal under [test-dependencies]/[test-dev-dependencies]
        // (JkBuildParser.applyDependencyKind), so a test-jar dep declared in another Maven
        // scope moves to TEST — otherwise the emitted jk.toml rejects its own `jk lock`.
        if (d.isTestsKind() && scope != Scope.TEST && scope != Scope.TEST_DEV) {
            report.warning("`<type>test-jar</type>` on "
                    + dep.module()
                    + " is in Maven scope `"
                    + (dep.scope() == null || dep.scope().isBlank() ? "compile" : dep.scope())
                    + "`; jk models test-jar deps as kind=tests, which is only legal in test"
                    + " scopes — moved to [test-dependencies].");
            scope = Scope.TEST;
        }
        byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(d);
    }

    /**
     * {@code <annotationProcessorPaths>} → {@code [processor-dependencies]}. With that element
     * present Maven hands javac a processor path and stops discovering processors on the classpath,
     * which is exactly what the jk table does, so only the listed paths are written. Without it,
     * the processors {@link KnownProcessors} recognizes among the plain dependencies are written.
     */
    private static void mapProcessorPaths(
            List<Pom.Dep> paths, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        for (Pom.Dep path : paths) {
            if (PluginFacts.usable(path.version()) == null) {
                report.warning("`<annotationProcessorPaths>` entry " + path.module()
                        + " has no version in the POM or its dependencyManagement; jk wrote `=unresolved`.");
            }
            byScope.computeIfAbsent(Scope.PROCESSOR, s -> new ArrayList<>()).add(DependencyMapping.toDependency(path));
        }
    }

    /**
     * The optional deps inactive profiles contribute, after the POM's own handles are settled: a
     * handle already taken in the scope gets a numeric suffix, the same rule as
     * {@link #uniquifyHandles}, so the feature's list still names what was written.
     */
    private static void addOptionalDeps(Map<Scope, List<Dependency>> byScope, Map<Scope, List<Dependency>> optional) {
        for (Map.Entry<Scope, List<Dependency>> e : optional.entrySet()) {
            List<Dependency> deps = byScope.computeIfAbsent(e.getKey(), s -> new ArrayList<>());
            Set<String> seen = new HashSet<>();
            for (Dependency d : deps) seen.add(d.library());
            for (Dependency d : e.getValue()) {
                String handle = d.library();
                for (int n = 2; !seen.add(handle); n++) handle = d.library() + "-" + n;
                deps.add(
                        handle.equals(d.library())
                                ? d
                                : Dependency.of(handle, d.module(), d.version())
                                        .withKind(d.kind())
                                        .withClassifier(d.classifier())
                                        .withOptional(true));
            }
        }
    }

    /**
     * A BOM import whose version is still a property is a Tier-3 row naming that property and no
     * {@code [platform-dependencies]} row: a lock cannot ask a repository for a BOM at the version
     * {@code unresolved}, and the effective model left the property that way because the chain
     * that defines it could not be built.
     */
    private static void reportUnresolvedBom(Pom.Dep bom, ImportReport.Builder report) {
        String version = bom.version() == null ? "" : bom.version();
        List<String> properties = CiFriendlyVersions.unresolved(version);
        String named = properties.isEmpty()
                ? "no version"
                : "version `" + version + "`, and " + String.join(", ", properties)
                        + (properties.size() == 1 ? " has" : " have") + " no value in the effective model";
        report.error("`<scope>import</scope>` BOM " + bom.module() + " has " + named
                + "; no `[platform-dependencies]` row is written for it. Make the parent chain resolvable"
                + " or pin the version in the POM and re-import.");
    }

    /**
     * BOM imports become {@code [platform]} entries with their versions resolved; a published parent
     * whose chain manages versions is carried as one {@code [platform]} entry of its own, so the
     * inherited table governs transitive versions too. Bare pins nothing declared uses are named.
     */
    private static void mapManagement(
            EffectiveModel.Management mgmt, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        for (Pom.Dep bom : mgmt.platform()) {
            if (PluginFacts.usable(bom.version()) == null) {
                reportUnresolvedBom(bom, report);
                continue;
            }
            byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>()).add(DependencyMapping.toDependency(bom));
        }
        EffectiveModel.Ancestor parent = mgmt.parentPlatform();
        if (parent != null) {
            String module = parent.groupId() + ":" + parent.artifactId();
            byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>())
                    .add(Dependency.of(parent.artifactId(), module, VersionSelector.parse(parent.version())));
            report.warning("`<dependencyManagement>` inherited from "
                    + parent.label()
                    + " is carried as `[platform]` "
                    + parent.gav()
                    + ", so its managed versions govern transitive dependencies as well.");
        }
        mgmt.unusedPins().forEach((owner, modules) -> {
            String sample =
                    modules.size() > 5 ? String.join(", ", modules.subList(0, 5)) + ", …" : String.join(", ", modules);
            report.warning("`<dependencyManagement>` in "
                    + owner
                    + " pins "
                    + modules.size()
                    + " version"
                    + (modules.size() == 1 ? "" : "s")
                    + " no declared dependency uses ("
                    + sample
                    + "); jk applies managed versions to declared dependencies only, so transitive"
                    + " versions follow the resolver.");
        });
    }

    /**
     * The manifest key (dep {@code library} handle) must be unique per scope section — the renderer
     * keys each section on it, so a collision silently drops an edge. Maven allows same-artifactId
     * deps in one scope (different groups); disambiguate deterministically in declaration order.
     */
    private static void uniquifyHandles(Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        for (Map.Entry<Scope, List<Dependency>> e : byScope.entrySet()) {
            List<Dependency> deps = e.getValue();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < deps.size(); i++) {
                Dependency d = deps.get(i);
                if (seen.add(d.library())) continue;
                int n = 2;
                String candidate;
                do {
                    candidate = d.library() + "-" + n++;
                } while (!seen.add(candidate));
                report.warning("dependency handle `"
                        + d.library()
                        + "` collides in ["
                        + e.getKey().tomlSection()
                        + "]; "
                        + d.module()
                        + " was written as `"
                        + candidate
                        + "`.");
                deps.set(
                        i,
                        Dependency.of(candidate, d.module(), d.version())
                                .withKind(d.kind())
                                .withClassifier(d.classifier()));
            }
        }
    }

    /**
     * A test framework pinned below the floor of the Platform engine that runs it under jk is
     * written at the version the engine accepts: JUnit 3.8.2 becomes JUnit 4.13.2, whose jar still
     * runs {@code TestCase} suites. The lock would refuse the pin as written; the note says why.
     */
    private static Dependency raiseToEngineFloor(Dependency d, Scope scope, ImportReport.Builder report) {
        if (scope != Scope.TEST && scope != Scope.TEST_DEV) return d;
        if (!(d.version() instanceof VersionSelector.Exact exact)) return d;
        for (TestEngines.Row row : TestEngines.ROWS) {
            if (!row.trigger().equals(d.module()) || Versions.compare(exact.version(), row.floor()) >= 0) continue;
            report.warning(d.module()
                    + " "
                    + exact.version()
                    + " raised to "
                    + row.suggested()
                    + ": jk runs its suites through "
                    + row.engine().module()
                    + ", which needs "
                    + row.floor()
                    + " or later.");
            return Dependency.of(d.library(), d.module(), VersionSelector.parse(row.suggested()))
                    .withKind(d.kind())
                    .withClassifier(d.classifier());
        }
        return d;
    }

    // --- repositories -------------------------------------------------------

    /**
     * Every repository the effective model declares or inherits, with its {@code <releases>} /
     * {@code <snapshots>} policy; Central is the implicit default.
     */
    private static List<RepositorySpec> mapRepositories(List<Repository> repositories, ImportReport.Builder report) {
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Repository repo : repositories) {
            String id = repo.getId();
            String url = repo.getUrl();
            if (url == null || url.isBlank()) {
                report.warning("`<repository>` with no `<url>` was skipped (id=" + id + ").");
                continue;
            }
            String name = (id == null || id.isBlank()) ? "repo" + (deduped.size() + 1) : id;
            if (name.equals("central") || RepoModelResolver.isCentral(url)) continue;
            boolean releases = repo.getReleases() == null || repo.getReleases().isEnabled();
            boolean snapshots =
                    repo.getSnapshots() == null || repo.getSnapshots().isEnabled();
            if (!releases && !snapshots) continue;
            try {
                deduped.put(name, new RepositorySpec(name, new URI(url.trim())).withPolicy(releases, snapshots));
            } catch (URISyntaxException e) {
                report.warning("`<repository><url>" + url + "</url></repository>` is not a valid URI; skipped.");
            }
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * The root's repositories followed by every member's, one entry per name, first declaration
     * wins — the list the workspace lock resolves every member against.
     */
    static List<RepositorySpec> hoistRepositories(List<RepositorySpec> root, Collection<JkBuild> members) {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        for (RepositorySpec spec : root) byName.putIfAbsent(spec.name(), spec);
        for (JkBuild member : members) {
            for (RepositorySpec spec : member.repositories()) byName.putIfAbsent(spec.name(), spec);
        }
        return List.copyOf(byName.values());
    }

    // --- unsupported-section warnings ---------------------------------------

    private static void warnUnsupportedSections(
            EffectiveModel em, ImportReport.Builder report, boolean isWorkspaceRoot) {
        Model model = em.model();
        if (!isWorkspaceRoot && !model.getModules().isEmpty()) {
            // The workspace-import path already converted these; warn only for the single-POM path.
            report.warning("`<modules>` block present but this import was run in single-POM mode."
                    + " Re-run as `jk import pom.xml` from the project root to materialise a workspace.");
        }
        BuildExtensions.report(model, report);
    }
}
