// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.Pom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationOS;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;

/**
 * Where each Maven profile lands, by payload. A profile Maven activated on this machine is already
 * folded into the effective model, so the report only says so. Every other profile is mapped one
 * payload kind at a time, each with its own report row naming the profile: dependencies become a
 * {@code [features.<id>]} of optional deps, compiler settings a {@code [profiles.<id>]}, repositories
 * merge into the top-level list, plugins stay a hand-port checklist, and per-platform dependencies
 * are a proposal for a {@code [variants]} dimension.
 */
final class ProfileMapping {

    /** What the inactive profiles contribute to the build. */
    record Mapped(
            Map<Scope, List<Dependency>> optionalDeps,
            Features features,
            List<CompilerProfile> profiles,
            List<Repository> repositories) {}

    /** A {@code [profiles.<id>]} in the making: the javac and JVM arguments one Maven profile carried. */
    record CompilerProfile(String id, List<String> javac, List<String> jvmArgs) {}

    private static final String[] COMPILER_LEVEL_PROPERTIES = {
        "maven.compiler.release", "maven.compiler.target", "maven.compiler.source"
    };

    private final EffectiveModel em;
    private final ImportReport.Builder report;
    private final Map<Scope, List<Dependency>> optionalDeps = new EnumMap<>(Scope.class);
    private final Map<String, Feature> features = new LinkedHashMap<>();
    private final List<CompilerProfile> profiles = new ArrayList<>();
    private final List<Repository> repositories = new ArrayList<>();

    private ProfileMapping(EffectiveModel em, ImportReport.Builder report) {
        this.em = em;
        this.report = report;
    }

    static Mapped map(EffectiveModel em, ImportReport.Builder report) {
        ProfileMapping mapping = new ProfileMapping(em, report);
        for (Profile profile : em.raw().getProfiles()) {
            if (em.isActive(profile)) {
                mapping.reportActive(profile);
            } else {
                mapping.mapInactive(em.interpolated(profile));
            }
        }
        return new Mapped(
                mapping.optionalDeps,
                new Features(mapping.features, List.of()),
                mapping.profiles,
                mapping.repositories);
    }

    private static String label(Profile profile) {
        String id = profile.getId();
        return id == null || id.isBlank() ? "<unnamed>" : id;
    }

    private void row(Profile profile, String text) {
        String activation = describeActivation(profile.getActivation());
        report.warning("Maven profile `" + label(profile) + "`" + (activation == null ? "" : " (" + activation + ")")
                + ": " + text);
    }

    // --- active ---------------------------------------------------------------

    private void reportActive(Profile profile) {
        List<String> parts = new ArrayList<>();
        int deps = profile.getDependencies().size();
        if (deps > 0) parts.add(count(deps, "dependency", "dependencies"));
        int managed = managedCount(profile.getDependencyManagement());
        if (managed > 0) parts.add(count(managed, "dependencyManagement entry", "dependencyManagement entries"));
        if (!profile.getProperties().isEmpty()) {
            parts.add("properties=[" + String.join(",", profile.getProperties().stringPropertyNames()) + "]");
        }
        List<String> plugins = pluginIds(profile.getBuild());
        if (!plugins.isEmpty()) parts.add("plugins=[" + String.join(",", plugins) + "]");
        if (parts.isEmpty()) parts.add("no payload");
        row(profile, "active on this machine and folded into the import: " + String.join("; ", parts) + ".");
    }

    // --- inactive, by payload ---------------------------------------------------

    private void mapInactive(Profile profile) {
        boolean any = false;
        any |= mapDependencies(profile);
        any |= mapCompiler(profile);
        any |= mapRepositories(profile);
        any |= reportPlugins(profile);
        if (!any) {
            row(profile, "no convertible payload; dropped.");
        }
    }

    /**
     * {@code <dependencies>} → {@code [features.<id>]}: each dep goes to its scope marked optional,
     * the feature lists their handles, and {@code default} leaves it off. Per-platform deps under an
     * OS or JDK activation are a variants proposal instead — which product you build, not an
     * optional library.
     */
    private boolean mapDependencies(Profile profile) {
        List<Pom.Dep> deps = new ArrayList<>();
        for (var d : profile.getDependencies()) {
            if (d.getGroupId() == null || d.getArtifactId() == null) continue;
            deps.add(managedVersion(EffectiveModel.toDep(d), profile.getDependencyManagement()));
        }
        int managed = managedCount(profile.getDependencyManagement());
        if (deps.isEmpty()) {
            if (managed == 0) return false;
            row(
                    profile,
                    count(managed, "dependencyManagement entry", "dependencyManagement entries")
                            + " and no dependency of its own; nothing to write"
                            + " (inline the versions on the matching `<dependency>` or use a BOM import).");
            return true;
        }
        if (isPlatformConditional(profile, deps)) {
            row(
                    profile,
                    "per-platform dependencies " + modules(deps)
                            + " → declare a `[variants]` dimension for the platform axis; nothing written.");
            return true;
        }
        String id = label(profile);
        List<String> handles = new ArrayList<>();
        for (Pom.Dep dep : deps) {
            DependencyMapping.warnUnresolvedVersion(dep, report);
            Dependency d = DependencyMapping.toDependency(dep).withOptional(true);
            Scope scope = DependencyMapping.scope(dep.scope());
            if (d.isTestsKind() && scope != Scope.TEST && scope != Scope.TEST_DEV) scope = Scope.TEST;
            optionalDeps.computeIfAbsent(scope, s -> new ArrayList<>()).add(d);
            handles.add(d.library());
        }
        features.put(id, new Feature(id, handles, List.of()));
        row(
                profile,
                count(deps.size(), "dependency", "dependencies") + " → `[features." + id
                        + "]` (optional deps " + String.join(", ", handles) + "; not in `default`, activate with"
                        + " `--features " + id + "`).");
        return true;
    }

    /**
     * The version Maven would give a profile dependency declared without one: the profile's own
     * {@code <dependencyManagement>} first, then the POM's effective table (parents flattened, BOM
     * imports inlined), which is what governs the dependency once the profile is active.
     */
    private Pom.Dep managedVersion(Pom.Dep dep, @Nullable DependencyManagement profileManagement) {
        if (PluginFacts.usable(dep.version()) != null) return dep;
        String version = versionManagedBy(dep, profileManagement);
        if (version == null) version = versionManagedBy(dep, em.model().getDependencyManagement());
        if (version == null) return dep;
        return new Pom.Dep(
                dep.groupId(),
                dep.artifactId(),
                version,
                dep.scope(),
                dep.optional(),
                dep.classifier(),
                dep.type(),
                dep.exclusions());
    }

    private static @Nullable String versionManagedBy(Pom.Dep dep, @Nullable DependencyManagement dm) {
        if (dm == null) return null;
        for (var m : dm.getDependencies()) {
            if (dep.groupId().equals(m.getGroupId())
                    && dep.artifactId().equals(m.getArtifactId())
                    && PluginFacts.usable(m.getVersion()) != null) {
                return m.getVersion();
            }
        }
        return null;
    }

    private static boolean isPlatformConditional(Profile profile, List<Pom.Dep> deps) {
        Activation activation = profile.getActivation();
        if (activation == null) return false;
        boolean platform = activation.getOs() != null || PluginFacts.usable(activation.getJdk()) != null;
        return platform && deps.stream().anyMatch(d -> PluginFacts.usable(d.classifier()) != null);
    }

    /**
     * {@code maven.compiler.*} properties and the compiler plugin's {@code <compilerArgs>} →
     * {@code [profiles.<id>] javac}; an {@code argLine} property → {@code jvm-args}. A level below
     * jk's floor is raised the same way the module's own is.
     */
    private boolean mapCompiler(Profile profile) {
        List<String> javac = new ArrayList<>();
        Properties props = profile.getProperties();
        for (String key : COMPILER_LEVEL_PROPERTIES) {
            Optional<Integer> level = PluginFacts.javaLevel(props.getProperty(key));
            if (level.isPresent()) {
                javac.add("--release");
                javac.add(Integer.toString(Math.max(PluginFacts.JAVA_FLOOR, level.get())));
                break;
            }
        }
        compilerPlugin(profile.getBuild()).ifPresent(compiler -> {
            for (Xpp3Dom config : PluginFacts.configurations(compiler)) {
                PluginFacts.collectCompilerArgs(config, javac);
                PluginFacts.collectCompilerSwitches(config, javac);
            }
        });
        List<String> jvm = new ArrayList<>();
        String argLine = PluginFacts.usable(props.getProperty("argLine"));
        if (argLine != null) jvm.addAll(List.of(argLine.trim().split("\\s+")));
        if (javac.isEmpty() && jvm.isEmpty()) return false;
        String id = label(profile);
        profiles.add(new CompilerProfile(id, javac, jvm));
        List<String> keys = new ArrayList<>();
        if (!javac.isEmpty()) keys.add("`javac`");
        if (!jvm.isEmpty()) keys.add("`jvm-args`");
        row(
                profile,
                "compiler settings → `[profiles." + id + "]` " + String.join(" and ", keys)
                        + "; select with `--profile " + id + "`.");
        return true;
    }

    private static Optional<Plugin> compilerPlugin(@Nullable BuildBase build) {
        if (build == null) return Optional.empty();
        List<Plugin> plugins = new ArrayList<>(build.getPlugins());
        if (build.getPluginManagement() != null)
            plugins.addAll(build.getPluginManagement().getPlugins());
        return plugins.stream()
                .filter(p -> "maven-compiler-plugin".equals(p.getArtifactId()))
                .findFirst();
    }

    /** {@code <repositories>} → the top-level list; a repository is never conditional in jk. */
    private boolean mapRepositories(Profile profile) {
        if (profile.getRepositories().isEmpty()) return false;
        List<String> ids = new ArrayList<>();
        for (Repository repo : profile.getRepositories()) {
            repositories.add(repo);
            ids.add(repo.getId() == null ? repo.getUrl() : repo.getId());
        }
        row(
                profile,
                "`<repositories>` " + String.join(", ", ids)
                        + " → merged into `[repositories]`; a repository is never conditional in jk.");
        return true;
    }

    /**
     * {@code <build><plugins>} stay a checklist: the plugin table says where each lands, a profile
     * does not move it. A {@code <pluginManagement>} entry carrying executions is on the list too:
     * it is what binds a plugin the POM declares bare, so the profile is where that plugin runs.
     */
    private boolean reportPlugins(Profile profile) {
        List<String> plugins = pluginIds(profile.getBuild());
        BuildBase build = profile.getBuild();
        if (build != null && build.getPluginManagement() != null) {
            for (Plugin managed : build.getPluginManagement().getPlugins()) {
                String artifactId = managed.getArtifactId();
                if (artifactId == null
                        || artifactId.isBlank()
                        || managed.getExecutions().isEmpty()) continue;
                if (!plugins.contains(artifactId)) plugins.add(artifactId);
            }
        }
        plugins.remove("maven-compiler-plugin");
        if (plugins.isEmpty()) return false;
        row(
                profile,
                "plugins=[" + String.join(",", plugins)
                        + "] — port by hand; docs/user/migration.md lists where each plugin lands.");
        return true;
    }

    // --- helpers ------------------------------------------------------------------

    private static String modules(List<Pom.Dep> deps) {
        List<String> names = new ArrayList<>();
        for (Pom.Dep d : deps) {
            names.add(d.module() + (PluginFacts.usable(d.classifier()) == null ? "" : ":" + d.classifier()));
        }
        return String.join(", ", names);
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    private static int managedCount(@Nullable DependencyManagement dm) {
        return dm == null ? 0 : dm.getDependencies().size();
    }

    private static List<String> pluginIds(@Nullable BuildBase build) {
        List<String> ids = new ArrayList<>();
        if (build == null) return ids;
        for (Plugin plugin : build.getPlugins()) {
            String artifactId = plugin.getArtifactId();
            if (artifactId != null && !artifactId.isBlank()) ids.add(artifactId);
        }
        return ids;
    }

    private static @Nullable String describeActivation(@Nullable Activation activation) {
        if (activation == null) return null;
        List<String> kinds = new ArrayList<>();
        if (activation.isActiveByDefault()) kinds.add("activeByDefault");
        String jdk = activation.getJdk();
        if (jdk != null && !jdk.isBlank()) kinds.add("jdk=" + jdk);
        ActivationOS os = activation.getOs();
        if (os != null) {
            String family = os.getFamily();
            String name = os.getName();
            kinds.add("os=" + (family != null ? family : name != null ? name : "?"));
        }
        ActivationProperty property = activation.getProperty();
        if (property != null) {
            String name = property.getName();
            kinds.add("property=" + (name != null ? name : "?") + ", a command-line switch jk has no equivalent for");
        }
        if (activation.getFile() != null) {
            kinds.add("file-existence, which jk has no equivalent for");
        }
        return kinds.isEmpty() ? null : "activation " + String.join("+", kinds);
    }
}
