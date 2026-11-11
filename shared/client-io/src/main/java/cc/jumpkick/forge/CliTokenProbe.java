// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Piggybacks on a native forge CLI to reuse an existing login ({@code gh auth token}, {@code glab
 * auth token}). Best-effort by contract: every failure mode — binary not on {@code PATH}, non-zero
 * exit, empty output, hang — yields {@link Optional#empty()} so the caller falls through to jk's
 * own flow. Never throws.
 */
@FunctionalInterface
public interface CliTokenProbe {

    /** Run {@code argv} and return its trimmed stdout if it looks like a token. */
    Optional<String> token(List<String> argv);

    /**
     * The real implementation: shell out via {@link ProcessBuilder}.
     *
     * <p>stdout is captured through {@link Processes}, so the 5s cap holds. stderr is discarded
     * rather than merged: it must never end up inside the token.
     */
    CliTokenProbe REAL = capped(Duration.ofSeconds(5));

    /** {@link #REAL} with another cap on the helper's run time; tests prove the cap in milliseconds. */
    static CliTokenProbe capped(Duration cap) {
        return argv -> {
            if (argv == null || argv.isEmpty()) return Optional.empty();
            try {
                Processes.Captured c =
                        Processes.capture(new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD), cap);
                String token = c.output().strip();
                return c.ok() && !token.isBlank() ? Optional.of(token) : Optional.empty();
            } catch (IOException e) { // command-not-found lands here
                return Optional.empty();
            }
        };
    }
}
