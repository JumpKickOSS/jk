// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
