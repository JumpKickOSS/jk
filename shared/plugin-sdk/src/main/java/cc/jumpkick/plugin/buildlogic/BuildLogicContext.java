// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.buildlogic;

import java.nio.file.Path;

/**
 * Runtime context for one build-logic task execution.
 *
 * <p>{@code outDir} is the task's <em>only</em> output surface, because it is the only thing the
 * action cache captures and replays. A write to the module classes tree is visible on the first
 * build and silently gone on the second, under a reassuring {@code cache hit} label. Everything
 * a task produces goes in {@code outDir} — the engine merges it into classes at every anchor
 * except {@code BEFORE_COMPILE}, where it is a generated-source root.
 *
 * @param projectDir absolute project root (directory containing {@code jk.toml})
 * @param outDir dedicated output directory for this task (action-cached; merged into classes)
 */
public record BuildLogicContext(Path projectDir, Path outDir) {}
