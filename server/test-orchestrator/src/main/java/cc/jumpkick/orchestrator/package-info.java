// SPDX-License-Identifier: Apache-2.0
/**
 * The code that judges a suite: how the runner's event stream is counted, when a run that ended
 * short is red, when an empty discovery or a {@code --class} that matched nothing fails, which
 * failures a retry answers, which of a suite's recorded reads count as its inputs, which suites a
 * run runs, and the listener and failure text it hands on. A run-tests stamp keys this package by its compiled
 * classes ({@link cc.jumpkick.orchestrator.OrchestratorCode}), not by the engine around it.
 */
@NullMarked
package cc.jumpkick.orchestrator;

import org.jspecify.annotations.NullMarked;
