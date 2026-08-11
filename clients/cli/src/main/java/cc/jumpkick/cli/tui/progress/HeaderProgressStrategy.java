// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.progress;

/**
 * How the plan-mode header progress bar is filled. Implementations must be safe under
 * {@link cc.jumpkick.cli.tui.CommandManager}'s lock (no blocking I/O).
 */
public interface HeaderProgressStrategy {

    /**
     * Paint inputs as {@code [numerator, denominator]} for {@link
     * cc.jumpkick.cli.tui.ProgressBar}. {@code denominator <= 0} means "no bar yet".
     */
    long[] display(HeaderProgressState state);

    /**
     * Engine weight-slice update. Weighted strategy updates its peak; clock strategy may ignore.
     *
     * @return display pair after applying the update (same shape as {@link #display})
     */
    long[] onWeightProgress(HeaderProgressState state, long numerator, long denominator);

    /** Short id for tests / diagnostics ({@code clock}, {@code weighted}). */
    String id();
}
