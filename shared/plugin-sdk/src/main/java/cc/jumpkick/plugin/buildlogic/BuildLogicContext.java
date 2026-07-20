// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.buildlogic;

import java.nio.file.Path;

/**
 * Runtime context for one build-logic task execution.
 *
 * @param projectDir absolute project root (directory containing {@code jk.toml})
 * @param outDir dedicated output directory for this task (action-cached; merged into classes)
 * @param classesDir module classes tree (main compile output / resource merge target)
 */
public record BuildLogicContext(Path projectDir, Path outDir, Path classesDir) {}
