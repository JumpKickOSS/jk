// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turn a {@link JkBuild} plus a feature selection into the three deduped, group-partitioned root
 * lists the scope solves start from, plus the file dependencies that never reach a solver. Main,
 * test and processor are solved as separate graphs (test isolation); a package key roots once per
 * graph, so a main jar and a test-jar of the same GA can both root.
 */
final class LockRoots {

    /** Every declared scope, in partition order. {@link Scope#PLATFORM} is BOM input, not a root. */
    static final List<Scope> SCOPES = List.of(
            Scope.EXPORT,
            Scope.MAIN,
            Scope.RUNTIME,
            Scope.PROVIDED,
            Scope.TEST,
            Scope.PROCESSOR,
            Scope.DEV,
            Scope.TEST_DEV);

    /** The three solver graphs. */
    enum GraphGroup {
        MAIN,
        TEST,
        PROCESSOR
    }

    /**
     * jk test infrastructure: always injected into the TEST classpath via {@code putIfAbsent} so
     * {@code jk test} (which forks {@code jk-test-runner} over the JUnit Platform Launcher API)
     * works regardless of which test framework the user chose.
     */
    static final Dependency JUNIT_LAUNCHER =
            new Dependency("org.junit.platform:junit-platform-launcher", VersionSelector.parse("latest"));

    /**
     * Passive JUnit 5 default: injected only when the user declared no {@code [test-dependencies]}
     * section, so that a bare project gets a working test framework out of the box. Once the user
     * owns the section — even if they don't list JUnit — jk leaves the framework choice to them.
     *
     * <p>Declared as {@code latest}: {@code jk lock} pins today's latest stable release (reproducible
     * builds), and {@code jk update} advances it — "jk defaults to the latest stable JUnit" stays
     * evergreen without manual bumps.
     */
    static final Dependency JUNIT_JUPITER =
            new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("latest"));

    private LockRoots() {}

    /**
     * Declared roots per graph, still keyed by package key so later phases can add to them (the
     * language-runtime inject roots the compiler runtime into {@code main}), plus the cross-package
     * features activated on each path library.
     */
    record Declared(
            LinkedHashMap<String, Dependency> main,
            LinkedHashMap<String, Dependency> test,
            LinkedHashMap<String, Dependency> processor,
            Map<String, List<String>> activatedFeatures) {

        /** Split file dependencies out of every graph; the rest are the roots the solvers see. */
        Roots split() {
            List<Dependency> fileDeps = new ArrayList<>();
            List<Dependency> mainDeclared = splitFile(main, fileDeps);
            List<Dependency> testDeclared = splitFile(test, fileDeps);
            List<Dependency> processorDeclared = splitFile(processor, fileDeps);
            return new Roots(mainDeclared, testDeclared, processorDeclared, fileDeps);
        }
    }

    /** The three solver root lists and the file dependencies, in declaration order. */
    record Roots(List<Dependency> main, List<Dependency> test, List<Dependency> processor, List<Dependency> fileDeps) {

        /** This graph's roots. */
        List<Dependency> of(GraphGroup graph) {
            return switch (graph) {
                case MAIN -> main;
                case TEST -> test;
                case PROCESSOR -> processor;
            };
        }

        /** Every root plus every file dependency — the progress budget's seed. */
        int declaredCount() {
            return main.size() + test.size() + processor.size() + fileDeps.size();
        }
    }

    /**
     * Partition {@code project}'s declared dependencies. Optional dependencies enter only when a
     * requested feature names them; cross-package features on {@code path=} libraries pull that
     * library's optional deps into {@code main}; the JUnit Platform launcher always rides the test
     * graph, and JUnit Jupiter joins it only when the user declared no test dependencies at all.
     */
    static Declared partition(
            JkBuild project, Collection<String> featuresRequested, boolean withDefaults, Path projectDir) {
        Set<String> activated = project.features().activate(new LinkedHashSet<>(featuresRequested), withDefaults);

        // Partition declared deps into main / test / processor (R5 + test isolation).
        LinkedHashMap<String, Dependency> mainDeduped = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> testDeduped = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> processorDeduped = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> optionalByLib = new LinkedHashMap<>();
        Map<String, Scope> optionalScopeByLib = new HashMap<>();
        for (Scope scope : SCOPES) {
            if (scope == Scope.PLATFORM) continue;
            for (Dependency dep : project.dependencies().of(scope)) {
                if (dep.optional()) {
                    optionalByLib.putIfAbsent(dep.library(), dep);
                    optionalScopeByLib.putIfAbsent(dep.library(), scope);
                } else {
                    // packageKey so main jar and test-jar of the same GA can both root.
                    switch (graphGroup(scope)) {
                        case PROCESSOR -> processorDeduped.putIfAbsent(dep.packageKey(), dep);
                        case TEST -> testDeduped.putIfAbsent(dep.packageKey(), dep);
                        case MAIN -> mainDeduped.putIfAbsent(dep.packageKey(), dep);
                    }
                }
            }
        }
        for (String depName : project.features().requestedDepNames(activated)) {
            Dependency opt = optionalByLib.get(depName);
            if (opt == null) {
                throw new IllegalArgumentException("feature dependency '"
                        + depName
                        + "' is not a declared optional dependency"
                        + " — declare it under [dependencies.*] with `optional = true`");
            }
            Scope optScope = optionalScopeByLib.getOrDefault(depName, Scope.MAIN);
            switch (graphGroup(optScope)) {
                case PROCESSOR -> processorDeduped.putIfAbsent(opt.packageKey(), opt);
                case TEST -> testDeduped.putIfAbsent(opt.packageKey(), opt);
                case MAIN -> mainDeduped.putIfAbsent(opt.packageKey(), opt);
            }
        }
        // Cross-package features on path= libraries: pull their optional deps.
        CrossPackageFeatures.Result cross = CrossPackageFeatures.expand(projectDir, mainDeduped.values());
        for (Dependency extra : cross.extrasList()) {
            mainDeduped.putIfAbsent(extra.packageKey(), extra);
        }
        // junit infrastructure rides the test graph only.
        testDeduped.putIfAbsent(JUNIT_LAUNCHER.packageKey(), JUNIT_LAUNCHER);
        if (project.dependencies().of(Scope.TEST).isEmpty()) {
            testDeduped.putIfAbsent(JUNIT_JUPITER.packageKey(), JUNIT_JUPITER);
        }
        return new Declared(mainDeduped, testDeduped, processorDeduped, cross.activatedFeaturesByModule());
    }

    private static GraphGroup graphGroup(Scope scope) {
        return switch (scope) {
            case PROCESSOR -> GraphGroup.PROCESSOR;
            case TEST, TEST_DEV -> GraphGroup.TEST;
            default -> GraphGroup.MAIN;
        };
    }

    private static List<Dependency> splitFile(LinkedHashMap<String, Dependency> deduped, List<Dependency> fileDeps) {
        List<Dependency> out = new ArrayList<>();
        for (Dependency d : deduped.values()) {
            if (d.isFile()) {
                if (fileDeps.stream().noneMatch(f -> f.module().equals(d.module()))) fileDeps.add(d);
            } else {
                out.add(d);
            }
        }
        return out;
    }
}
