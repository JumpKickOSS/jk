// SPDX-License-Identifier: Apache-2.0
/**
 * The machine's environments as the Windows engine sees them: each WSL distro, the JumpKick it
 * has, and the one operation that installs JumpKick there or removes it, behind the dashboard,
 * {@code jk self setup-wsl}, the MCP {@code machine} tool and the setup decision.
 */
@NullMarked
package cc.jumpkick.engine.machine;

import org.jspecify.annotations.NullMarked;
