// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where each test compiler writes, and what each reads of the ones before it. Tests in one
 * language compile straight into {@code testClasses}. Kotlin beside Java or Groovy compiles into a
 * dir of its own (kotlinc prunes what it did not write), as does Groovy beside Java or Kotlin
 * (groovyc's cache snapshots its whole output dir); the two are merged into {@code testClasses}
 * after javac. Kotlin compiles first, Groovy against its output, javac against both. One
 * derivation for compile-test and its forecast, so both key each compiler on one classpath.
 */
record TestOutputs(Path testClasses, Path kotlinOut, Path groovyOut, boolean mixedKotlin, boolean mixedGroovy) {

    /** The outputs of the compilers {@code src} runs in a module laid out as {@code layout}. */
    static TestOutputs of(TestSources src, BuildLayout layout, Path testClasses) {
        boolean java = !src.javaTest().isEmpty();
        boolean kotlin = !src.ktTest().isEmpty();
        boolean groovy = !src.gvTest().isEmpty();
        boolean mixedKotlin = kotlin && (java || groovy);
        boolean mixedGroovy = groovy && (java || kotlin);
        return new TestOutputs(
                testClasses,
                mixedKotlin ? layout.kotlinTestClassesDir() : testClasses,
                mixedGroovy ? layout.groovyTestClassesDir() : testClasses,
                mixedKotlin,
                mixedGroovy);
    }

    /**
     * The classpath every test compiler starts from: the main classes, the module's own fixtures,
     * the resolved test compile classpath, then the Groovy jar a module with Groovy main or test
     * sources needs ({@code null} for one without), since every Groovy class implements {@code
     * groovy.lang.GroovyObject}.
     */
    static List<Path> baseClasspath(
            Path mainClasses, JkBuild project, BuildLayout layout, List<Path> compileCp, @Nullable Path groovyJar) {
        List<Path> out = PlannerFixtures.withOwnFixtures(project, layout, new ArrayList<>(List.of(mainClasses)));
        out = new ArrayList<>(out);
        out.addAll(compileCp);
        if (groovyJar != null) out.add(groovyJar);
        return out;
    }

    /** groovyc's classpath: the module's, then Kotlin's test output when Kotlin compiled beside it. */
    List<Path> groovyClasspath(List<Path> base) {
        return with(base, mixedKotlin, false);
    }

    /** javac's classpath: the module's, then the Kotlin and Groovy test outputs that compiled beside it. */
    List<Path> javacClasspath(List<Path> base) {
        return with(base, mixedKotlin, mixedGroovy);
    }

    private List<Path> with(List<Path> base, boolean kotlin, boolean groovy) {
        if (!kotlin && !groovy) return base;
        List<Path> out = new ArrayList<>(base);
        if (kotlin) out.add(kotlinOut);
        if (groovy) out.add(groovyOut);
        return out;
    }
}
