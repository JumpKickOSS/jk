// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import java.nio.file.Path;

/**
 * Per-module facts for an {@link IdeGenerator} (engine-computed layout paths; not a full {@code
 * JkBuild}).
 *
 * @param name the IDE module/project name ({@code project.name})
 * @param javaRelease the declared {@code project.java} bytecode level, or {@code 0} when unset
 * @param mainClass the {@code [application] main} class, or {@code null} for a non-application module
 * @param classesDir jk's main compile output ({@code target/classes})
 * @param testClassesDir jk's test compile output
 * @param jdtClassesDir JDT-LS main output ({@code target/jdt/classes/main}) — isolated from jk's
 * @param jdtTestClassesDir JDT-LS test output
 * @param generatedSourcesDir annotation-processor main source output
 * @param generatedTestSourcesDir annotation-processor test source output
 */
public record IdeModule(
        String name,
        int javaRelease,
        String mainClass,
        Path classesDir,
        Path testClassesDir,
        Path jdtClassesDir,
        Path jdtTestClassesDir,
        Path generatedSourcesDir,
        Path generatedTestSourcesDir) {}
