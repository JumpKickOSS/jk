// SPDX-License-Identifier: Apache-2.0
/**
 * Running rules: one {@link cc.jumpkick.guard.eval.Evaluator} per kind produces an
 * {@link cc.jumpkick.guard.eval.Evaluation}; {@link cc.jumpkick.guard.eval.LaneRun} runs every rule
 * a lane owns — collected, never short-circuited — reconciles each against the baseline and renders
 * the report the engine turns into diagnostics. The honesty semantics every kind inherits (population
 * floors, scope-shrunk, stale allow, rule-removed, scanner-failed) live here, not in any evaluator.
 */
@NullMarked
package cc.jumpkick.guard.eval;

import org.jspecify.annotations.NullMarked;
