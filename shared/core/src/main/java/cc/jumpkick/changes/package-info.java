// SPDX-License-Identifier: Apache-2.0

/**
 * The change journal: what JumpKick changed outside a project, who asked for it and when. Every path
 * that edits {@code config.toml}, stores or clears a credential, installs or removes a JDK, Node.js
 * or a tool, cleans or nukes a cache tier, nukes the home, or applies machine setup appends one
 * {@link cc.jumpkick.changes.Change} through {@link cc.jumpkick.changes.ChangeJournal}. Recording
 * never asks and never fails the change it records.
 */
@NullMarked
package cc.jumpkick.changes;

import org.jspecify.annotations.NullMarked;
