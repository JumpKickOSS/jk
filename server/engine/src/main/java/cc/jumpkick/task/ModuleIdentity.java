// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;

/**
 * Which module a plan builds: its effective manifest and where its outputs go. Every plan's
 * {@code parse-build} publishes it.
 *
 * @param project the effective manifest the plan was built from
 * @param layout the module's output layout
 */
public record ModuleIdentity(JkBuild project, BuildLayout layout) {}
