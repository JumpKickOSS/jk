// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Zinc analyses of the compiles that produced a compile classpath's jk-built entries — a
 * sibling's jar or classes directory, the module's own main classes under its test compile — as
 * the consumer's worker wants them: entry to the producer's analysis file. The worker answers
 * Zinc's per-entry lookup from these, so a real API change in a producer invalidates the
 * consumer's classes one producer class at a time rather than every class that touched the jar.
 *
 * <p>A compile's incremental state is keyed by its output directory ({@link
 * ActionKey#stateDir}), and {@link BuildLayout#compiledClassesOf} names that directory from
 * the entry alone, so a producer contributes the same analysis whether the classpath carries its
 * jar or its classes directory, and no workspace index is consulted.
 *
 * <p>This is a hint to the worker and never part of the compile's identity: {@link
 * ActionKey#forJavac} keys the compile on the entries' ABI, a hit stays a hit whatever analyses
 * exist, and the worker itself checks that an analysis still describes the classes on disk before
 * trusting it.
 */
public final class ProducerAnalyses {

    /** The analysis file inside a compile's incremental state dir. */
    public static final String ANALYSIS_FILE = "zinc";

    private ProducerAnalyses() {}

    /**
     * @param classpath the consumer's compile classpath
     * @param incrementalRoot the directory holding every Java compile's state dir ({@code
     *     ActionTree.INCREMENTAL_JAVA} under the actions tree)
     * @return each entry that has a producer analysis on disk, in classpath order
     */
    public static Map<Path, Path> forClasspath(List<Path> classpath, Path incrementalRoot) {
        Map<Path, Path> out = new LinkedHashMap<>();
        for (Path entry : classpath) {
            Optional<Path> classes = BuildLayout.compiledClassesOf(entry);
            if (classes.isEmpty()) continue;
            Path analysis = stateDir(incrementalRoot, classes.get()).resolve(ANALYSIS_FILE);
            if (Files.isRegularFile(analysis)) out.put(entry, analysis);
        }
        return out;
    }

    /**
     * The state dir of the compile that writes {@code classes}: the test tree belongs to
     * compile-test, every other tree {@link BuildLayout#compiledClassesOf} names to compile-main.
     */
    private static Path stateDir(Path incrementalRoot, Path classes) {
        String task =
                "test".equals(String.valueOf(classes.getFileName())) ? TaskNames.COMPILE_TEST : TaskNames.COMPILE_MAIN;
        return ActionKey.stateDir(incrementalRoot, task, classes);
    }
}
