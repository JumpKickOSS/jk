// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.List;
import java.util.Map;

/**
 * The workspace root's {@code [graph]} table: the words outside the code that name a term inside it
 * ({@code Tariff = ["price", "rate card"]}), and what a build's verdict says of the prose that still
 * names a name an edit changed ({@code anchor-tail}). {@link #TAIL_SILENT}, the default ({@link
 * #TAIL_DEFAULT}), names the places that must change and says nothing of the prose; {@link #TAIL_OFF} adds
 * nothing to the verdict, which then neither waits for the graph's refresh nor reads the stale record;
 * {@link #TAIL_COUNT} and {@link #TAIL_KINDS} also count the prose, and are experimental: the agent bench
 * found them no better than silent and the only variants that sent agents reading files for nothing.
 */
public record GraphConfig(Map<String, List<String>> aliases, String anchorTail) {

    public static final String TAIL_OFF = "off";
    public static final String TAIL_SILENT = "silent";
    public static final String TAIL_COUNT = "count";
    public static final String TAIL_KINDS = "kinds";

    /** What a root that does not set {@code anchor-tail} gets. */
    public static final String TAIL_DEFAULT = TAIL_SILENT;

    /** The values {@code anchor-tail} takes. */
    public static final List<String> TAILS = List.of(TAIL_OFF, TAIL_SILENT, TAIL_COUNT, TAIL_KINDS);

    public static final GraphConfig EMPTY = new GraphConfig(Map.of(), TAIL_DEFAULT);

    public GraphConfig {
        aliases = Map.copyOf(aliases);
    }

    public GraphConfig(Map<String, List<String>> aliases) {
        this(aliases, TAIL_DEFAULT);
    }
}
