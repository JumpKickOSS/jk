// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ImportedKotlin;
import cc.jumpkick.compat.NodeImportMapping;
import cc.jumpkick.config.UserConfigEditor;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.InFlightRequests;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JdkTable;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Jlink;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.ReleaseSources;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.stall.StallWatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
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
            JkBuild root,
            Map<String, JkBuild> modules,
            ImportReport report,
            Set<Path> pomFiles,
            /** The {@code ~/.jk/config.toml} tables naming the variables the build's credentials come from. */
            List<UserConfigEditor.CredentialTable> machineCredentials) {}

    /** Fetches a file the POM names by URL (a generator's remote spec); an {@link IOException} is the row's reason. */
    @FunctionalInterface
    public interface RemoteFile {
        byte[] fetch(URI uri) throws IOException;
    }

    /** How a refusal for a POM read that stopped advancing opens. */
    public static final String BUDGET_EXCEEDED = "Resolution budget exceeded: ";

    /** The credential tables the POMs' plugins read from the environment, once per id. */
    private final List<UserConfigEditor.CredentialTable> machineCredentials = new ArrayList<>();

    final RepoModelResolver resolver;
    private final RemoteFile remote;

    /** How long the POM reads may stand still before the import stops; {@code JK_RESOLVE_TIMEOUT_MS}. */
    private long stallWindowMs = StallWatch.envWindowMs();

    /** The clock the stall window is measured on. */
    private Clock stallClock = Clock.SYSTEM;

    /** The profile ids activated by name ({@code jk import -P}), beside what each POM activates itself. */
    private List<String> activeProfiles = List.of();

    private FrontendCollector frontends = FrontendCollector.OFF;

    /**
     * Maven's {@code settings.xml}: the repositories of its active profiles join every imported
     * manifest's {@code [repositories]}, the way Maven consults them beside a POM's own.
     */
    private final MavenSettings settings;

    /** Parents and BOM imports are fetched through {@code repos}, plus any {@code <repository>} the POM declares. */
    public PomImporter(RepoGroup repos, Cas cas) {
        this(repos, cas, overHttp(new Http()));
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
                HttpResponse<byte[]> response = http.documents().get(uri);
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

    /** This importer measuring its stall window on {@code clock} — tests. */
    PomImporter stallClock(Clock clock) {
        this.stallClock = clock;
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

    /** Place frontend-maven-plugin builds into {@code frontends}; only {@code jk import} moves files. */
    public PomImporter frontends(FrontendCollector frontends) {
        this.frontends = frontends;
        return this;
    }

    public Result importFrom(Path pomXml) throws IOException {
        Path file = pomXml.toAbsolutePath();
        byte[] xml = Files.readAllBytes(file);
        return watched(() -> importModel(
                        EffectiveModel.build(
                                xml, file, resolver.newCopy(), null, activeProfiles, MavenConfig.properties(file)),
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
                            EffectiveModel.build(xml, null, resolver.newCopy(), null, activeProfiles, new Properties()),
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
                stallClock,
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
     * — the ones a BOM of the reactor may turn out to own ({@link ReactorManaged}). {@code
     * reactorSupplied} names the modules declared without a version that a reactor parent's own
     * {@code dependencyManagement} entry supplies: the workspace's version ({@link ReactorVersions}).
     */
    private record Imported(
            JkBuild jkBuild,
            ImportReport report,
            Set<String> platformSupplied,
            Set<String> bomSupplied,
            Set<String> reactorSupplied) {

        /** The result with every platform-supplied dependency written without a version. */
        Result platformManaged() {
            return platformManaged(platformSupplied);
        }

        /** The result with each dependency on a module of {@code supplied} written without a version. */
        Result platformManaged(Set<String> supplied) {
            return new Result(PlatformManaged.apply(jkBuild, supplied), report);
        }
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
        UserConfigEditor.CredentialTable registryCredential = generators.registryCredential();
        if (registryCredential != null
                && machineCredentials.stream().noneMatch(c -> c.name().equals(registryCredential.name())))
            machineCredentials.add(registryCredential);
        Set<String> consumedPlugins = new HashSet<>(generators.consumedPlugins());
        consumedPlugins.addAll(BuildExtensions.extensionPlugins(em.model()));
        // hibernate-maven-plugin is the table only when it enhances; its other goals keep the row.
        PluginConfig hibernate = HibernateEnhancePlugin.map(em.model(), report);
        if (hibernate != null) consumedPlugins.add(HibernateEnhancePlugin.MAVEN_PLUGIN);
        LibDirPlugins.Mapped libDir = LibDirPlugins.map(em.model(), PluginFacts.mainClass(em.model()), report);
        if (libDir.consumed()) consumedPlugins.add(LibDirPlugins.DEPENDENCY_PLUGIN);
        HelidonPlugins.Helidon helidon =
                HelidonPlugins.map(em, libDir.libDir(), PluginFacts.mainClass(em.model()), report);
        if (helidon != null) consumedPlugins.addAll(helidon.consumedPlugins());
        SourceTreePlugins.SourceTree sourceTree =
                SourceTreePlugins.map(em, generators.outputRoots(), consumedPlugins, report, inherited, false);
        Project project = mapProject(em, report, sourceTree);
        PluginFacts.ProcessorPaths processorPaths = PluginFacts.annotationProcessorPaths(em.model());
        // The [helidon] table contributes Helidon's processors and its BOM.
        if (helidon != null) processorPaths = helidon.processors(processorPaths);
        Set<String> platformSupplied = new HashSet<>();
        Set<String> bomSupplied = new HashSet<>();
        Set<String> reactorSupplied = new HashSet<>();
        Map<Scope, List<Dependency>> byScope = PomDependencies.map(
                em,
                report,
                processorPaths.all(),
                hoisted,
                helidon == null ? module -> false : helidon::replacesPlatform,
                platformSupplied,
                bomSupplied,
                reactorSupplied);
        PomDependencies.processorPaths(processorPaths, byScope, report);
        ProfileMapping.Mapped profiles = ProfileMapping.map(em, report, PomDependencies.profileBoms(resolver, report));
        var renamedHandles = OptionalProfileDeps.add(byScope, profiles.optionalDeps(), profiles.optionalDepFeatures());
        Features profileFeatures = OptionalProfileDeps.renameHandles(profiles.features(), renamedHandles);
        List<Repository> repositories = new ArrayList<>(em.model().getRepositories());
        repositories.addAll(profiles.repositories());
        List<RepositorySpec> repos = withSettingsRepositories(mapRepositories(repositories, report), settings);
        warnUnsupportedSections(em, report, /* isWorkspaceRoot= */ false, inherited);

        String mainClass = PluginFacts.mainClass(em.model());
        TestPlugins.TestSettings tests = TestPlugins.map(em.model(), report);
        if (helidon != null) tests = helidon.tests(tests);
        MigrationPlugins.report(em.model(), report);
        PackagingPlugins.Packaging packaging = PackagingPlugins.map(em, mainClass, report);
        JkBuild.Application application = mainClass != null
                ? new JkBuild.Application(
                        mainClass,
                        packaging.fatJar(),
                        false,
                        false,
                        null,
                        packaging.relocate(),
                        List.of(),
                        helidon == null ? libDir.libDir() : helidon.libDir(libDir.libDir()))
                : null;
        JkBuild.Builder builder = JkBuild.builder(project)
                // The workspace root's [format] is the one jk reads; a member inherits the plugin, not the table.
                .format(
                        inherited == null
                                ? FormatPlugins.map(em.model(), report).orElse(JkBuild.FormatConfig.EMPTY)
                                : JkBuild.FormatConfig.EMPTY)
                .dependencies(new JkBuild.Dependencies(byScope, !processorPaths.testOwn()))
                .repositories(repos)
                .features(profileFeatures)
                .profiles(toProfiles(profiles.profiles()))
                .application(application)
                .nativeConfig(packaging.nativeConfig())
                .image(packaging.image())
                .pluginConfig(packaging.springBoot())
                .pluginConfig(packaging.quarkus())
                .pluginConfig(helidon == null ? null : helidon.table())
                .pluginConfig(hibernate)
                .pluginConfig(JavafxMavenPlugin.map(em.model(), report))
                .pluginConfig(LintPlugins.map(em, report, inherited, resolver.repos()));
        for (PluginConfig table : generators.tables()) builder.pluginConfig(table);
        List<ReleaseSources> releases = MultiReleasePlugins.map(em.model(), report);
        JkBuild jkBuild = builder.build(buildBlock(em.model(), sourceTree, tests, report)
                        .withBuildInfo(BuildInfoPlugins.map(em, report).orElse(null))
                        .withModuleServices(ModuleServicesPlugin.map(em, report))
                        .withDokka(BuildInfoPlugins.mapDokka(em, report).orElse(BuildBlock.Dokka.DEFAULT))
                        .withMultiRelease(releases)
                        .withWar(packaging.war())
                        .withJlink(jlink(em, mainClass, helidon, report)))
                .build();
        jkBuild = NullnessImport.map(jkBuild, report);
        Map<String, String> manifest = new LinkedHashMap<>(PluginFacts.manifestEntries(em.model()));
        // A [multi-release] jar carries the attribute already.
        if (!releases.isEmpty()) manifest.remove(Attributes.Name.MULTI_RELEASE.toString());
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        jkBuild = frontends.member(em.model(), jkBuild, report, inherited == null);
        if (jkBuild.project().nodeSpec().isEmpty() && em.model().getProjectDirectory() != null) {
            jkBuild = NodeImportMapping.apply(
                    jkBuild,
                    NodeImportMapping.quinoa(em.model().getProjectDirectory().toPath(), report));
        }
        return new Imported(jkBuild, report.build(), platformSupplied, bomSupplied, reactorSupplied);
    }

    /**
     * {@code [jlink]} for an application: {@code javafx-maven-plugin}'s jlink settings, else
     * {@code helidon-maven-plugin}'s {@code jlink-image} goal under a {@code [helidon]} table.
     */
    private static @Nullable Jlink jlink(
            EffectiveModel em,
            @Nullable String mainClass,
            HelidonPlugins.@Nullable Helidon helidon,
            ImportReport.Builder report) {
        if (mainClass == null) return null;
        Jlink javafx = JavafxMavenPlugin.jlink(em.model(), report);
        if (javafx != null || helidon == null) return javafx;
        return helidon.jlink();
    }

    /**
     * {@code [javac] args} from {@code <compilerArgs>}, with a {@code [javac.test]} table when a
     * compiler execution reached one compile step alone; {@code [build]} / {@code [test]} extra
     * source roots; {@code [test] exclude-src} from {@code <testExcludes>}; {@code [test]} tag
     * filters, excluded classes and dependencies, JVM flags and system properties from Surefire and
     * Failsafe; Failsafe's class patterns and excludes as {@code [test.suites.integration]}.
     */
    private static BuildBlock buildBlock(
            Model model,
            SourceTreePlugins.SourceTree sourceTree,
            TestPlugins.TestSettings tests,
            ImportReport.Builder report) {
        // A POM's direct version is the version Maven used, whatever a transitive asked for.
        BuildBlock build = BuildBlock.EMPTY.withTestFailures(tests.failures());
        PluginFacts.CompilerArgs args = PluginFacts.compilerArgs(model);
        if (args.split()) {
            build = build.withJavac(new JavacConfig(Map.of(), args.main(), new JavacConfig(Map.of(), args.test())));
            report.warning("<compilerArgs> split into [javac] args and [javac.test] args");
        } else if (!args.main().isEmpty()) {
            build = build.withJavac(new JavacConfig(Map.of(), args.main()));
        }
        if (!args.granted().isEmpty()) {
            report.warning("<compilerArgs>: dropped "
                    + packagesOf(args.granted()).size() + " -J jdk.compiler opens jk already grants");
        }
        if (!sourceTree.extraSrc().isEmpty()) build = build.withExtraSrc(sourceTree.extraSrc());
        if (!sourceTree.testExtraSrc().isEmpty()) build = build.withTestExtraSrc(sourceTree.testExtraSrc());
        if (!sourceTree.resources().isEmpty()) build = build.withResources(sourceTree.resources());
        if (!tests.excludeClasses().isEmpty()) build = build.withTestExcludeClasses(tests.excludeClasses());
        if (!tests.excludeDependencies().isEmpty())
            build = build.withTestExcludeDependencies(tests.excludeDependencies());
        Map<String, DeclaredSuite> suites = TestPlugins.suites(tests, report);
        if (!suites.isEmpty()) build = build.withTestSuites(suites);
        if (!tests.jvm().isEmpty()) build = build.withTestJvm(tests.jvm());
        if (!tests.env().isEmpty()) build = build.withTestEnv(tests.env());
        List<String> testExcludes = PluginFacts.compilerSourceGlobs(model, "testExcludes", true);
        if (!testExcludes.isEmpty()) build = build.withTestExcludeSrc(testExcludes);
        reportCompilerGlobs(model, "testIncludes", true, report);
        reportCompilerGlobs(model, "excludes", false, report);
        reportCompilerGlobs(model, "includes", false, report);
        return build;
    }

    /** Compiler source globs with no jk key: every source under a root compiles, so each is a row. */
    private static void reportCompilerGlobs(Model model, String element, boolean test, ImportReport.Builder report) {
        List<String> globs = PluginFacts.compilerSourceGlobs(model, element, test);
        if (globs.isEmpty()) return;
        report.warning("maven-compiler-plugin <" + element + "> " + String.join(", ", globs) + ": skipped; jk"
                + " compiles every " + (test ? "test" : "main") + " source. "
                + (test ? "Use [test] exclude-src" : "Move excluded sources out"));
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
                ? "effective model not built"
                : "parent " + parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion()
                        + " not resolved";
        report.error(subject + " (" + em.failure() + "); nothing inherited, managed versions written =unresolved");
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
        machineCredentials.clear();
        Path rootFile = rootPom.toAbsolutePath();
        byte[] rootXml = Files.readAllBytes(rootFile);
        Model rootRaw = EffectiveModel.rawModel(rootXml);
        Properties userProperties = MavenConfig.properties(rootFile);
        if (!ReactorModules.declaresModules(rootRaw)) {
            Result single = importModel(
                            EffectiveModel.build(
                                    rootXml, rootFile, resolver.newCopy(), null, activeProfiles, userProperties),
                            remote,
                            settings,
                            null,
                            null)
                    .platformManaged();
            return new WorkspaceImportResult(
                    single.jkBuild(), Map.of(), single.report(), Set.of(rootFile), List.copyOf(machineCredentials));
        }

        ImportReport.Builder report = ImportReport.builder();
        reportUnknownProfiles(rootRaw, report);
        ReactorModelResolver reactor = new ReactorModelResolver(resolver, activeProfiles, userProperties);
        // Each module is imported as the walk reaches it and its effective model is dropped right
        // after: what stays of a module is its JkBuild and its rows.
        Map<String, Imported> imported = new LinkedHashMap<>();
        Map<String, Set<String>> reactorSupplied = new LinkedHashMap<>();
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
                    reactorSupplied.put(leaf.path(), child.reactorSupplied());
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

        List<RepositorySpec> rootRepositories =
                withSettingsRepositories(mapRepositories(rootModel.model().getRepositories(), report), settings);
        Map<String, JkBuild> imports = WorkspaceRepositories.disambiguateRepositories(
                rootRepositories,
                SelfHostedFrameworks.strip(
                        memberBuilds(imported, reactorManaged, found.unbuilt()),
                        found.boms(),
                        rootProject.version(),
                        report),
                report);
        // A version a reactor parent manages is the workspace's: its members read it as
        // `x.workspace = true`, and a version a module writes itself stays that module's pin.
        Set<String> reactorCoordinates = new HashSet<>(found.unbuilt().keySet());
        for (JkBuild member : imports.values())
            reactorCoordinates.add(
                    member.project().group() + ":" + member.project().name());
        for (ReactorModules.Leaf bom : found.boms()) reactorCoordinates.add(bom.ga());
        ReactorVersions.Hoisted shared = ReactorVersions.hoist(imports, reactorSupplied, reactorCoordinates);
        Map<String, JkBuild> members = shared.members();
        SiblingNames.report(members, report);
        // The workspace root is a coordination point — no deps of its own — but it owns the one
        // repository list the workspace lock resolves against, so every member's `<repositories>`
        // is hoisted onto it.
        JkBuild rootJkBuild = JkBuild.builder(rootProject)
                .format(FormatPlugins.map(rootModel.model(), report).orElse(JkBuild.FormatConfig.EMPTY))
                .workspace(new Workspace(
                        leaves.stream().map(ReactorModules.Leaf::path).toList(), shared.dependencies()))
                .repositories(WorkspaceRepositories.hoistRepositories(rootRepositories, members.values()))
                .application(rootApplication)
                .build(BuildBlock.EMPTY)
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
            rootJkBuild = rootJkBuild.withDependencyScopes(rootDeps);
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
            report.warning(bom + " is a BOM no module imports; not a workspace module");
        }
        FrontendImport.Relocated placed = frontends.place(
                rootModel.model(), Objects.requireNonNull(rootFile.getParent()), rootJkBuild, rewritten, report);
        return new WorkspaceImportResult(
                placed.root(), placed.members(), report.build(), found.pomFiles(), List.copyOf(machineCredentials));
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
        report.error("<modules> only in inactive profiles (" + String.join(", ", inactive)
                + "); no module imported. Re-run jk import pom.xml -P " + inactive.getFirst());
    }

    /** A {@code -P} id no {@code <profile>} of the root POM declares is a row; a member's own profile still activates there. */
    private void reportUnknownProfiles(Model rootRaw, ImportReport.Builder report) {
        for (String id : activeProfiles) {
            boolean declared = rootRaw.getProfiles().stream().anyMatch(p -> id.equals(p.getId()));
            if (!declared) {
                report.warning("-P " + id + ": the root POM has no such profile; only members declaring it use it");
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
        String description = description(em);
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
     * The POM's own {@code <description>}, or one inherited from a parent in the same source tree;
     * a published parent's describes the framework, never this project, so it is dropped.
     */
    private static @Nullable String description(EffectiveModel em) {
        String description = em.model().getDescription();
        if (description == null || description.isBlank()) return null;
        if (em.raw().getDescription() != null) return description;
        boolean published = em.ancestors().stream()
                .filter(a -> a.raw().getDescription() != null)
                .findFirst()
                .map(EffectiveModel.Ancestor::published)
                .orElse(false);
        return published ? null : description;
    }

    /**
     * A CI-friendly version the effective model left uninterpolated names a property neither {@code
     * .mvn/maven.config} nor any POM in the chain defines (Maven takes it from {@code -D}); it is
     * filled from the model's own properties where it can be, and what is left is written as {@link
     * CiFriendlyVersions#FALLBACK} with a row naming the property.
     */
    private static String concreteVersion(String version, Model model, ImportReport.Builder report) {
        if (!CiFriendlyVersions.hasPlaceholder(version)) return version;
        String filled = CiFriendlyVersions.interpolate(version, model.getProperties()::getProperty);
        List<String> missing = CiFriendlyVersions.unresolved(filled);
        if (missing.isEmpty()) return filled;
        report.warning("<version>" + version + "</version>: " + String.join(", ", missing)
                + " is undefined (set -D" + missing.getFirst() + " in .mvn/maven.config); written as version = \""
                + CiFriendlyVersions.FALLBACK + "\"");
        return CiFriendlyVersions.FALLBACK;
    }

    private static int javaLevel(Model model, ImportReport.Builder report) {
        Optional<PluginFacts.CompilerLevel> declared = PluginFacts.compilerLevel(model);
        if (declared.isEmpty()) return 25;
        int release = declared.get().release();
        if (release >= PluginFacts.JAVA_FLOOR) return release;
        report.warning(declared.get().origin() + " " + release + " raised to java = " + PluginFacts.JAVA_FLOOR
                + ", JumpKick's minimum. Set java = " + release + " back if it needs a removed API");
        return PluginFacts.JAVA_FLOOR;
    }

    private static @Nullable String toolchainPin(Model model, ImportReport.Builder report) {
        Optional<String> pin = PluginFacts.toolchainJdk(model);
        if (pin.isEmpty()) return null;
        String spec = pin.get();
        if (Project.majorOf(spec) < PluginFacts.JAVA_FLOOR) {
            report.warning("toolchain JDK " + spec + " is below JumpKick's minimum " + PluginFacts.JAVA_FLOOR
                    + "; no jdk written");
            return null;
        }
        report.warning("<toolchains> JDK " + spec + " written as " + JdkTable.fromRetired(spec)
                + ", which installs it; drop it if java = is enough");
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
            report.warning("kotlin-maven-plugin: no version found; kotlin = latest, pinned by jk lock");
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
        report.warning("kotlin-maven-plugin beside Java sources in " + PluginFacts.mainSourceDirectory(model)
                + ": mixed module; java sets javac, kotlin = \"" + kotlin.raw() + "\" sets kotlinc");
        return true;
    }

    // --- repositories -------------------------------------------------------

    /** The name a non-Central repository declared under Central's reserved id is written as. */
    static final String CENTRAL_MIRROR = RepositorySpec.CENTRAL + "-mirror";

    /**
     * Every repository the effective model declares or inherits, with its {@code <releases>} /
     * {@code <snapshots>} policy; Central is the implicit default, and one at another URL under
     * its id (a corporate redirect) is written as {@link #CENTRAL_MIRROR}, since the lock keeps
     * {@code central} for Central itself. A plaintext {@code http://} repository is written {@code
     * blocked}, as Maven 3.9 blocks it, and is a row; every other one {@code optional}, as Maven
     * falls through a repository that answers nothing.
     */
    static List<RepositorySpec> mapRepositories(List<Repository> repositories, ImportReport.Builder report) {
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Repository repo : repositories) {
            String id = repo.getId();
            String url = repo.getUrl();
            if (url == null || url.isBlank()) {
                report.warning("repository " + id + ": no <url>; skipped");
                continue;
            }
            if (RepoModelResolver.isCentral(url)) continue;
            String name = (id == null || id.isBlank()) ? "repo" + (deduped.size() + 1) : id;
            if (name.equals(RepositorySpec.CENTRAL)) {
                name = CENTRAL_MIRROR;
                report.warning("repository central at " + url.trim() + " is not Maven Central; written as "
                        + CENTRAL_MIRROR + ". A machine-wide mirror belongs in ~/.jk/config.toml");
            }
            boolean releases = repo.getReleases() == null || repo.getReleases().isEnabled();
            boolean snapshots =
                    repo.getSnapshots() == null || repo.getSnapshots().isEnabled();
            if (!releases && !snapshots) continue;
            try {
                URI written = RepositorySpec.normalizedUrl(new URI(url.trim()));
                RepositorySpec spec = new RepositorySpec(name, written).withPolicy(releases, snapshots);
                if ("http".equalsIgnoreCase(written.getScheme()) && !RepositorySpec.loopback(written.getHost())) {
                    spec = spec.withBlocked();
                    report.warning("repository " + name + " at " + written + " is plain http; written blocked = true."
                            + " Use allow-insecure = true to query it");
                } else {
                    spec = spec.withOptional();
                }
                deduped.put(name, spec);
            } catch (URISyntaxException e) {
                report.warning("repository URL " + url + " is not a valid URI; skipped");
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
            if (spec.name().equals(RepositorySpec.CENTRAL)
                    || RepoModelResolver.isCentral(spec.url().toString())) continue;
            byName.putIfAbsent(spec.name(), spec);
        }
        return new ArrayList<>(byName.values());
    }

    // --- unsupported-section warnings ---------------------------------------

    private static void warnUnsupportedSections(
            EffectiveModel em,
            ImportReport.Builder report,
            boolean isWorkspaceRoot,
            @Nullable InheritedRows inherited) {
        if (!isWorkspaceRoot && inherited == null && !em.model().getModules().isEmpty()) {
            report.warning(
                    "<modules> ignored in single-POM mode; run jk import pom.xml from the root for a" + " workspace");
        }
        BuildExtensions.report(em, report, inherited, isWorkspaceRoot);
    }
}
