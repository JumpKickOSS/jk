// SPDX-License-Identifier: Apache-2.0
/**
 * Maven version ordering, and nothing else: a leaf with no jk dependencies, so the resolver, the
 * lock and the config packages can all rank two versions without one of them importing the
 * resolver to do it.
 */
@NullMarked
package cc.jumpkick.version;

import org.jspecify.annotations.NullMarked;
