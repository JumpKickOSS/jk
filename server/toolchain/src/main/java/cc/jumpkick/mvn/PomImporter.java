// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ImportedKotlin;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.InFlightRequests;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PinPolicy;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.StallWatch;
import cc.jumpkick.resolver.TestEngines;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

    /** How a refusal for a POM read that stopped advancing opens. */
    public static final String BUDGET_EXCEEDED = "Resolution budget exceeded: ";

    final RepoModelResolver resolver;
    private final RemoteFile remote;

    /** How long the POM reads may stand still before the import stops; {@code JK_RESOLVE_TIMEOUT_MS}. */
    private long stallWindowMs = StallWatch.envWindowMs();

    /** The profile ids activated by name ({@code jk import -P}), beside what each POM activates itself. */
    private List<String> activeProfiles = List.of();

    /**
     * Maven's {@code settings.xml}: the repositories of its active profiles join every imported
     * manifest's {@code [repositories]}, the way Maven consults them beside a POM's own.
     */
    private final MavenSettings settings;

    /** Parents and BOM imports are fetched through {@code repos}, plus any {@code <repository>} the POM declares. */
    public PomImporter(RepoGroup repos, Cas cas) {
        this(repos, cas, overHttp(Http.forRepositories()));
    }

    /** {@code remote} answers the URLs a POM's generator plugins read their specs from. */
    public PomImporter(RepoGroup repos, Cas cas, RemoteFile remote) {
        this(repos, cas, remote, MavenSettings.current());
    }

    /** As above over the given Maven settings rather than this machine's — tests. */
    public PomImporter(RepoGroup repos, Cas cas, RemoteFile remote, MavenSettings settings) {
        this.resolver = new RepoModelResolver(repos, cas);
        this.remote = remote;
        this.settings = settings;
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

    /** This importer with another stall window for its POM reads, in milliseconds ({@code 0} never stops) — tests. */
    PomImporter stallWindowMs(long windowMs) {
        this.stallWindowMs = windowMs;
        return this;
    }

    /**
     * This importer activating {@code ids} in every POM it reads, as Maven's {@code -P} does: a
     * profile's modules, dependencies and plugins join the effective model whether or not the POM
     * would activate it here.
     */
    public PomImporter activeProfiles(List<String> ids) {
        this.activeProfiles = List.copyOf(ids);
        return this;
    }

    public Result importFrom(Path pomXml) throws IOException {
        Path file = pomXml.toAbsolutePath();
        byte[] xml = Files.readAllBytes(file);
        return watched(() -> importModel(
                        EffectiveModel.build(xml, file, resolver.newCopy(), null, activeProfiles),
                        remote,
                        settings,
                        null,
                        null)
                .platformManaged());
    }

    /** A POM with no file behind it (an archive's embedded pom.xml): no {@code relativePath} lookup. */
    public Result importFromBytes(byte[] xml) {
        try {
            return watched(() -> importModel(
                            EffectiveModel.build(xml, null, resolver.newCopy(), null, activeProfiles),
                            remote,
                            settings,
                            null,
                            null)
                    .platformManaged());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One import pass over the POMs; the reads inside may park on a repository. */
    @FunctionalInterface
    private interface Read<T> {
        T run() throws IOException;
    }

    /**
     * Run {@code read} under the stall watch: the parent, BOM and POM reads are the progress, and an
     * import whose reads stop advancing for the window fails with {@link #BUDGET_EXCEEDED} followed
     * by the coordinate being read and the URL waited on, as a lock's resolve does. The window
     * opens with the first read, since nothing before it can park on a repository; an import that
     * reads no POM is never stopped.
     */
    private <T> T watched(Read<T> read) throws IOException {
        StallWatch watch = new StallWatch(
                Clock.SYSTEM,
                stallWindowMs,
                "no POM read",
                resolver::readsCompleted,
                resolver::phase,
                InFlightRequests::waitingOn);
        Thread importing = Thread.currentThread();
        resolver.onFirstRead(() -> watch.start(importing));
        try {
            T out = read.run();
            if (watch.tripped()) throw new IOException(BUDGET_EXCEEDED + watch.stall());
            return out;
        } catch (RepoModelResolver.ReadInterrupted e) {
            if (watch.tripped()) throw new IOException(BUDGET_EXCEEDED + watch.stall(), e);
            throw new IOException(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            if (watch.tripped()) throw new IOException(BUDGET_EXCEEDED + watch.stall(), e);
            throw e;
        } finally {
            resolver.onFirstRead(null);
            watch.stop();
        }
    }

    /**
     * One POM imported, before the workspace pass decides which of its platform-supplied versions
     * are written: {@code platformSupplied} names the modules declared without a version that a
     * {@code [platform-dependencies]} entry of the manifest supplies, and {@code bomSupplied} those
     * of them a BOM import supplied rather than an ancestor's own {@code dependencyManagement} entry
     * — the ones a BOM of the reactor may turn out to own ({@link ReactorManaged}).
     */
    private record Imported(
            JkBuild jkBuild, ImportReport report, Set<String> platformSupplied, Set<String> bomSupplied) {

        /** The result with every platform-supplied dependency written without a version. */
        Result platformManaged() {
            return platformManaged(platformSupplied);
        }

        /** The result with each dependency on a module of {@code supplied} written without a version. */
        Result platformManaged(Set<String> supplied) {
            return new Result(withPlatformManaged(jkBuild, supplied), report);
        }
    }

    /**
     * {@code build} with each dependency on a module of {@code platformSupplied} left to the
     * platform: the {@code [platform-dependencies]} entry that supplied the version under Maven
     * keeps owning it under jk, as {@code spring-boot-starter-web = "managed"}.
     */
    static JkBuild withPlatformManaged(JkBuild build, Set<String> platformSupplied) {
        if (platformSupplied.isEmpty()) return build;
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        for (Map.Entry<Scope, List<Dependency>> e :
                build.dependencies().byScope().entrySet()) {
            List<Dependency> deps = new ArrayList<>(e.getValue().size());
            for (Dependency d : e.getValue()) {
                boolean supplied = e.getKey() != Scope.PLATFORM
                        && e.getKey() != Scope.MANAGED
                        && platformSupplied.contains(d.module())
                        && d.version() instanceof VersionSelector.Exact;
                deps.add(supplied ? d.asPlatformManaged() : d);
            }
            byScope.put(e.getKey(), deps);
        }
        return build.withDependencies(new JkBuild.Dependencies(byScope));
    }

    /**
     * {@code inherited} collects the rows a workspace module inherits and {@code hoisted} the managed
     * pins its reactor parents own; both {@code null} for a POM imported on its own.
     */
    private Imported importModel(
            EffectiveModel em,
            RemoteFile remote,
            MavenSettings settings,
            @Nullable InheritedRows inherited,
            @Nullable List<Dependency> hoisted) {
        ImportReport.Builder report = ImportReport.builder();
        reportInheritanceFailure(em, report);
        GeneratorPlugins.Generators generators = GeneratorPlugins.map(em.model(), remote, report);
        Set<String> consumedPlugins = new HashSet<>(generators.consumedPlugins());
        consumedPlugins.addAll(BuildExtensions.extensionPlugins(em.model()));
        SourceTreePlugins.SourceTree sourceTree =
                SourceTreePlugins.map(em, generators.outputRoots(), consumedPlugins, report, inherited, false);
        Project project = mapProject(em, report, sourceTree);
        PluginFacts.ProcessorPaths processorPaths = PluginFacts.annotationProcessorPaths(em.model());
        Set<String> platformSupplied = new HashSet<>();
        Set<String> bomSupplied = new HashSet<>();
        Map<Scope, List<Dependency>> byScope =
                mapDependencies(em, report, processorPaths.all(), hoisted, platformSupplied, bomSupplied);
        mapProcessorPaths(processorPaths, byScope, report);
        ProfileMapping.Mapped profiles = ProfileMapping.map(em, report, profileBoms(resolver, report));
        addOptionalDeps(byScope, profiles.optionalDeps());
        List<Repository> repositories = new ArrayList<>(em.model().getRepositories());
        repositories.addAll(profiles.repositories());
        List<RepositorySpec> repos = withSettingsRepositories(mapRepositories(repositories, report), settings);
        warnUnsupportedSections(em, report, /* isWorkspaceRoot= */ false, inherited);

        String mainClass = PluginFacts.mainClass(em.model());
        TestPlugins.TestSettings tests = TestPlugins.map(em.model(), report);
        MigrationPlugins.report(em.model(), report);
        PackagingPlugins.Packaging packaging = PackagingPlugins.map(em, mainClass, report);
        // The fat jar is the application's when the module has a main; a shaded library — neo4j's
        // lucene9-shaded, nacos's client — ships it under [library], relocations included.
        JkBuild.Application application = mainClass != null
                ? new JkBuild.Application(mainClass, packaging.fatJar(), false, false, null, packaging.relocate())
                : null;
        JkBuild.Library library =
                mainClass == null && packaging.fatJar() ? new JkBuild.Library(true, packaging.relocate()) : null;
        JkBuild.Builder builder = JkBuild.builder(project)
                .dependencies(new JkBuild.Dependencies(byScope))
                .repositories(repos)
                .features(profiles.features())
                .profiles(toProfiles(profiles.profiles()))
                .application(application)
                .library(library)
                .nativeConfig(packaging.nativeConfig())
                .image(packaging.image())
                .pluginConfig(packaging.springBoot())
                .pluginConfig(packaging.quarkus())
                .pluginConfig(LintPlugins.map(em, report, inherited, resolver.repos()));
        for (PluginConfig table : generators.tables()) builder.pluginConfig(table);
        JkBuild jkBuild = builder.build(buildBlock(em.model(), sourceTree, tests, report)
                        .withBuildInfo(BuildInfoPlugins.map(em, report).orElse(null))
                        .withDokka(BuildInfoPlugins.mapDokka(em, report).orElse(BuildBlock.Dokka.DEFAULT)))
                .build();
        Map<String, String> manifest = PluginFacts.manifestEntries(em.model());
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        return new Imported(jkBuild, report.build(), platformSupplied, bomSupplied);
    }

    /**
     * {@code [javac] args} from {@code <compilerArgs>}, with a {@code [javac.test]} table when a
     * compiler execution reached one compile step alone; {@code [build]} / {@code [test]} extra
     * source roots; {@code [test]} tag filters, JVM flags and system properties from Surefire and
     * Failsafe.
     */
    private static BuildBlock buildBlock(
            Model model,
            SourceTreePlugins.SourceTree sourceTree,
            TestPlugins.TestSettings tests,
            ImportReport.Builder report) {
        // A POM's direct version is the version Maven used, whatever a transitive asked for.
        BuildBlock build = BuildBlock.EMPTY.withPinPolicy(PinPolicy.NEAREST);
        PluginFacts.CompilerArgs args = PluginFacts.compilerArgs(model);
        if (args.split()) {
            build = build.withJavac(new JavacConfig(Map.of(), args.main(), new JavacConfig(Map.of(), args.test())));
            report.warning("`maven-compiler-plugin` execution " + String.join(", ", args.scoped())
                    + " carries `<compilerArgs>` for one compile step, so `[javac] args` is what compile-main"
                    + " runs and `[javac.test] args` what compile-test runs, as under Maven.");
        } else if (!args.main().isEmpty()) {
            build = build.withJavac(new JavacConfig(Map.of(), args.main()));
        }
        if (!args.granted().isEmpty()) {
            report.warning("`<compilerArgs>` carries " + args.granted().size() + " `-J` line"
                    + (args.granted().size() == 1 ? "" : "s") + " opening `jdk.compiler` packages jk grants every"
                    + " compiler worker (" + String.join(", ", packagesOf(args.granted()))
                    + "); they add nothing to a jk build and are not written.");
        }
        for (String execution : PluginFacts.mainScopedProcessorPaths(model)) {
            report.warning("`<annotationProcessorPaths>` on execution " + execution
                    + " — jk's [processor-dependencies] serves compile-test as well as compile-main; the paths"
                    + " are written there, so both compiles run them.");
        }
        if (!sourceTree.extraSrc().isEmpty()) build = build.withExtraSrc(sourceTree.extraSrc());
        if (!sourceTree.testExtraSrc().isEmpty()) build = build.withTestExtraSrc(sourceTree.testExtraSrc());
        if (!tests.includeTags().isEmpty() || !tests.excludeTags().isEmpty()) {
            build = build.withTestTags(tests.includeTags(), tests.excludeTags());
        }
        if (!tests.jvm().isEmpty()) build = build.withTestJvm(tests.jvm());
        return build;
    }

    /** {@code com.sun.tools.javac.api} out of {@code -J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED}. */
    private static List<String> packagesOf(List<String> granted) {
        List<String> out = new ArrayList<>();
        for (String line : granted) {
            int slash = line.indexOf('/');
            int eq = line.indexOf('=', slash);
            out.add(slash < 0 || eq < 0 ? line : line.substring(slash + 1, eq));
        }
        return out;
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
        return watched(() -> importReactor(rootPom));
    }

    private WorkspaceImportResult importReactor(Path rootPom) throws IOException {
        Path rootFile = rootPom.toAbsolutePath();
        byte[] rootXml = Files.readAllBytes(rootFile);
        Model rootRaw = EffectiveModel.rawModel(rootXml);
        if (!ReactorModules.declaresModules(rootRaw)) {
            Result single = importModel(
                            EffectiveModel.build(rootXml, rootFile, resolver.newCopy(), null, activeProfiles),
                            remote,
                            settings,
                            null,
                            null)
                    .platformManaged();
            return new WorkspaceImportResult(single.jkBuild(), Map.of(), single.report(), Set.of(rootFile));
        }

        ImportReport.Builder report = ImportReport.builder();
        reportUnknownProfiles(rootRaw, report);
        ReactorModelResolver reactor = new ReactorModelResolver(resolver, activeProfiles);
        // Each module is imported as the walk reaches it and its effective model is dropped right
        // after: what stays of a module is its JkBuild and its rows.
        Map<String, Imported> imported = new LinkedHashMap<>();
        ModuleRows moduleRows = new ModuleRows();
        InheritedRows inherited = new InheritedRows(Objects.requireNonNull(rootFile.getParent()));
        // The managed pins a reactor parent owns are written once, on the root, whose table every
        // member's lock reads.
        List<Dependency> hoistedManaged = new ArrayList<>();
        ReactorManaged reactorManaged = new ReactorManaged();
        ReactorModules.Reactor found =
                ReactorModules.collect(rootFile, rootXml, rootRaw, reactor, report, (leaf, model) -> {
                    Imported child = importModel(model, remote, settings, inherited, hoistedManaged);
                    imported.put(leaf.path(), child);
                    moduleRows.addAll(leaf.path(), child.report());
                    if ("pom".equals(model.model().getPackaging())) reactorManaged.add(leaf.ga(), model);
                });
        List<ReactorModules.Leaf> leaves = found.modules();
        EffectiveModel rootModel = reactor.effective(rootFile);
        reactorManaged.add(
                rootModel.model().getGroupId() + ":" + rootModel.model().getArtifactId(), rootModel);
        for (ReactorModules.Leaf bom : found.boms()) reactorManaged.add(bom.ga(), reactor.effective(bom.pomFile()));
        reportInheritanceFailure(rootModel, report);
        if (leaves.isEmpty() && found.boms().isEmpty()) reportInactiveModules(rootModel, report);
        SourceTreePlugins.SourceTree rootSourceTree = SourceTreePlugins.map(
                rootModel, Map.of(), BuildExtensions.extensionPlugins(rootModel.model()), report, inherited, true);
        Project rootProject = mapProject(rootModel, report, rootSourceTree);
        warnUnsupportedSections(rootModel, report, /* isWorkspaceRoot= */ true, inherited);
        inherited.flush(report);
        String rootMainClass = PluginFacts.mainClass(rootModel.model());
        JkBuild.Application rootApplication =
                rootMainClass != null ? new JkBuild.Application(rootMainClass, false) : null;

        Map<String, JkBuild> members = SelfHostedFrameworks.strip(
                memberBuilds(imported, reactorManaged, found.unbuilt()), found.boms(), rootProject.version(), report);
        SiblingNames.report(members, report);
        // The workspace root is a coordination point — no deps of its own — but it owns the one
        // repository list the workspace lock resolves against, so every member's `<repositories>`
        // is hoisted onto it.
        JkBuild rootJkBuild = JkBuild.builder(rootProject)
                .workspace(new Workspace(
                        leaves.stream().map(ReactorModules.Leaf::path).toList()))
                .repositories(hoistRepositories(
                        withSettingsRepositories(
                                mapRepositories(rootModel.model().getRepositories(), report), settings),
                        members.values()))
                .application(rootApplication)
                .build(BuildBlock.EMPTY.withPinPolicy(PinPolicy.NEAREST))
                .build();
        // Rewrite inter-module Maven deps to workspace edges (and test-jar → kind=tests).
        Map<String, String> siblingByGa = SiblingEdges.siblingGaIndex(rootJkBuild, members.values());
        Set<String> sharedNames = SiblingNames.shared(members);
        Map<String, String> bomByGa = bomGaIndex(found.boms());
        // A reactor parent's managed pin on a reactor POM is the workspace's to supply.
        hoistedManaged.removeIf(d -> SiblingEdges.namesReactorPom(d.module(), siblingByGa, found.unbuilt(), bomByGa));
        if (!hoistedManaged.isEmpty()) {
            Map<Scope, List<Dependency>> rootDeps = new EnumMap<>(Scope.class);
            rootDeps.put(Scope.MANAGED, hoistedManaged);
            rootJkBuild = rootJkBuild.withDependencies(new JkBuild.Dependencies(rootDeps));
            ManagedRows.reportHoisted(hoistedManaged, report);
        }
        Set<String> importedBoms = new HashSet<>();
        Map<String, JkBuild> rewritten = new LinkedHashMap<>();
        for (var e : members.entrySet()) {
            rewritten.put(
                    e.getKey(),
                    SiblingEdges.rewrite(
                            e.getValue(),
                            siblingByGa,
                            sharedNames,
                            bomByGa,
                            found.unbuilt(),
                            importedBoms,
                            e.getKey(),
                            moduleRows));
        }
        moduleRows.flush(report);
        for (String bom : bomByGa.values()) {
            if (importedBoms.contains(bom)) continue;
            report.warning("`" + bom + "` is a BOM (packaging `pom`, a `<dependencyManagement>` table and nothing"
                    + " else) that no module of the reactor imports; it is not a workspace module.");
        }
        return new WorkspaceImportResult(rootJkBuild, rewritten, report.build(), found.pomFiles());
    }

    /**
     * Each member's manifest with its platform-supplied versions written as the platform's, less
     * what a BOM of the reactor itself supplied: that BOM is no {@code [platform]} row under jk
     * ({@link SiblingEdges}), so nothing would supply the versions it manages, and the member keeps
     * those as the effective POM resolved them ({@link ReactorManaged}).
     */
    private static Map<String, JkBuild> memberBuilds(
            Map<String, Imported> imported,
            ReactorManaged reactorManaged,
            Map<String, ReactorModules.Unbuilt> unbuilt) {
        Map<String, JkBuild> builds = new LinkedHashMap<>();
        for (Map.Entry<String, Imported> e : imported.entrySet()) {
            Imported member = e.getValue();
            Set<String> supplied = new HashSet<>(member.platformSupplied());
            supplied.removeAll(member.bomSupplied());
            supplied.addAll(reactorManaged.keep(member.jkBuild(), member.bomSupplied(), unbuilt));
            builds.put(e.getKey(), member.platformManaged(supplied).jkBuild());
        }
        return builds;
    }

    /** {@code group:artifact} → root-relative path for every BOM leaf of the reactor. */
    private static Map<String, String> bomGaIndex(List<ReactorModules.Leaf> boms) {
        Map<String, String> ga = new LinkedHashMap<>();
        for (ReactorModules.Leaf bom : boms) ga.put(bom.ga(), bom.path());
        return ga;
    }

    /**
     * Modules listed only in profiles Maven would not activate here leave nothing to build: a Tier-3
     * row names the profiles and the {@code -P} that activates one, as Maven's does.
     */
    private static void reportInactiveModules(EffectiveModel root, ImportReport.Builder report) {
        List<String> inactive = new ArrayList<>();
        for (var profile : root.raw().getProfiles()) {
            if (!profile.getModules().isEmpty() && !root.isActive(profile)) inactive.add(profile.getId());
        }
        if (inactive.isEmpty()) return;
        report.error("`<modules>` are declared only in profiles that are not active on this machine ("
                + String.join(", ", inactive) + "); no module was imported, so the workspace builds nothing."
                + " Activate one as Maven's `-P` does — `jk import pom.xml -P " + inactive.getFirst()
                + "` — or list the modules at the top level.");
    }

    /** A {@code -P} id no {@code <profile>} of the root POM declares is a row; a member's own profile still activates there. */
    private void reportUnknownProfiles(Model rootRaw, ImportReport.Builder report) {
        for (String id : activeProfiles) {
            boolean declared = rootRaw.getProfiles().stream().anyMatch(p -> id.equals(p.getId()));
            if (!declared) {
                report.warning("`-P " + id + "` names a profile the root POM does not declare; a member declaring"
                        + " it activates it there, and the root's module list is as its own profiles leave it.");
            }
        }
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
        // A Kotlin module leaves `java` at 0; one whose main tree also carries Java sources is
        // mixed and declares both, so javac compiles those against kotlinc's output.
        int java = kotlin == null || javaMainBesideKotlin(model, kotlin, report) ? level : 0;
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
        report.warning(
                declared.get().origin() + " declared " + release + "; jk's floor is " + PluginFacts.JAVA_FLOOR
                        + "; bytecode level raised — written as `java = " + PluginFacts.JAVA_FLOOR
                        + "`. A module whose sources or dependencies need an API a later JDK removed keeps `java = "
                        + release
                        + "` instead: the toolchain JDK cross-compiles for it, and a compile that misses such an API names it.");
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
        return ImportedKotlin.floored(VersionSelector.parse(version), report);
    }

    /**
     * Whether a module with the Kotlin plugin has Java main sources too ({@link
     * PluginFacts#javaMainSources}); a row says the module is mixed and what each key compiles.
     */
    private static boolean javaMainBesideKotlin(Model model, VersionSelector kotlin, ImportReport.Builder report) {
        if (!PluginFacts.javaMainSources(model)) return false;
        report.warning("`kotlin-maven-plugin` beside Java sources under `" + PluginFacts.mainSourceDirectory(model)
                + "`: the module is mixed — `java` is javac's level for those and `kotlin = \"" + kotlin.raw()
                + "\"` kotlinc's for the Kotlin sources of main and test; kotlinc runs first and javac compiles"
                + " against its output.");
        return true;
    }

    // --- dependencies -------------------------------------------------------

    /**
     * {@code processorPaths} count as users of a managed version too, so their pins are not reported
     * unused; {@code hoisted} collects the reactor parent's inline pins for the workspace root, and is
     * {@code null} for a POM imported on its own. {@code platformSupplied} collects the modules
     * declared without a version that a written {@code [platform-dependencies]} entry supplies, and
     * {@code bomSupplied} those of them a BOM import supplied rather than an ancestor's own entry.
     */
    private static Map<Scope, List<Dependency>> mapDependencies(
            EffectiveModel em,
            ImportReport.Builder report,
            List<Pom.Dep> processorPaths,
            @Nullable List<Dependency> hoisted,
            Set<String> platformSupplied,
            Set<String> bomSupplied) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        Set<String> used = new HashSet<>();
        for (Pom.Dep path : processorPaths) used.add(path.module() + ":jar");
        List<EffectiveModel.Declared> declarations = em.dependencies(report);
        for (EffectiveModel.Declared declared : declarations) used.add(declared.key());
        EffectiveModel.Management mgmt = em.management(used);
        Map<String, List<String>> managedBy = new LinkedHashMap<>();
        Map<String, List<String>> inheritedFrom = new LinkedHashMap<>();
        List<Pom.Dep> plainProcessors = new ArrayList<>();
        for (EffectiveModel.Declared declared : declarations) {
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
            if (declared.versionManaged() && em.platformSupplies(declared.key(), mgmt)) {
                platformSupplied.add(declared.dep().module());
                if (!em.chainManages(declared.key()))
                    bomSupplied.add(declared.dep().module());
            }
            mapDependency(declared.dep(), byScope, report);
        }
        mapManagement(mgmt, byScope, report, hoisted);
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
        DependencyMapping.warnUnresolvedVersion(dep, report);
        DependencyMapping.warnMetaversion(dep, report);
        Scope scope = DependencyMapping.scope(dep.scope());
        if (DependencyMapping.isPom(dep)) {
            report.warning("`<type>pom</type>` on " + dep.module()
                    + " is written to [platform-dependencies]: its dependencyManagement governs versions, and"
                    + " the libraries it lists are not on the classpath — declare the ones the code uses.");
            scope = Scope.PLATFORM;
        }
        Dependency d = raiseToEngineFloor(DependencyMapping.toDependency(dep), scope, report);
        // A BOM import governs versions, not a classpath subtree; its exclusions have nothing to prune.
        if (scope != Scope.PLATFORM) d = ExclusionMapping.apply(d, dep, report);
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
     * {@code <annotationProcessorPaths>} → {@code [processor-dependencies]}, and the paths of a
     * {@code testCompile}-only execution → {@code [test-processor-dependencies]}, so compile-test
     * alone runs them as under Maven. With the element present Maven hands javac a processor path
     * and stops discovering processors on the classpath, which is exactly what the jk tables do, so
     * only the listed paths are written. Without it, the processors {@link KnownProcessors}
     * recognizes among the plain dependencies are written.
     */
    private static void mapProcessorPaths(
            PluginFacts.ProcessorPaths paths, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        mapProcessorPaths(paths.shared(), Scope.PROCESSOR, byScope, report);
        List<Pom.Dep> testOnly = new ArrayList<>();
        for (Pom.Dep path : paths.test()) {
            if (paths.shared().stream().noneMatch(s -> s.module().equals(path.module()))) testOnly.add(path);
        }
        mapProcessorPaths(testOnly, Scope.TEST_PROCESSOR, byScope, report);
        if (!testOnly.isEmpty()) {
            report.warning("`<annotationProcessorPaths>` on a `testCompile` execution: "
                    + testOnly.stream().map(Pom.Dep::module).collect(Collectors.joining(", "))
                    + " written to [test-processor-dependencies], so compile-test alone runs them.");
        }
    }

    private static void mapProcessorPaths(
            List<Pom.Dep> paths, Scope scope, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        for (Pom.Dep path : paths) {
            if (PluginFacts.usable(path.version()) == null) {
                report.warning("`<annotationProcessorPaths>` entry " + path.module()
                        + " has no version in the POM or its dependencyManagement; jk wrote `=unresolved`.");
            }
            byScope.computeIfAbsent(scope, s -> new ArrayList<>()).add(DependencyMapping.toDependency(path));
        }
    }

    /**
     * The optional deps inactive profiles contribute, after the POM's own handles are settled: a
     * handle already taken in the scope gets a numeric suffix, the same rule as
     * {@link #uniquifyHandles}, so the feature's list still names what was written. A profile
     * dependency with no version of its own that names a coordinate the POM already declares is
     * Maven's merge of the two — the profile's row over the declared one, the version kept — so it
     * takes the declared version rather than {@code unresolved}.
     */
    private static void addOptionalDeps(Map<Scope, List<Dependency>> byScope, Map<Scope, List<Dependency>> optional) {
        List<Dependency> declared = new ArrayList<>();
        byScope.values().forEach(declared::addAll);
        for (Map.Entry<Scope, List<Dependency>> e : optional.entrySet()) {
            List<Dependency> deps = byScope.computeIfAbsent(e.getKey(), s -> new ArrayList<>());
            Set<String> seen = new HashSet<>();
            for (Dependency d : deps) seen.add(d.library());
            for (Dependency d : e.getValue()) {
                String handle = d.library();
                for (int n = 2; !seen.add(handle); n++) handle = d.library() + "-" + n;
                deps.add(declaredVersion(d, declared).withLibrary(handle).withOptional(true));
            }
        }
    }

    /** {@code d} at the version of the declared dependency naming its coordinate, when {@code d} has none. */
    private static Dependency declaredVersion(Dependency d, List<Dependency> declared) {
        if (!DependencyMapping.UNRESOLVED.equals(d.version().raw())) return d;
        for (Dependency existing : declared) {
            if (existing.group().equals(d.group())
                    && existing.module().equals(d.module())
                    && existing.kind() == d.kind()
                    && Objects.equals(existing.classifier(), d.classifier())
                    && !DependencyMapping.UNRESOLVED.equals(existing.version().raw())) {
                return d.withVersion(existing.version());
            }
        }
        return d;
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
     * inherited table governs transitive versions too. Bare pins nothing declared uses, and every
     * entry with {@code <exclusions>}, become {@code [managed-dependencies]} rows, which govern
     * transitive versions and prune every edge onto the module the way Maven's inline {@code
     * dependencyManagement} does; the ones a reactor parent owns are gathered into {@code hoisted}
     * for the workspace root when a workspace import passes one.
     */
    private static void mapManagement(
            EffectiveModel.Management mgmt,
            Map<Scope, List<Dependency>> byScope,
            ImportReport.Builder report,
            @Nullable List<Dependency> hoisted) {
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
        ManagedRows rows = new ManagedRows();
        Set<String> seen = new HashSet<>();
        for (EffectiveModel.InlinePin pin : mgmt.inline()) {
            Pom.Dep dep = pin.dep();
            if (dep.version() == null || dep.version().isBlank()) {
                rows.versionless(pin.owner(), dep.module());
                continue;
            }
            if (PluginFacts.usable(dep.version()) == null) {
                rows.unresolved(pin.owner(), dep.module());
                continue;
            }
            if (DependencyMapping.unmappedType(dep) != null || !seen.add(dep.module())) continue;
            Dependency row = ExclusionMapping.apply(
                    Dependency.of(
                            dep.artifactId(),
                            dep.module(),
                            VersionSelector.parse(dep.version().trim())),
                    dep,
                    report);
            if (pin.reactorParent() && hoisted != null) {
                // Reported once, by the workspace import, when the root's table is written.
                if (hoisted.stream().noneMatch(h -> h.module().equals(row.module()))) hoisted.add(row);
                continue;
            }
            byScope.computeIfAbsent(Scope.MANAGED, s -> new ArrayList<>()).add(row);
            rows.written(pin.owner(), row);
        }
        rows.flush(report);
    }

    /**
     * The manifest key (dep {@code library} handle) must be unique per scope section — the renderer
     * keys each section on it, so a collision silently drops an edge. Maven allows same-artifactId
     * deps in one scope (different groups); disambiguate deterministically in declaration order.
     * The renamed edge keeps every other field — {@code optional}, kind, classifier, exclusions.
     */
    static void uniquifyHandles(Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
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
                deps.set(i, d.withLibrary(candidate));
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
        if (d.isPlatformManaged() || !(d.version() instanceof VersionSelector.Exact exact)) return d;
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

    /**
     * The managed tables of the BOMs inactive profiles import, read through the import's
     * repositories once each; a BOM no repository serves is a row, and the profile's dependencies
     * it would have versioned stay {@code unresolved}.
     */
    private static ProfileMapping.BomTables profileBoms(RepoModelResolver resolver, ImportReport.Builder report) {
        Map<String, Optional<List<Pom.Dep>>> read = new HashMap<>();
        EffectivePomBuilder builder = new EffectivePomBuilder(resolver.repos());
        return (groupId, artifactId, version) -> read.computeIfAbsent(
                        groupId + ":" + artifactId + ":" + version, gav -> {
                            try {
                                return Optional.of(builder.build(Coordinate.of(groupId, artifactId, version))
                                        .managedDependencies());
                            } catch (IOException | RuntimeException e) {
                                report.warning("BOM `" + gav + "`, imported by a profile's `<dependencyManagement>`,"
                                        + " could not be read (" + e.getMessage() + "); the profile's dependencies"
                                        + " it versions are written `=unresolved`.");
                                return Optional.empty();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return Optional.empty();
                            }
                        })
                .orElse(null);
    }

    // --- repositories -------------------------------------------------------

    /**
     * Every repository the effective model declares or inherits, with its {@code <releases>} /
     * {@code <snapshots>} policy; Central is the implicit default. A plaintext {@code http://}
     * repository is written {@code blocked}, as Maven 3.9 blocks it, and is a row.
     */
    static List<RepositorySpec> mapRepositories(List<Repository> repositories, ImportReport.Builder report) {
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
                URI written = RepositorySpec.normalizedUrl(new URI(url.trim()));
                RepositorySpec spec = new RepositorySpec(name, written).withPolicy(releases, snapshots);
                if ("http".equalsIgnoreCase(written.getScheme()) && !RepositorySpec.loopback(written.getHost())) {
                    spec = spec.withBlocked();
                    report.warning("`<repository>` `" + name + "` at " + written + " is plaintext http, which Maven"
                            + " 3.9 blocks by default: it is written `blocked = true` under [repositories], so the"
                            + " lock never asks it and names it when an artifact resolves nowhere else. Set"
                            + " `allow-insecure = true` in place of `blocked` to ask it.");
                }
                deduped.put(name, spec);
            } catch (URISyntaxException e) {
                report.warning("`<repository><url>" + url + "</url></repository>` is not a valid URI; skipped.");
            }
        }
        return new ArrayList<>(deduped.values());
    }

    /**
     * {@code declared} followed by the repositories of the active {@code settings.xml} profiles the
     * POM does not itself name; Central stays implicit. A profile repository a project uses without
     * writing it into the POM is what the manifest's {@code [repositories]} has to carry for the
     * lock to reach it.
     */
    static List<RepositorySpec> withSettingsRepositories(List<RepositorySpec> declared, MavenSettings settings) {
        Map<String, RepositorySpec> byName = new LinkedHashMap<>();
        for (RepositorySpec spec : declared) byName.putIfAbsent(spec.name(), spec);
        for (RepositorySpec spec : settings.profileRepositories()) {
            if (spec.name().equals("central")
                    || RepoModelResolver.isCentral(spec.url().toString())) continue;
            byName.putIfAbsent(spec.name(), spec);
        }
        return new ArrayList<>(byName.values());
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
            EffectiveModel em,
            ImportReport.Builder report,
            boolean isWorkspaceRoot,
            @Nullable InheritedRows inherited) {
        if (!isWorkspaceRoot && inherited == null && !em.model().getModules().isEmpty()) {
            report.warning("`<modules>` block present but this import was run in single-POM mode."
                    + " Re-run as `jk import pom.xml` from the project root to materialise a workspace.");
        }
        BuildExtensions.report(em, report, inherited, isWorkspaceRoot);
    }
}
