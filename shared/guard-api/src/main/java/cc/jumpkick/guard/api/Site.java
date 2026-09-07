// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import org.jspecify.annotations.Nullable;

/**
 * Where a violation is. Every site knows its fingerprint — the line-independent identity the
 * baseline stores ({@code Origin#member -> target} for a call, a class name, a path plus content
 * for text, a model key) — and, when it has one, the file and line a diagnostic points at.
 */
public sealed interface Site permits CallSite, FieldAccess, ClassSite, TaggedClass, TextSite, ModelSite, MetricSite {

    /** The baseline identity: stable across unrelated edits above the site. */
    String fingerprint();

    /** Source-root-relative file the diagnostic points at, or {@code null} when the site has none. */
    @Nullable
    String file();

    /** 1-based line, or {@code 0} when unknown. */
    int line();
}
