// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.List;

/**
 * The compile freshness sentinels a build drops into an output directory, and the one predicate
 * that recognises them.
 *
 * <p>A stamp body carries the wall clock at which it was written, so nothing content-keyed and
 * nothing shipped may contain one: action-cache outputs, directory fingerprints, jar/zip entries
 * and container layers all ask {@link #isStampFile} before they take a file. A writer that skips
 * the question leaks build-host metadata into the artifact and churns its digest on every build.
 *
 * <p>Lives on the host leaf because the archive writers that must exclude these run in forked
 * plugin workers, which link nothing heavier.
 */
public final class BuildStamps {

    /** Written by the Java compile. */
    public static final String JAVA = ".jstamp";

    /** Written by the Kotlin compile. */
    public static final String KOTLIN = ".kstamp";

    /** Written by the Groovy compile. */
    public static final String GROOVY = ".gstamp";

    /** Written by the KSP round, into its own output base rather than a classes dir. */
    public static final String KSP = ".kspstamp";

    /** Every stamp name, for callers that carry stamps across a directory rebuild. */
    public static final List<String> ALL = List.of(JAVA, KOTLIN, GROOVY, KSP);

    private BuildStamps() {}

    /**
     * True when {@code entry} names a stamp — either a bare file name or a {@code /}-separated
     * archive-entry path. A file that merely ends in the suffix ({@code Main.jstamp}) is not one.
     */
    public static boolean isStampFile(String entry) {
        return ALL.contains(entry.substring(entry.lastIndexOf('/') + 1));
    }
}
