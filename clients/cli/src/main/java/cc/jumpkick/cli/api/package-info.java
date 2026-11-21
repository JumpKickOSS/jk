// SPDX-License-Identifier: Apache-2.0
/**
 * What a command needs from the CLI shell: output, global and common options, path display and the
 * CLI's own paths. The verbs in {@code cc.jumpkick.command} read this package instead of the shell's
 * root, so the shell can dispatch to the verbs without the verbs reaching back into it. It reads
 * {@code cli.theme} and no other CLI package, so every one of them may read it.
 */
@NullMarked
package cc.jumpkick.cli.api;

import org.jspecify.annotations.NullMarked;
