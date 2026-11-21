// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The compile freshness sentinels a build drops into an output directory, the one predicate that
 * recognises them, and the sources they name.
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

    /** A stamp's line for one source the compile read: {@code SOURCE <identity> <absolute path>}. */
    public static final String SOURCE_LINE = "SOURCE ";

    private BuildStamps() {}

    /**
     * Every source file the stamps in {@code outputDir} name: what the compiles that last wrote the
     * tree read. Empty when there is no stamp; a stamp that cannot be read names none.
     */
    public static List<Path> stampedSources(Path outputDir) {
        List<Path> out = new ArrayList<>();
        for (String name : ALL) {
            Path stamp = outputDir.resolve(name);
            if (!Files.isRegularFile(stamp)) continue;
            try {
                for (String line : Files.readAllLines(stamp, StandardCharsets.UTF_8)) {
                    if (!line.startsWith(SOURCE_LINE)) continue;
                    String[] parts = line.substring(SOURCE_LINE.length()).trim().split(" ", 2);
                    if (parts.length == 2) out.add(Path.of(parts[1]));
                }
            } catch (IOException | InvalidPathException unreadable) {
                // A stamp being replaced or cut short vouches for no source.
            }
        }
        return out;
    }

    /**
     * True when {@code entry} names a stamp — either a bare file name or a {@code /}-separated
     * archive-entry path. A file that merely ends in the suffix ({@code Main.jstamp}) is not one.
     */
    public static boolean isStampFile(String entry) {
        return ALL.contains(entry.substring(entry.lastIndexOf('/') + 1));
    }
}
