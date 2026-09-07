// SPDX-License-Identifier: Apache-2.0
/**
 * What a command needs from the CLI shell: output, global and common options, path display, the
 * CLI's own paths and the project context. The verbs in {@code cc.jumpkick.command} read this
 * package instead of the shell's root, so the shell can dispatch to the verbs without the verbs
 * reaching back into it.
 */
@NullMarked
package cc.jumpkick.cli.api;

import org.jspecify.annotations.NullMarked;
