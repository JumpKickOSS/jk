// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.List;
import java.util.Map;

/**
 * The workspace root's {@code [graph]} table: the words outside the code that name a term inside it
 * ({@code Tariff = ["price", "rate card"]}), and what a build's verdict says of the prose that still
 * names a name an edit changed ({@code anchor-tail}: {@link #TAIL_SILENT}, the default, says nothing;
 * {@link #TAIL_COUNT} and {@link #TAIL_KINDS} count it, for the agent bench to compare).
 */
public record GraphConfig(Map<String, List<String>> aliases, String anchorTail) {

    public static final String TAIL_SILENT = "silent";
    public static final String TAIL_COUNT = "count";
    public static final String TAIL_KINDS = "kinds";

    /** The values {@code anchor-tail} takes. */
    public static final List<String> TAILS = List.of(TAIL_SILENT, TAIL_COUNT, TAIL_KINDS);

    public static final GraphConfig EMPTY = new GraphConfig(Map.of(), TAIL_SILENT);

    public GraphConfig {
        aliases = Map.copyOf(aliases);
    }

    public GraphConfig(Map<String, List<String>> aliases) {
        this(aliases, TAIL_SILENT);
    }
}
