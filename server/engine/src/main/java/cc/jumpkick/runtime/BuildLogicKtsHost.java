// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Evaluates project build-logic {@code .kts} stem scripts through {@link KtsSession} — one child
 * JVM for the whole build, with each script compiled once and cached by content.
 *
 * <p>Bindings, supplied to the script as declared properties rather than injected source:
 *
 * <ul>
 *   <li>{@code projectDir} — {@link Path} project root
 *   <li>{@code outDir} — {@link Path} action-cached task output
 * </ul>
 *
 * <p>That is the difference from the {@code kotlinc -script} fork this replaces. That host wrote a
 * wrapper containing {@code val projectDir = Path.of("/abs/path")} and had to hoist every user
 * {@code import} and {@code @file:} annotation above the injected declarations to keep the file a
 * valid Kotlin script. Declaring the bindings in the script definition removes both the hoisting and
 * its failure modes, and — because the script's own text no longer names a path — lets one compiled
 * jar serve every module that runs the script.
 *
 * <p>No {@code ant} / {@code properties} bag (Kotlin scripts use the JDK; call Ant from a Groovy
 * stem or compiled logic if needed).
 *
 * <p>Unlike the Groovy host, which still forks per script, a {@code .kts} script does not get its
 * own working directory: the shared session has one, and it is the engine's. Scripts resolve paths
 * from {@code projectDir}, which is what the documented contract has always been.
 */
final class BuildLogicKtsHost {

    private BuildLogicKtsHost() {}

    /**
     * Run one script, returning its captured stdout/stderr; a failure throws with that output
     * attached.
     *
     * <p>The caller hands the return value to the same output sink native-image and the compilers
     * use: buffered for the Ctrl-O peek ring, printed under {@code -v}. Discarding it here would
     * leave {@code error()} as the only way a script can reach the user, since a failure is the one
     * path that carries its output along.
     */
    static String evaluate(Path script, Path projectDir, Path outDir) throws IOException, InterruptedException {
        return KtsSession.run(script, projectDir, outDir);
    }
}
