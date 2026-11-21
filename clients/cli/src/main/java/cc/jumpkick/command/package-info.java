// SPDX-License-Identifier: Apache-2.0
/**
 * The dispatcher's verbs, one family per sub-package: pipeline, project, toolchain, ide, interop and
 * system. This
 * root holds what more than one family reads — the module selectors, the cwd module scope, variant
 * selection, the engine-edit helpers, client environment forwarding, JkEnv, the toolchain path, the
 * tool targets, the project a verb runs in and the lock freshen ahead of a read. Nothing here reads a family, and no verb names the shell: what a verb needs from it comes
 * through {@code cc.jumpkick.cli.api}.
 */
@NullMarked
package cc.jumpkick.command;

import org.jspecify.annotations.NullMarked;
