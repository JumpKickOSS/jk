// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

/**
 * Something that extends the core engine (in-JVM domain hooks or a sandboxed {@link Plugin} worker
 * over JSONL). Clients that drive the engine are not extensions. Capability is expressed by
 * implementing interfaces (e.g. BuildExtension), not by fixed lifecycle slots.
 */
public interface Extension {

    /** Stable identity of this extension (for discovery, ordering, and diagnostics). */
    String id();
}
