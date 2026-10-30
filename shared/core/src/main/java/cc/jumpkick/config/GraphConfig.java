// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.List;
import java.util.Map;

/**
 * The workspace root's {@code [graph]} table: the words outside the code that name a term inside it
 * ({@code Tariff = ["price", "rate card"]}).
 */
public record GraphConfig(Map<String, List<String>> aliases) {

    public static final GraphConfig EMPTY = new GraphConfig(Map.of());

    public GraphConfig {
        aliases = Map.copyOf(aliases);
    }
}
