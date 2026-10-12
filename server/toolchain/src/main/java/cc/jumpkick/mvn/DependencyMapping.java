// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.MavenMetaversion;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.resolver.TestEngines;
import cc.jumpkick.version.Versions;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How one Maven dependency becomes a jk one: the scope name, the {@code library} handle, the exact
 * pin, the classifier, and what its {@code <type>} means. Shared by the POM's own dependency list
 * and the optional deps a profile contributes, so both spell a coordinate the same way.
 */
final class DependencyMapping {

    /** The version literal written when nothing in the chain supplies one; the report names the dependency. */
    static final String UNRESOLVED = "unresolved";

    /** Maven types whose artifact is the module's plain jar. */
    private static final Set<String> JAR_TYPES = Set.of("jar", "bundle", "ejb", "maven-plugin");

    /** Maven types that name a classified jar of the module: the type and the classifier it stands for. */
    private static final Map<String, String> CLASSIFIED_TYPES =
            Map.of("ejb-client", "client", "java-source", "sources", "javadoc", "javadoc");

    private DependencyMapping() {}

    /** A mapped dependency and the scope it lands in. */
    record Mapped(Scope scope, Dependency dependency) {}

    /**
     * One Maven dependency as the manifest carries it, every rule applied: {@code system} scope
     * and a type jk cannot spell are refused with a row, a {@code pom} type lands in {@code
     * [platform-dependencies]}, exclusions become the entry's {@code exclude} list, a test-jar
     * moves to {@code [test-dependencies]}, and a test framework below its engine's floor is
     * raised. Empty when the dependency is refused.
     */
    static Optional<Mapped> map(Pom.Dep dep, ImportReport.Builder report) {
        if ("system".equalsIgnoreCase(dep.scope())) {
            report.error("`<dependency>` with `<scope>system</scope>` is rejected ("
                    + dep.module()
                    + "). Move it to a git dependency or a local repository.");
            return Optional.empty();
        }
        String unmappedType = unmappedType(dep);
        if (unmappedType != null) {
            report.error("`<type>" + unmappedType + "</type>` on " + dep.module()
                    + " names an artifact jk has no manifest spelling for; the dependency was not written."
                    + " A jar of the same module is `{ group, name, version }`, a classified jar adds"
                    + " `classifier`.");
            return Optional.empty();
        }
        warnUnresolvedVersion(dep, report);
        warnMetaversion(dep, report);
        Scope scope = scope(dep.scope());
        if (isPom(dep)) {
            report.warning("`<type>pom</type>` on " + dep.module()
                    + " is written to [platform-dependencies]: its dependencyManagement governs versions, and"
                    + " the libraries it lists are not on the classpath — declare the ones the code uses.");
            scope = Scope.PLATFORM;
        }
        Dependency d = raiseToEngineFloor(toDependency(dep), scope, report);
        // A BOM import governs versions, not a classpath subtree; its exclusions have nothing to prune.
        if (scope != Scope.PLATFORM) d = ExclusionMapping.apply(d, dep, report);
        // kind=tests is only legal under [test-dependencies]/[test-dev-dependencies], so a test-jar
        // declared in another Maven scope moves to TEST or the written jk.toml rejects its own `jk lock`.
        if (d.isTestsKind() && scope != Scope.TEST && scope != Scope.TEST_DEV) {
            report.warning("`<type>test-jar</type>` on "
                    + dep.module()
                    + " is in Maven scope `"
                    + (dep.scope() == null || dep.scope().isBlank() ? "compile" : dep.scope())
                    + "`; jk models test-jar deps as kind=tests, which is only legal in test"
                    + " scopes — moved to [test-dependencies].");
            scope = Scope.TEST;
        }
        return Optional.of(new Mapped(scope, d));
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

    static Scope scope(@Nullable String mavenScope) {
        if (mavenScope == null || mavenScope.isBlank() || "compile".equalsIgnoreCase(mavenScope)) {
            return Scope.MAIN;
        }
        return switch (mavenScope.toLowerCase(Locale.ROOT)) {
            case "runtime" -> Scope.RUNTIME;
            case "provided" -> Scope.PROVIDED;
            case "test" -> Scope.TEST;
            // system is rejected before this is reached.
            default -> Scope.MAIN;
        };
    }

    /**
     * The jk dependency for a Maven one. Bare versions are exact pins, matching Maven. The handle
     * defaults to the artifactId (the manifest's own {@code artifact}-defaults-to-key rule); a
     * test-jar gets a distinct {@code -tests} handle and {@code kind = "tests"}, and a classified
     * artifact a {@code -<classifier>} handle and {@code classifier = "…"}, so a POM depending on
     * the jar, the test-jar and a native classifier of one GA keeps every entry.
     */
    static Dependency toDependency(Pom.Dep dep) {
        String version = PluginFacts.usable(dep.version());
        if (version == null) version = UNRESOLVED;
        if (MavenMetaversion.of(version) != null) version = "latest";
        VersionSelector selector = VersionSelector.parse(version);
        if (isTestJar(dep)) {
            return Dependency.of(dep.artifactId() + "-tests", dep.module(), selector)
                    .withKind(DependencyKind.TESTS)
                    .withOptional(dep.optional());
        }
        String classifier = classifier(dep);
        if (classifier == null) {
            return Dependency.of(dep.artifactId(), dep.module(), selector).withOptional(dep.optional());
        }
        return Dependency.of(dep.artifactId() + "-" + classifier, dep.module(), selector)
                .withClassifier(classifier)
                .withOptional(dep.optional());
    }

    /**
     * The classifier the edge carries: the declared {@code <classifier>} (already valued from the
     * host when the POM spelled it with {@code ${os.detected.classifier}}), else the one the type
     * implies ({@code ejb-client} is the {@code client} jar). {@code null} for the plain jar.
     */
    static @Nullable String classifier(Pom.Dep dep) {
        String declared = PluginFacts.usable(dep.classifier());
        if (declared != null) return declared;
        String type = type(dep);
        return type == null ? null : CLASSIFIED_TYPES.get(type);
    }

    /** Maven {@code <type>pom</type>}: the dependency is a BOM, {@code [platform-dependencies]} in jk. */
    static boolean isPom(Pom.Dep dep) {
        return "pom".equals(type(dep));
    }

    /**
     * A {@code <type>} jk has no spelling for ({@code aar}, {@code war}, {@code zip}, …), as the POM
     * wrote it; {@code null} when the type is a jar, a classified jar, a test-jar or a pom.
     */
    static @Nullable String unmappedType(Pom.Dep dep) {
        String type = type(dep);
        if (type == null
                || JAR_TYPES.contains(type)
                || CLASSIFIED_TYPES.containsKey(type)
                || "test-jar".equals(type)
                || "pom".equals(type)) {
            return null;
        }
        return PluginFacts.usable(dep.type());
    }

    private static @Nullable String type(Pom.Dep dep) {
        String type = PluginFacts.usable(dep.type());
        return type == null ? null : type.toLowerCase(Locale.ROOT);
    }

    /**
     * Maven {@code <type>test-jar</type>} only. A bare {@code tests} classifier (default jar type)
     * is not kind=tests — classifiers are their own axis, and stamping kind on them produced
     * jk.toml that the parser rejects outside test scopes.
     */
    static boolean isTestJar(Pom.Dep dep) {
        return dep.type() != null && "test-jar".equalsIgnoreCase(dep.type());
    }

    /**
     * A {@code LATEST} or {@code RELEASE} version floats under Maven; the manifest spells that as the
     * {@code latest} selector, which the lock pins to a number.
     */
    static void warnMetaversion(Pom.Dep dep, ImportReport.Builder report) {
        String version = dep.version();
        MavenMetaversion meta = MavenMetaversion.of(version);
        if (meta == null || version == null) return;
        report.warning("`<version>" + version.trim() + "</version>` on " + dep.module()
                + " floats under Maven; written as the `latest` selector, so `jk lock` pins the newest"
                + (meta == MavenMetaversion.RELEASE ? " release" : " version")
                + " and `jk update` moves it.");
    }

    static void warnUnresolvedVersion(Pom.Dep dep, ImportReport.Builder report) {
        if (PluginFacts.usable(dep.version()) != null) return;
        report.warning("`<dependency>` "
                + dep.module()
                + " has no resolved `<version>` anywhere in its parent chain; jk wrote `=unresolved`."
                + " Pin it in the POM and re-import.");
    }
}
