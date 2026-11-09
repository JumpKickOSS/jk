// SPDX-License-Identifier: Apache-2.0

/**
 * The Windows host conditions that slow or block JumpKick: Smart App Control, PowerShell's
 * execution policy, Defender and its exclusions, OneDrive and Controlled Folder Access, long paths,
 * volumes and emulation. {@link cc.jumpkick.winhost.WindowsHostCheck} reads them through {@link
 * cc.jumpkick.winhost.HostFacts}, without admin rights and under a time limit.
 */
@NullMarked
package cc.jumpkick.winhost;

import org.jspecify.annotations.NullMarked;
