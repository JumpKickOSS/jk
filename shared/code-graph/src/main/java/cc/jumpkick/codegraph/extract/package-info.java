// SPDX-License-Identifier: Apache-2.0
/**
 * Per-file extractors: pure functions from a file's repo-relative path and text to a {@link
 * cc.jumpkick.codegraph.Fragment}. Dependency-free scanners, not full parsers: each keeps what a
 * graph needs (declarations, keys, links, class names) with its line.
 */
@NullMarked
package cc.jumpkick.codegraph.extract;

import org.jspecify.annotations.NullMarked;
