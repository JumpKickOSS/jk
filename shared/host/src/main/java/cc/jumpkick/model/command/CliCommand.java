// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model.command;

import java.util.List;

/**
 * An executable, CLI-presentable {@link Command}: it can have aliases and subcommands, and it runs
 * against a parsed {@link Invocation}, returning the process exit code. Replaces the picocli
 * {@code @Command} + {@code Callable<Integer>} shape — leaf commands implement {@link #run}, parent
 * commands return subcommands (and typically {@code run} prints help).
 */
public interface CliCommand extends Command {

    /** Hidden alternate commands (e.g. {@code jdk} ⇄ {@code jdks}); not shown in help. */
    default List<String> aliases() {
        return List.of();
    }

    /** Subcommands, in registration order; empty for a leaf command. */
    default List<CliCommand> subcommands() {
        return List.of();
    }

    /**
     * Optional default leaf when the user runs a bare group ({@code jk <group>} with no
     * subcommand). When non-null, the dispatcher runs this instead of printing the group help.
     * {@code jk <group> --help} still shows the full subcommand list.
     */
    default CliCommand defaultSubcommand() {
        return null;
    }

    /** True when this command has no subcommands. */
    default boolean isLeaf() {
        return subcommands().isEmpty();
    }

    /** True to omit this command from the top-level help listing (still dispatchable). */
    default boolean hidden() {
        return false;
    }

    /**
     * True when unrecognized options should be forwarded as positional arguments rather than treated
     * as errors — needed for passthrough commands like {@code jk mvn} / {@code jk gradle} that relay
     * unknown flags to a child process.
     */
    default boolean passthrough() {
        return false;
    }

    /**
     * True when <em>this</em> invocation's stdout is consumed by a program rather than read by a
     * person: an {@code eval}'d shell script, a bare path, a token, wire JSON, a graph source.
     *
     * <p>The dispatcher consults this once, before {@link #run}, and it settles two things at the
     * same time: no blank-line envelope around the command's stdout, and no Unicode&rarr;ASCII
     * rewrite of it, so the payload reaches the consumer byte-for-byte as the command produced it.
     * stderr is unaffected &mdash; diagnostics stay human-formatted and spaced even here.
     *
     * <p>Must be a pure read of {@code in}: no filesystem, no engine, no config resolution. It runs
     * before the command does. When a verb or flag decides the mode ({@code jk activate
     * &lt;shell&gt;} versus the bare installer, {@code jk ide --print-model}), share one resolver
     * with {@link #run} so the two cannot disagree about which path is taken.
     *
     * <p>Every override that can return true is a row in the script-mode allowlist in
     * {@code docs/contributors/tui.md}.
     */
    default boolean scriptMode(Invocation in) {
        return false;
    }

    /**
     * Execute with the parsed arguments; return the process exit code (0 = success). Parent commands
     * typically print help and return a usage exit code when invoked without a subcommand.
     */
    int run(Invocation in) throws Exception;
}
