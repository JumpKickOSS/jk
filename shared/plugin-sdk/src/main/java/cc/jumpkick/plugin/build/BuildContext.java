// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * {@link BuildExtension} contribution surface: tasks that prepare sources, rewrite classes, or
 * otherwise feed the compile/package graph via {@link TaskSpec#requires} and {@code contributes*}.
 */
public interface BuildContext extends TaskContribution {}
