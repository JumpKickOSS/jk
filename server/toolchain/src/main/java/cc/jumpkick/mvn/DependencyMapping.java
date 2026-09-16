// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.Pom;
import java.util.Locale;
import java.util.Map;
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
        VersionSelector selector = VersionSelector.parse(version);
        if (isTestJar(dep)) {
            return Dependency.of(dep.artifactId() + "-tests", dep.module(), selector)
                    .withKind(DependencyKind.TESTS);
        }
        String classifier = classifier(dep);
        if (classifier == null) return Dependency.of(dep.artifactId(), dep.module(), selector);
        return Dependency.of(dep.artifactId() + "-" + classifier, dep.module(), selector)
                .withClassifier(classifier);
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

    static void warnUnresolvedVersion(Pom.Dep dep, ImportReport.Builder report) {
        if (PluginFacts.usable(dep.version()) != null) return;
        report.warning("`<dependency>` "
                + dep.module()
                + " has no resolved `<version>` anywhere in its parent chain; jk wrote `=unresolved`."
                + " Pin it in the POM and re-import.");
    }
}
