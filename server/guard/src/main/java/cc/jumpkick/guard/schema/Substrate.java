// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

/** Where a kind's evidence comes from. Decides what a rule can and cannot see. */
public enum Substrate {
    /** Manifests, lockfile, module graph, tiers — in memory at plan time. */
    MODEL,
    /** The per-module facts index the compile task writes. */
    BYTECODE,
    /** Source files through the {@code CodeText} projections. */
    TEXT,
    /** Build artefacts: POMs, jars, coverage reports. */
    OUTPUT,
    /** Derive half from bytecode, scan half from text ({@code vocabulary}). */
    HYBRID
}
