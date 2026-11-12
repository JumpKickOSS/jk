// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.nio.file.Path;
import java.util.List;

/**
 * What one main compile decided, for the steps after it: how it ended, and when it ran, the
 * freshness inputs it checked, so its write-stamp step records exactly what the next check will
 * compare.
 *
 * @param outcome {@link #NO_SOURCES}, {@link #UP_TO_DATE} or what the compiler reported
 * @param inputs the sources the stamp covers: the compile's own, plus the Java sources a joint
 *     compile reads
 * @param digest the option digest of the request the action key hashed
 * @param tokens the classpath token lines that key hashed
 * @param clock the filesystem clock ({@link FreshnessStamp#clockNow}) before the compile read its
 *     first input: a source edited while it ran is stale on the next check
 * @param actionKey the compile's action key: the record the classes tree is held against
 */
public record CompileStamp(
        String outcome, List<Path> inputs, String digest, List<String> tokens, long clock, String actionKey) {

    public static final String NO_SOURCES = "no-sources";
    public static final String UP_TO_DATE = "up-to-date";

    public CompileStamp {
        inputs = List.copyOf(inputs);
        tokens = List.copyOf(tokens);
    }

    /** A compile with nothing to compile: no stamp to write. */
    public static CompileStamp noSources() {
        return new CompileStamp(NO_SOURCES, List.of(), "", List.of(), Long.MAX_VALUE, "");
    }

    /** A compile its stamp already vouched for: the stamp stays as it is. */
    public static CompileStamp upToDate() {
        return new CompileStamp(UP_TO_DATE, List.of(), "", List.of(), Long.MAX_VALUE, "");
    }

    /** Whether the classes tree is as the last build left it: nothing compiled, nothing to stamp. */
    public boolean settled() {
        return settled(outcome);
    }

    /** {@link #settled()} for an outcome on its own. */
    public static boolean settled(String outcome) {
        return outcome.equals(UP_TO_DATE) || outcome.equals(NO_SOURCES);
    }
}
