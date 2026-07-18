// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

import cc.jumpkick.plugin.build.Phase;
import java.util.Set;

/**
 * Something that extends the core engine (in-JVM domain hooks or a sandboxed {@link Plugin} worker
 * over JSONL). Clients that drive the engine are not extensions. {@link #phases()} is ordering
 * metadata; empty is valid for standalone command plugins.
 */
public interface Extension {

    /** Stable identity of this extension (for discovery, ordering, and diagnostics). */
    String id();

    /** The coarse pipeline phases this extension participates in; empty when it maps to none. */
    default Set<Phase> phases() {
        return Set.of();
    }
}
