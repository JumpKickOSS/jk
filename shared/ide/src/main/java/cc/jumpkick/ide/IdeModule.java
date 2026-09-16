// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Per-module facts for an {@link IdeGenerator} (engine-computed layout paths; not a full {@code
 * JkBuild}).
 *
 * @param name the IDE module/project name ({@code name})
 * @param javaRelease the declared {@code java} bytecode level, or {@code 0} when unset
 * @param mainClass the {@code [application] main} class, or {@code null} for a non-application module
 * @param classesDir jk's main compile output ({@code target/classes})
 * @param testClassesDir jk's test compile output
 * @param jdtClassesDir IDE-owned main compile output ({@code target/jdt/classes/main}) for JDT-LS and
 *     IntelliJ — isolated from jk's so an IDE compile never lands in {@code classesDir}
 * @param jdtTestClassesDir IDE-owned test compile output
 * @param generatedSourcesDir annotation-processor main source output
 * @param generatedTestSourcesDir annotation-processor test source output
 */
public record IdeModule(
        String name,
        int javaRelease,
        @Nullable String mainClass,
        Path classesDir,
        Path testClassesDir,
        Path jdtClassesDir,
        Path jdtTestClassesDir,
        Path generatedSourcesDir,
        Path generatedTestSourcesDir) {}
