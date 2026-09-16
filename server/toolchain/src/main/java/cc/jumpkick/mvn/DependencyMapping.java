// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.Pom;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How one Maven dependency becomes a jk one: the scope name, the {@code library} handle, the exact
 * pin, and the test-jar kind. Shared by the POM's own dependency list and the optional deps a
 * profile contributes, so both spell a coordinate the same way.
 */
final class DependencyMapping {

    /** The version literal written when nothing in the chain supplies one; the report names the dependency. */
    static final String UNRESOLVED = "unresolved";

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
     * test-jar gets a distinct {@code -tests} handle and {@code kind = "tests"}, so a POM depending
     * on both the jar and the test-jar of one GA keeps both entries.
     */
    static Dependency toDependency(Pom.Dep dep) {
        String version = PluginFacts.usable(dep.version());
        if (version == null) version = UNRESOLVED;
        VersionSelector selector = VersionSelector.parse(version);
        boolean testJar = isTestJar(dep);
        String library = testJar ? dep.artifactId() + "-tests" : dep.artifactId();
        Dependency d = Dependency.of(library, dep.module(), selector);
        return testJar ? d.withKind(DependencyKind.TESTS) : d;
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
