// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.List;
import java.util.Locale;

/** What a Central bundle costs against Sonatype's monthly publishing limits, as one-line reports. */
public final class CentralBudget {

    private CentralBudget() {}

    /** Files per month at Sonatype's published 90th percentile: https://central.sonatype.org/publish/maven-central-publishing-limits/ */
    public static final int P90_FILES = 1_167;

    /** Megabytes per month at the same 90th percentile row. */
    public static final long P90_MEGABYTES = 78;

    /** Where an organization reads its month's usage; Sonatype has no API for it. */
    public static final String USAGE_URL = "https://central.sonatype.com/publishing/usage";

    private static final long MEGABYTE = 1_000_000L;

    /** The size line, such as {@code Central bundle: 16 files, 1.2 MB}. */
    public static String summary(int files, long bytes) {
        return "Central bundle: " + files + (files == 1 ? " file, " : " files, ") + size(bytes);
    }

    /** The warning lines when this release alone passes either 90th-percentile limit; empty otherwise. */
    public static List<String> warnings(int files, long bytes) {
        if (files <= P90_FILES && bytes <= P90_MEGABYTES * MEGABYTE) return List.of();
        return List.of(
                "this release alone passes Central's 90th percentile (1,167 files, 78 MB)",
                "check this month's usage at " + USAGE_URL);
    }

    private static String size(long bytes) {
        if (bytes < MEGABYTE) return String.format(Locale.ROOT, "%.1f KB", bytes / 1000.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) MEGABYTE);
    }
}
