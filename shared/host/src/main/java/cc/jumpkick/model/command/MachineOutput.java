// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model.command;

/**
 * What a command prints for a program under {@code -O json} / {@code -O jsonl}. The dispatcher
 * refuses {@code -O json|jsonl} on a {@link #NONE} command instead of letting it print human text a
 * parser would choke on.
 */
public enum MachineOutput {
    /** Human output only: {@code -O text} is the one format. */
    NONE,
    /**
     * One JSON object per line on stdout ({@code docs/user/machine-output.md}); human lines the
     * command still writes go to stderr.
     */
    JSONL
}
