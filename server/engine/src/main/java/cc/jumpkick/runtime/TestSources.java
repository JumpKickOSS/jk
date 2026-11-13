// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.CompileSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The selected suites' sources by language, derived once for the build and the forecast so the
 * compile-test key and the run-tests stamp hash the same files on both sides. {@code javaTest}
 * carries every selected {@code .java} — the suites' roots and {@code [test] extra-src}, roots in
 * the test tier that belong to no suite and compile with whichever suites were selected because
 * there is nothing in them to run — less {@code [test] exclude-src}. A selected class-pattern
 * suite reads the default suite's roots ({@link TestSuites#compiled}). {@code javaTestSrc} is the
 * primary suite root, which a mixed Kotlin test compile reads Java from. Each list holds each
 * path once.
 */
record TestSources(Path javaTestSrc, List<Path> javaTest, List<Path> ktTest, List<Path> gvTest, List<Path> scTest) {

    static TestSources collect(
            JkBuild project,
            Path dir,
            boolean compact,
            List<String> selectedSuites,
            BuildLayout layout,
            @Nullable PluginDeclarations decls)
            throws IOException {
        List<String> suiteNames = TestSuites.compiled(
                dir, compact, selectedSuites, project.build().testSuites());
        LinkedHashSet<Path> javaTest = new LinkedHashSet<>(TestSuites.collectJavaSources(dir, compact, suiteNames));
        javaTest.addAll(TestSupport.testExtraSources(project, dir, ".java"));
        javaTest.addAll(PlannerKsp.generatedTestSources(layout, decls, ".java"));
        TestSources all = new TestSources(
                TestSuites.primaryJavaRoot(dir, compact, suiteNames),
                List.copyOf(javaTest),
                CompileSupport.concatDistinct(
                        TestSuites.collectKotlinSources(dir, compact, suiteNames),
                        PlannerKsp.generatedTestSources(layout, decls, ".kt")),
                CompileSupport.concatDistinct(
                        TestSuites.collectGroovySources(dir, compact, suiteNames),
                        PlannerKsp.pluginContributedTestSources(layout, decls, ".groovy")),
                TestSuites.collectScalaSources(dir, compact, suiteNames));
        TestSourceExcludes excludes = TestSourceExcludes.of(project, dir, compact, suiteNames);
        if (excludes == null) return all;
        return new TestSources(
                all.javaTestSrc(),
                excludes.keep(all.javaTest()),
                excludes.keep(all.ktTest()),
                excludes.keep(all.gvTest()),
                excludes.keep(all.scTest()));
    }

    boolean isEmpty() {
        return javaTest.isEmpty() && ktTest.isEmpty() && gvTest.isEmpty() && scTest.isEmpty();
    }

    /**
     * What compile-test hands javac: every selected Java source and, through the one Zinc
     * session, the Scala ones. This list is what the compile-test action key hashes.
     */
    List<Path> javacSources() {
        return CompileSupport.concatDistinct(javaTest, scTest);
    }

    /** Every selected source, for the TestStamp in run-tests. */
    List<Path> all() {
        List<Path> allTestSources = new ArrayList<>();
        allTestSources.addAll(javaTest);
        allTestSources.addAll(ktTest);
        allTestSources.addAll(gvTest);
        allTestSources.addAll(scTest);
        return allTestSources;
    }
}
