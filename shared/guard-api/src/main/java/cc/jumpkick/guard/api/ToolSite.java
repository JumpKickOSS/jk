// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import org.jspecify.annotations.Nullable;

/**
 * A site another tool found — ArchUnit, Konsist — whose fingerprint that tool's own normalisation
 * decided (line numbers and synthetic ordinals folded away), with the file and line jk points at
 * when the tool knew them.
 */
public record ToolSite(String fingerprint, @Nullable String file, int line) implements Site {}
