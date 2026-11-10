// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Whether a compile step is up to date: its stamp vouches for the inputs, and its tree still
 * holds every output the stamped compile's record owns. A fresh stamp over a partly wiped tree
 * falls through to the action cache, whose hit restores the record's whole tree.
 */
final class CompileUpToDate {

    private CompileUpToDate() {}

    /** compile-java's check: its tree, which the other languages merge into, against every stamp in it. */
    static boolean java(
            ActionCache actionCache, Path dir, List<Path> sources, List<String> tokens, int release, String digest)
            throws IOException {
        return fresh(dir, BuildStamps.JAVA, sources, tokens, release, digest)
                && ModuleOutputs.compileOutputsOnDisk(actionCache, dir);
    }

    /** compile-groovy's check: the tree against the record its own stamp names. */
    static boolean groovy(
            ActionCache actionCache, Path dir, List<Path> sources, List<String> tokens, int release, String digest)
            throws IOException {
        return fresh(dir, BuildStamps.GROOVY, sources, tokens, release, digest)
                && ModuleOutputs.compileOutputsOnDisk(actionCache, dir, BuildStamps.GROOVY);
    }

    private static boolean fresh(
            Path dir, String stamp, List<Path> sources, List<String> tokens, int release, String digest)
            throws IOException {
        return FreshnessStamp.isFresh(dir, stamp, sources, FreshnessStamp.ClasspathTokens.of(tokens), release, digest);
    }
}
