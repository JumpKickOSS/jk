// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Converts a Maven {@code pom.xml} into a {@link JkBuild} plus an {@link ImportReport} of
 * unfaithful constructs. The POM is read as Maven's effective model ({@link EffectiveModel}):
 * parents flattened, {@code dependencyManagement} applied, BOM imports and properties resolved,
 * profiles active on this machine folded in. Coordinates, scoped deps (bare versions → exact pins),
 * BOM imports, repositories and the compiler release are mapped; other POM features land in the
 * report, which also names what each parent contributed.
 */
public final class PomImporter {

    public record Result(JkBuild jkBuild, ImportReport report) {}

    /**
     * Outcome of importing a multi-module POM tree: the root's {@link JkBuild} (carrying the
     * workspace block), each module's {@link JkBuild} keyed by the relative module path, and one
     * aggregated {@link ImportReport} spanning the root + every child.
     */
    public record WorkspaceImportResult(JkBuild root, Map<String, JkBuild> modules, ImportReport report) {}

    private final RepoModelResolver resolver;

    /** Parents and BOM imports are fetched through {@code repos}, plus any {@code <repository>} the POM declares. */
    public PomImporter(RepoGroup repos, Cas cas) {
        this.resolver = new RepoModelResolver(repos, cas);
    }

    public Result importFrom(Path pomXml) throws IOException {
        Path file = pomXml.toAbsolutePath();
        return importModel(EffectiveModel.build(Files.readAllBytes(file), file, resolver.newCopy(), null));
    }

    /** A POM with no file behind it (an archive's embedded pom.xml): no {@code relativePath} lookup. */
    public Result importFromBytes(byte[] xml) {
        return importModel(EffectiveModel.build(xml, null, resolver.newCopy(), null));
    }

    private static Result importModel(EffectiveModel em) {
        ImportReport.Builder report = ImportReport.builder();
        reportInheritanceFailure(em, report);
        Project project = mapProject(em, report);
        Map<Scope, List<Dependency>> byScope = mapDependencies(em, report);
        List<RepositorySpec> repos = mapRepositories(em.model(), report);
        warnUnsupportedSections(em, report, /* isWorkspaceRoot= */ false);

        String mainClass = PluginFacts.mainClass(em.model());
        JkBuild.Application application = mainClass != null ? new JkBuild.Application(mainClass, false) : null;
        JkBuild jkBuild = JkBuild.builder(project)
                .dependencies(new JkBuild.Dependencies(byScope))
                .repositories(repos)
                .application(application)
                .build();
        Map<String, String> manifest = PluginFacts.manifestEntries(em.model());
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        return new Result(jkBuild, report.build());
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
     * builds. No {@code <modules>} → single-POM import. Sibling POMs answer parent lookups first, so
     * the reactor never goes to the network for itself.
     */
    public WorkspaceImportResult importWorkspace(Path rootPom) throws IOException {
        Path rootFile = rootPom.toAbsolutePath();
        byte[] rootXml = Files.readAllBytes(rootFile);
        Model rootRaw = EffectiveModel.rawModel(rootXml);
        List<String> modules = rootRaw.getModules();
        if (modules.isEmpty()) {
            Result single = importModel(EffectiveModel.build(rootXml, rootFile, resolver.newCopy(), null));
            return new WorkspaceImportResult(single.jkBuild(), Map.of(), single.report());
        }

        ImportReport.Builder report = ImportReport.builder();
        ReactorModelResolver reactor = new ReactorModelResolver();
        reactor.add(rootFile, rootRaw);
        Path projectDir = Objects.requireNonNull(rootFile.getParent());
        Map<String, byte[]> childXml = new LinkedHashMap<>();
        for (String module : modules) {
            Path childPom = projectDir.resolve(module).resolve("pom.xml");
            if (!Files.exists(childPom)) {
                report.error("workspace module `" + module + "` has no pom.xml at " + childPom);
                continue;
            }
            byte[] xml = Files.readAllBytes(childPom);
            reactor.add(childPom, EffectiveModel.rawModel(xml));
            childXml.put(module, xml);
        }

        EffectiveModel rootModel = EffectiveModel.build(rootXml, rootFile, resolver.newCopy(), reactor);
        reportInheritanceFailure(rootModel, report);
        Project rootProject = mapProject(rootModel, report);
        warnUnsupportedSections(rootModel, report, /* isWorkspaceRoot= */ true);
        String rootMainClass = PluginFacts.mainClass(rootModel.model());
        JkBuild.Application rootApplication =
                rootMainClass != null ? new JkBuild.Application(rootMainClass, false) : null;
        // The workspace root is a coordination point — no deps of its own.
        JkBuild rootJkBuild = JkBuild.builder(rootProject)
                .workspace(new Workspace(modules))
                .application(rootApplication)
                .build();

        Map<String, JkBuild> moduleBuilds = new LinkedHashMap<>();
        for (var e : childXml.entrySet()) {
            Path childPom = projectDir.resolve(e.getKey()).resolve("pom.xml");
            Result child = importModel(EffectiveModel.build(e.getValue(), childPom, resolver.newCopy(), reactor));
            moduleBuilds.put(e.getKey(), child.jkBuild());
            for (ImportReport.Issue issue : child.report().issues()) {
                String prefixed = "[" + e.getKey() + "] " + issue.message();
                if (issue.severity() == ImportReport.Severity.ERROR) {
                    report.error(prefixed);
                } else {
                    report.warning(prefixed);
                }
            }
        }
        // Rewrite inter-module Maven deps to workspace edges (and test-jar → kind=tests).
        Map<String, String> siblingByGa = siblingGaIndex(rootJkBuild, moduleBuilds.values());
        Map<String, JkBuild> rewritten = new LinkedHashMap<>();
        for (var e : moduleBuilds.entrySet()) {
            rewritten.put(e.getKey(), rewriteSiblingDeps(e.getValue(), siblingByGa));
        }
        return new WorkspaceImportResult(rootJkBuild, rewritten, report.build());
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
     * Convert deps whose GA matches a workspace sibling into workspace edges. Maven
     * {@code <type>test-jar</type>} becomes {@code kind = "tests"} (Mill testModuleDeps).
     */
    private static JkBuild rewriteSiblingDeps(JkBuild module, Map<String, String> siblingByGa) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        boolean changed = false;
        for (Scope scope : Scope.values()) {
            List<Dependency> in = module.dependencies().of(scope);
            if (in.isEmpty()) continue;
            List<Dependency> out = new ArrayList<>(in.size());
            for (Dependency d : in) {
                String siblingName = siblingByGa.get(d.module());
                if (siblingName == null) {
                    // External test-jar keeps kind=tests (lock/resolve map to g:a:test-jar:tests).
                    out.add(d);
                    if (d.isTestsKind()) changed = true;
                    continue;
                }
                changed = true;
                // Library handle matches the sibling project name so `{ workspace = true }` resolves.
                // mapDependencies already forced tests-kind deps into a test scope, so kind is
                // carried as-is — never emitted where the parser would reject it.
                Dependency ws = Dependency.workspace(siblingName);
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

    private static Project mapProject(EffectiveModel em, ImportReport.Builder report) {
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
        int jdk = PluginFacts.compilerRelease(model).orElse(25);
        String description = model.getDescription();
        if (description != null && description.isBlank()) description = null;
        VersionSelector kotlin = kotlinFrom(model, report);
        // A Kotlin project sets `kotlin` and leaves `java` at 0 (mutually exclusive).
        int java = kotlin != null ? 0 : jdk;
        return Project.builder(group, model.getArtifactId(), version)
                .jdkMajor(jdk)
                .java(java)
                .kotlin(kotlin)
                .description(description)
                .build();
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

    private static Map<Scope, List<Dependency>> mapDependencies(EffectiveModel em, ImportReport.Builder report) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        Set<String> used = new HashSet<>();
        Map<String, List<String>> managedBy = new LinkedHashMap<>();
        Map<String, List<String>> inheritedFrom = new LinkedHashMap<>();
        for (EffectiveModel.Declared declared : em.dependencies(report)) {
            used.add(declared.key());
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
        if (dep.optional()) {
            report.warning("`<dependency><optional>true</optional></dependency>` on "
                    + dep.module()
                    + " — jk has no `<optional>`; emitted as a normal dep."
                    + " Use a feature flag if it should be opt-in.");
        }
        boolean testJar = isTestJar(dep);
        if (dep.classifier() != null
                && !dep.classifier().isBlank()
                && !(testJar && "tests".equalsIgnoreCase(dep.classifier()))) {
            report.warning("`<classifier>"
                    + dep.classifier()
                    + "</classifier>` on "
                    + dep.module()
                    + " — classifier support lands in a later slice; the coord was emitted without it.");
        }
        if (!dep.exclusions().isEmpty()) {
            report.warning("`<exclusions>` on "
                    + dep.module()
                    + " — exclusion support lands in a later slice; exclusions were dropped.");
        }
        warnUnresolvedVersion(dep, report);
        Scope scope = mapScope(dep.scope());
        Dependency d = toDependency(dep);
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
     * BOM imports become {@code [platform]} entries with their versions resolved; a published parent
     * whose chain manages versions is carried as one {@code [platform]} entry of its own, so the
     * inherited table governs transitive versions too. Bare pins nothing declared uses are named.
     */
    private static void mapManagement(
            EffectiveModel.Management mgmt, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        for (Pom.Dep bom : mgmt.platform()) {
            warnUnresolvedVersion(bom, report);
            byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>()).add(toDependency(bom));
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

    private static void warnUnresolvedVersion(Pom.Dep dep, ImportReport.Builder report) {
        if (PluginFacts.usable(dep.version()) != null) return;
        report.warning("`<dependency>` "
                + dep.module()
                + " has no resolved `<version>` anywhere in its parent chain; jk wrote `=unresolved`."
                + " Pin it in the POM and re-import.");
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
                deps.set(i, Dependency.of(candidate, d.module(), d.version()).withKind(d.kind()));
            }
        }
    }

    private static Scope mapScope(@Nullable String mavenScope) {
        if (mavenScope == null || mavenScope.isBlank() || "compile".equalsIgnoreCase(mavenScope)) {
            return Scope.MAIN;
        }
        return switch (mavenScope.toLowerCase(Locale.ROOT)) {
            case "runtime" -> Scope.RUNTIME;
            case "provided" -> Scope.PROVIDED;
            case "test" -> Scope.TEST;
            // system handled separately (rejected earlier).
            default -> Scope.MAIN;
        };
    }

    private static Dependency toDependency(Pom.Dep dep) {
        String version = PluginFacts.usable(dep.version());
        // The marker keeps the emitted file parseable; the report names the dependency.
        if (version == null) version = "unresolved";
        // Bare versions are exact pins, matching Maven semantics.
        VersionSelector selector = VersionSelector.parse(version);
        // Maven coordinates have no notion of a manifest "short name"; default the `name` field to
        // the artifactId, matching the manifest's own `artifact`-defaults-to-key rule. The test-jar
        // package gets a distinct `-tests` handle so a POM depending on both the jar and the
        // test-jar of one GA keeps both entries (sections key on the handle).
        boolean testJar = isTestJar(dep);
        String library = testJar ? dep.artifactId() + "-tests" : dep.artifactId();
        Dependency d = Dependency.of(library, dep.module(), selector);
        // Stash test-jar as kind=tests so workspace rewrite can emit kind = "tests".
        if (testJar) {
            d = d.withKind(DependencyKind.TESTS);
        }
        return d;
    }

    /**
     * Maven {@code <type>test-jar</type>} only. A bare {@code tests} classifier (default jar type)
     * is NOT mapped to kind=tests — classifiers are their own (unimplemented) axis, and stamping
     * kind on them produced jk.toml that the parser rejects outside test scopes.
     */
    private static boolean isTestJar(Pom.Dep dep) {
        return dep.type() != null && "test-jar".equalsIgnoreCase(dep.type());
    }

    // --- repositories -------------------------------------------------------

    /** Every repository the effective model declares or inherits; Central is the implicit default. */
    private static List<RepositorySpec> mapRepositories(Model model, ImportReport.Builder report) {
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Repository repo : model.getRepositories()) {
            String id = repo.getId();
            String url = repo.getUrl();
            if (url == null || url.isBlank()) {
                report.warning("`<repository>` with no `<url>` was skipped (id=" + id + ").");
                continue;
            }
            String name = (id == null || id.isBlank()) ? "repo" + (deduped.size() + 1) : id;
            if (name.equals("central") || RepoModelResolver.isCentral(url)) continue;
            try {
                deduped.put(name, new RepositorySpec(name, new URI(url.trim())));
            } catch (URISyntaxException e) {
                report.warning("`<repository><url>" + url + "</url></repository>` is not a valid URI; skipped.");
            }
        }
        return new ArrayList<>(deduped.values());
    }

    // --- unsupported-section warnings ---------------------------------------

    private static void warnUnsupportedSections(
            EffectiveModel em, ImportReport.Builder report, boolean isWorkspaceRoot) {
        Model model = em.model();
        for (Profile profile : em.raw().getProfiles()) {
            ProfileChecklist.report(profile, em.isActive(profile), report);
        }
        if (!isWorkspaceRoot && !model.getModules().isEmpty()) {
            // The workspace-import path already converted these; warn only for the single-POM path.
            report.warning("`<modules>` block present but this import was run in single-POM mode."
                    + " Re-run as `jk import pom.xml` from the project root to materialise a workspace.");
        }
        for (Plugin plugin : PluginFacts.plugins(model)) {
            String artifactId = plugin.getArtifactId();
            if (artifactId == null || "maven-compiler-plugin".equals(artifactId)) continue;
            report.warning("`<plugin>"
                    + artifactId
                    + "</plugin>` was not imported."
                    + " Plugin-aware mappings (Spotless, JaCoCo, Spring Boot, ...) arrive in slice D.");
        }
        Build build = model.getBuild();
        if (build != null && !build.getExtensions().isEmpty()) {
            report.error("`<build><extensions>` is not supported. Move build extensions to a custom"
                    + " jk task once tasks land.");
        }
    }
}
