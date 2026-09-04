// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Worker-JVM tuning carried on {@link Session}. Null scalars mean "next layer / default";
 * {@code extraArgs} are appended verbatim.
 */
public record PluginTuning(
        @Nullable Double maxRamPercent,
        @Nullable String gc,
        @Nullable Boolean stringDedup,
        List<String> extraArgs) {

    public PluginTuning {
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
    }

    /** The empty layer — every field unset. */
    public static final PluginTuning NONE = new PluginTuning(null, null, null, List.of());
}
