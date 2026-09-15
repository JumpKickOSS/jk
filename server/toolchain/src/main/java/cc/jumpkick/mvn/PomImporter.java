// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static cc.jumpkick.host.DomXml.childElement;
import static cc.jumpkick.host.DomXml.childElements;
import static cc.jumpkick.host.DomXml.childText;

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
import cc.jumpkick.repo.Pom.Parent;
import cc.jumpkick.repo.PomParseException;
import cc.jumpkick.repo.PomParser;
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
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Converts a Maven {@code pom.xml} into a {@link JkBuild} plus an {@link ImportReport} of
 * unfaithful constructs. Maps coords, scoped deps (bare versions → exact pins), BOM imports,
 * repositories, and compiler release; other POM features land in the report.
 */
public final class PomImporter {

    public record Result(JkBuild jkBuild, ImportReport report) {}

    /**
     * Outcome of importing a multi-module POM tree: the root's {@link JkBuild} (carrying the
     * workspace block), each module's {@link JkBuild} keyed by the relative module path, and one
     * aggregated {@link ImportReport} spanning the root + every child.
     */
    public record WorkspaceImportResult(JkBuild root, Map<String, JkBuild> modules, ImportReport report) {}

    private PomImporter() {}

    public static Result importFrom(Path pomXml) throws IOException {
        return importFromBytes(Files.readAllBytes(pomXml));
    }

    public static Result importFromBytes(byte[] xml) {
        return importFromBytes(xml, null);
    }

    private static Result importFromBytes(byte[] xml, @Nullable Parent suppressParentMatching) {
        Document doc = PomParser.parseXml(xml);
        Pom pom = PomParser.parse(doc);
        ImportReport.Builder report = ImportReport.builder();

        Project project = mapProject(pom, doc, report, suppressParentMatching);
        Map<Scope, List<Dependency>> byScope = mapDependencies(pom, report);
        List<RepositorySpec> repos = mapRepositories(doc, report);
        warnUnsupportedSections(doc, report, /* isWorkspaceRoot= */ false);

        JkBuild.Dependencies dependencies = new JkBuild.Dependencies(byScope);
        String mainClass = mainClassFromPom(doc);
        JkBuild.Application application = mainClass != null ? new JkBuild.Application(mainClass, false) : null;
        JkBuild jkBuild = JkBuild.builder(project)
                .dependencies(dependencies)
                .repositories(repos)
                .application(application)
                .build();
        Map<String, String> manifest = manifestFromPom(doc);
        if (!manifest.isEmpty()) jkBuild = jkBuild.withManifest(manifest);
        return new Result(jkBuild, report.build());
    }

    /**
     * Custom jar-manifest attributes from a build plugin's {@code <archive><manifestEntries>}
     * (maven-jar / assembly / shade). {@code Main-Class} is excluded — it routes to {@code
     * [application].main} via {@link #mainClassFromPom}. Unresolved {@code ${...}} property values and
     * blanks are skipped. Insertion order preserved.
     */
    private static Map<String, String> manifestFromPom(Document doc) {
        Map<String, String> attrs = new LinkedHashMap<>();
        NodeList entries = doc.getElementsByTagName("manifestEntries");
        for (int i = 0; i < entries.getLength(); i++) {
            if (!(entries.item(i) instanceof Element entriesEl)) continue;
            for (Element child : childElements(entriesEl)) {
                String name = child.getTagName();
                String value = child.getTextContent();
                if (name == null || name.isBlank() || value == null) continue;
                value = value.trim();
                if (value.isEmpty() || value.startsWith("${")) continue;
                if (name.equalsIgnoreCase("Main-Class")) continue; // routed to [application].main
                attrs.put(name, value);
            }
        }
        return attrs;
    }

    /**
     * Import a multi-module Maven build into a workspace-root {@code JkBuild} plus per-module
     * builds. No {@code <modules>} → single-POM import.
     */
    public static WorkspaceImportResult importWorkspace(Path rootPom) throws IOException {
        byte[] rootXml = Files.readAllBytes(rootPom);
        Document rootDoc = PomParser.parseXml(rootXml);
        List<String> modules = readModules(rootDoc);
        if (modules.isEmpty()) {
            Result single = importFromBytes(rootXml);
            return new WorkspaceImportResult(single.jkBuild(), Map.of(), single.report());
        }

        ImportReport.Builder report = ImportReport.builder();
        Pom rootPomParsed = PomParser.parse(rootDoc);
        Project rootProject = mapProject(rootPomParsed, rootDoc, report, null);
        // Root coords serve as the "expected parent" for children.
        Pom.Parent expectedParent =
                new Pom.Parent(rootProject.group(), rootPomParsed.artifactId(), rootProject.version());
        warnUnsupportedSections(rootDoc, report, /* isWorkspaceRoot= */ true);

        Workspace workspace = new Workspace(modules);
        String rootMainClass = mainClassFromPom(rootDoc);
        JkBuild.Application rootApplication =
                rootMainClass != null ? new JkBuild.Application(rootMainClass, false) : null;
        // The workspace root is a coordination point — no deps of its own.
        JkBuild rootJkBuild = JkBuild.builder(rootProject)
                .workspace(workspace)
                .application(rootApplication)
                .build();

        Map<String, JkBuild> moduleBuilds = new LinkedHashMap<>();
        Path projectDir = Objects.requireNonNull(rootPom.toAbsolutePath().getParent());
        for (String module : modules) {
            Path childPom = projectDir.resolve(module).resolve("pom.xml");
            if (!Files.exists(childPom)) {
                report.error("workspace module `" + module + "` has no pom.xml at " + childPom);
                continue;
            }
            byte[] childXml = Files.readAllBytes(childPom);
            Result childResult = importFromBytes(childXml, expectedParent);
            moduleBuilds.put(module, childResult.jkBuild());
            for (ImportReport.Issue issue : childResult.report().issues()) {
                String prefixed = "[" + module + "] " + issue.message();
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

    private static List<String> readModules(Document doc) {
        Element modules = childElement(doc.getDocumentElement(), "modules");
        if (modules == null) return List.of();
        List<String> result = new ArrayList<>();
        for (Element m : childElements(modules, "module")) {
            String text = m.getTextContent().trim();
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    // --- project ------------------------------------------------------------

    private static Project mapProject(
            Pom pom, Document doc, ImportReport.Builder report, @Nullable Parent suppressParentMatching) {
        String group = pom.groupId();
        String version = pom.version();
        if (pom.parent() != null) {
            if (group == null) group = pom.parent().groupId();
            if (version == null) version = pom.parent().version();
            boolean isWorkspaceParent = suppressParentMatching != null
                    && pom.parent().groupId().equals(suppressParentMatching.groupId())
                    && pom.parent().artifactId().equals(suppressParentMatching.artifactId())
                    && pom.parent().version().equals(suppressParentMatching.version());
            if (!isWorkspaceParent) {
                report.warning("`<parent>` was referenced ("
                        + pom.parent().groupId()
                        + ":"
                        + pom.parent().artifactId()
                        + ":"
                        + pom.parent().version()
                        + ") but jk-import did not flatten its dependencyManagement / properties / build config."
                        + " Run `mvn help:effective-pom` and re-import if any dependency versions are unresolved.");
            }
        }
        if (group == null || group.isBlank()) {
            throw new PomParseException("POM has no <groupId> and no <parent><groupId>");
        }
        if (version == null || version.isBlank()) {
            throw new PomParseException("POM has no <version> and no <parent><version>");
        }
        int jdk = jdkFromCompilerPlugin(doc).flatMap(PomImporter::parseInt).orElse(25);
        String description = childText(doc.getDocumentElement(), "description");
        if (description != null && description.isBlank()) description = null;
        VersionSelector kotlin = kotlinFromPom(doc, report);
        // A Kotlin project sets `kotlin` and leaves `java` at 0 (mutually exclusive).
        int java = kotlin != null ? 0 : jdk;
        return Project.builder(group, pom.artifactId(), version)
                .jdkMajor(jdk)
                .java(java)
                .kotlin(kotlin)
                .description(description)
                .build();
    }

    /**
     * Detect the Kotlin compiler version from the {@code kotlin-maven-plugin}. The version comes from
     * the plugin's {@code <version>} (resolving a {@code ${kotlin.version}} placeholder against
     * {@code <properties>}), else the {@code kotlin.version} property, else a floating {@link
     * KotlinResolver#DEFAULT_VERSION} (pinned later by {@code jk lock}). Returns {@code null} when
     * the plugin is absent (a Java project).
     */
    private static @Nullable VersionSelector kotlinFromPom(Document doc, ImportReport.Builder report) {
        Element root = doc.getDocumentElement();
        Element properties = childElement(root, "properties");
        String propVersion = null;
        if (properties != null) {
            for (String key : new String[] {"kotlin.version", "kotlin.compiler.version"}) {
                String v = childText(properties, key);
                if (v != null && !v.isBlank()) {
                    propVersion = v.trim();
                    break;
                }
            }
        }
        Element plugins = childElement(childElement(root, "build"), "plugins");
        boolean present = false;
        String pluginVersion = null;
        if (plugins != null) {
            for (Element plugin : childElements(plugins, "plugin")) {
                if (!"kotlin-maven-plugin".equals(childText(plugin, "artifactId"))) continue;
                present = true;
                String v = childText(plugin, "version");
                if (v != null && !v.isBlank()) pluginVersion = v.trim();
            }
        }
        if (!present) return null; // only the plugin marks a Kotlin project
        String resolved = (pluginVersion != null && !pluginVersion.startsWith("${")) ? pluginVersion : propVersion;
        if (resolved == null || resolved.isBlank()) {
            report.warning("kotlin-maven-plugin recognised without a resolvable version; project.kotlin"
                    + " is `latest` — `jk lock` picks the current stable, then `jk update` moves it.");
            return VersionSelector.parse("latest");
        }
        return VersionSelector.parse(resolved);
    }

    /**
     * Best-effort application main class: the first non-placeholder {@code <mainClass>} element
     * (jar/assembly/shade/exec plugin configs), or a {@code start-class}/{@code
     * exec.mainClass}/{@code main.class} property.
     */
    private static @Nullable String mainClassFromPom(Document doc) {
        NodeList nodes = doc.getElementsByTagName("mainClass");
        for (int i = 0; i < nodes.getLength(); i++) {
            String v = nodes.item(i).getTextContent();
            if (v != null && !v.isBlank() && !v.trim().startsWith("${")) return v.trim();
        }
        Element properties = childElement(doc.getDocumentElement(), "properties");
        if (properties != null) {
            for (String key : new String[] {"start-class", "exec.mainClass", "main.class", "mainClass"}) {
                String v = childText(properties, key);
                if (v != null && !v.isBlank() && !v.trim().startsWith("${")) return v.trim();
            }
        }
        return null;
    }

    private static Optional<Integer> parseInt(@Nullable String s) {
        if (s == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> jdkFromCompilerPlugin(Document doc) {
        Element root = doc.getDocumentElement();
        // First check properties: maven.compiler.release / .source / .target.
        Element properties = childElement(root, "properties");
        if (properties != null) {
            for (String key :
                    new String[] {"maven.compiler.release", "maven.compiler.target", "maven.compiler.source"}) {
                String value = childText(properties, key);
                if (value != null && !value.isBlank()) return Optional.of(value.trim());
            }
        }
        // Then check the maven-compiler-plugin <configuration>.
        Element build = childElement(root, "build");
        Element plugins = childElement(build, "plugins");
        if (plugins != null) {
            for (Element plugin : childElements(plugins, "plugin")) {
                String artifactId = childText(plugin, "artifactId");
                if (!"maven-compiler-plugin".equals(artifactId)) continue;
                Element config = childElement(plugin, "configuration");
                if (config == null) continue;
                for (String key : new String[] {"release", "target", "source"}) {
                    String value = childText(config, key);
                    if (value != null && !value.isBlank()) return Optional.of(value.trim());
                }
            }
        }
        return Optional.empty();
    }

    // --- dependencies -------------------------------------------------------

    private static Map<Scope, List<Dependency>> mapDependencies(Pom pom, ImportReport.Builder report) {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        // Standard scopes.
        for (Pom.Dep dep : pom.dependencies()) {
            if ("system".equalsIgnoreCase(dep.scope())) {
                report.error("`<dependency>` with `<scope>system</scope>` is rejected ("
                        + dep.module()
                        + "). Move it to a git dependency or a local repository.");
                continue;
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
            if (dep.version() == null || dep.version().isBlank()) {
                report.warning("`<dependency>` "
                        + dep.module()
                        + " has no resolved `<version>`; jk wrote `=unresolved`."
                        + " Run `mvn help:effective-pom` and re-import.");
            }
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
        // dependencyManagement: BOM imports → PLATFORM; bare version pins → warning.
        for (Pom.Dep managed : pom.managedDependencies()) {
            if ("import".equalsIgnoreCase(managed.scope()) && "pom".equalsIgnoreCase(managed.type())) {
                byScope.computeIfAbsent(Scope.PLATFORM, s -> new ArrayList<>()).add(toDependency(managed));
            } else {
                report.warning("`<dependencyManagement>` entry "
                        + managed.module()
                        + " is a version pin,"
                        + " not a BOM import. jk has no equivalent; the pin was dropped."
                        + " Inline the version on the matching `<dependency>` instead.");
            }
        }
        uniquifyHandles(byScope, report);
        return byScope;
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
        String version = dep.version();
        if (version == null || version.isBlank()) {
            // PomParser will warn via the report; emit a marker so the file
            // still parses round-tripped.
            version = "unresolved";
        }
        // Bare versions are exact pins, matching Maven semantics.
        VersionSelector selector = VersionSelector.parse(version);
        // Maven coordinates have no notion of a manifest "short name"; default
        // the v0.7 `name` field to the artifactId, matching the manifest's
        // own `artifact`-defaults-to-key rule. The test-jar package gets a
        // distinct `-tests` handle so a POM depending on both the jar and the
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

    private static List<RepositorySpec> mapRepositories(Document doc, ImportReport.Builder report) {
        Element root = doc.getDocumentElement();
        Element repos = childElement(root, "repositories");
        if (repos == null) return List.of();
        Map<String, RepositorySpec> deduped = new LinkedHashMap<>();
        for (Element repo : childElements(repos, "repository")) {
            String id = childText(repo, "id");
            String url = childText(repo, "url");
            if (url == null || url.isBlank()) {
                report.warning("`<repository>` with no `<url>` was skipped (id=" + id + ").");
                continue;
            }
            String name = (id == null || id.isBlank()) ? "repo" + (deduped.size() + 1) : id;
            // Central is the implicit default — skip duplicate declarations of it.
            if (name.equals("central")
                    || url.startsWith("https://repo.maven.apache.org/")
                    || url.startsWith("https://repo1.maven.org/")) {
                continue;
            }
            try {
                deduped.put(name, new RepositorySpec(name, new URI(url.trim())));
            } catch (URISyntaxException e) {
                report.warning("`<repository><url>" + url + "</url></repository>` is not a valid URI; skipped.");
            }
        }
        return new ArrayList<>(deduped.values());
    }

    // --- unsupported-section warnings ---------------------------------------

    private static void warnUnsupportedSections(Document doc, ImportReport.Builder report, boolean isWorkspaceRoot) {
        Element root = doc.getDocumentElement();
        Element profiles = childElement(root, "profiles");
        if (profiles != null) {
            for (Element profile : childElements(profiles, "profile")) {
                analyzeProfile(profile, report);
            }
        }
        if (!isWorkspaceRoot && childElement(root, "modules") != null) {
            // The workspace-import path already converted these; warn only for the single-POM path.
            report.warning("`<modules>` block present but this import was run in single-POM mode."
                    + " Re-run as `jk import pom.xml` from the project root to materialise a workspace.");
        }
        Element build = childElement(root, "build");
        Element plugins = childElement(build, "plugins");
        if (plugins != null) {
            for (Element plugin : childElements(plugins, "plugin")) {
                String artifactId = childText(plugin, "artifactId");
                if (artifactId == null || "maven-compiler-plugin".equals(artifactId)) continue;
                report.warning("`<plugin>"
                        + artifactId
                        + "</plugin>` was not imported."
                        + " Plugin-aware mappings (Spotless, JaCoCo, Spring Boot, ...) arrive in slice D.");
            }
        }
        Element extensions = childElement(build, "extensions");
        if (extensions != null && !childElements(extensions).isEmpty()) {
            report.error("`<build><extensions>` is not supported. Move build extensions to a custom"
                    + " jk task once tasks land.");
        }
    }

    // --- profile analysis ---------------------------------------------------

    /**
     * Emits per-profile diagnostics describing what was inside a Maven {@code <profile>}. jk's
     * current Profile model carries only javac/JVM args, so faithful mapping of property/dep/plugin
     * profiles is not yet possible — instead we give the user a precise checklist of items to port by
     * hand.
     */
    private static void analyzeProfile(Element profile, ImportReport.Builder report) {
        String id = childText(profile, "id");
        String label = id == null || id.isBlank() ? "<unnamed>" : id;
        StringBuilder summary =
                new StringBuilder("Maven profile `").append(label).append("`: ");
        List<String> parts = new ArrayList<>();

        String activation = describeActivation(childElement(profile, "activation"));
        if (activation != null) parts.add(activation);

        Element deps = childElement(profile, "dependencies");
        if (deps != null) {
            int count = childElements(deps, "dependency").size();
            if (count > 0) {
                parts.add(count
                        + " dependenc"
                        + (count == 1 ? "y" : "ies")
                        + " (convert to a jk feature `"
                        + label
                        + "` if opt-in, or move into the main deps list)");
            }
        }
        Element managed = childElement(childElement(profile, "dependencyManagement"), "dependencies");
        if (managed != null && !childElements(managed, "dependency").isEmpty()) {
            int count = childElements(managed, "dependency").size();
            parts.add(count
                    + " dependencyManagement entr"
                    + (count == 1 ? "y" : "ies")
                    + " (inline versions on the matching `<dependency>` or use a BOM import)");
        }
        Element properties = childElement(profile, "properties");
        if (properties != null) {
            List<Element> propEntries = childElements(properties);
            if (!propEntries.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Element p : propEntries) names.add(p.getNodeName());
                parts.add("properties=["
                        + String.join(",", names)
                        + "]"
                        + " (no jk equivalent — fold maven.compiler.* into project.jdk; drop the rest)");
            }
        }
        Element buildPlugins = childElement(childElement(profile, "build"), "plugins");
        if (buildPlugins != null) {
            List<String> pluginIds = new ArrayList<>();
            for (Element plugin : childElements(buildPlugins, "plugin")) {
                String artifactId = childText(plugin, "artifactId");
                if (artifactId != null && !artifactId.isBlank()) pluginIds.add(artifactId);
            }
            if (!pluginIds.isEmpty()) {
                parts.add("plugins=[" + String.join(",", pluginIds) + "] (plugin mapping is not yet implemented)");
            }
        }
        Element repos = childElement(profile, "repositories");
        if (repos != null && !childElements(repos, "repository").isEmpty()) {
            parts.add("repositories declared (move into the top-level `repositories` block)");
        }

        if (parts.isEmpty()) {
            // Profile with only an activation — name it so the user knows it's gone.
            parts.add("contained no convertible payload; dropped");
        }
        summary.append(String.join("; ", parts)).append('.');
        report.warning(summary.toString());
    }

    private static @Nullable String describeActivation(@Nullable Element activation) {
        if (activation == null) return null;
        List<String> kinds = new ArrayList<>();
        if ("true".equalsIgnoreCase(childText(activation, "activeByDefault"))) {
            kinds.add("activeByDefault");
        }
        String jdk = childText(activation, "jdk");
        if (jdk != null && !jdk.isBlank()) kinds.add("jdk=" + jdk);
        Element os = childElement(activation, "os");
        if (os != null) {
            String family = childText(os, "family");
            String name = childText(os, "name");
            kinds.add("os="
                    + (family != null ? family : name != null ? name : "?")
                    + " (use jk target predicates per dep)");
        }
        Element property = childElement(activation, "property");
        if (property != null) {
            String name = childText(property, "name");
            kinds.add("property="
                    + (name != null ? name : "?")
                    + " (no jk equivalent — replace with an explicit jk profile or feature)");
        }
        Element file = childElement(activation, "file");
        if (file != null) {
            kinds.add("file-existence (jk has no equivalent — refactor to a jk profile)");
        }
        return kinds.isEmpty() ? null : "activation=" + String.join("+", kinds);
    }
}
