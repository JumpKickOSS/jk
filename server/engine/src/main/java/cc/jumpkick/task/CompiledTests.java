// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code compile-test} left for {@code run-tests}: the stamp inputs of the suite it compiled.
 *
 * @param noSources the selected suites have no test sources, or a workspace module lacks the suite
 * @param sources every test source the compiles read
 * @param actionKeys the test compiles' action keys, one per language compiled
 * @param resourceDirs the resource roots the suites read
 * @param suiteRoots each directory suite's own resource roots when the run keeps them apart; empty
 *     when the test-classes tree holds the resources
 */
public record CompiledTests(
        boolean noSources,
        List<Path> sources,
        TestStamp.CompileTestKeys actionKeys,
        List<Path> resourceDirs,
        Map<String, List<Path>> suiteRoots) {

    /** A run with nothing to compile: run-tests has no tests to run. */
    public static final CompiledTests NO_SOURCES =
            new CompiledTests(true, List.of(), TestStamp.CompileTestKeys.NONE, List.of(), Map.of());

    public CompiledTests {
        sources = List.copyOf(sources);
        resourceDirs = List.copyOf(resourceDirs);
        suiteRoots = Collections.unmodifiableMap(new LinkedHashMap<>(suiteRoots));
    }
}
