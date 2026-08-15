// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
     * <p>stderr is discarded at the OS level and stdout is drained on a side thread, so the 5s cap
     * is real. Reading stdout to EOF inline would deadlock on a helper that fills its stderr pipe
     * (nothing drains it, so the child never exits, so stdout never sees EOF) and the {@code
     * waitFor} sequenced after the read would never be reached — an unbounded hang, not a 5s one
     *. stderr is discarded rather than merged: it must never end up inside the token.
     */
    CliTokenProbe REAL = argv -> {
        if (argv == null || argv.isEmpty()) return Optional.empty();
        Process p = null;
        try {
            p = new ProcessBuilder(argv)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process proc = p;
            StringBuilder out = new StringBuilder();
            Thread drainer = new Thread(
                    () -> {
                        try (var in = proc.getInputStream()) {
                            out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        } catch (IOException ignored) {
                            // killed mid-read, or the helper closed early — treat as no token
                        }
                    },
                    "jk-cli-token-probe");
            drainer.setDaemon(true);
            drainer.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            drainer.join(500);
            String token = out.toString().strip();
            if (p.exitValue() == 0 && !token.isBlank()) return Optional.of(token);
            return Optional.empty();
        } catch (IOException e) { // command-not-found lands here
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return Optional.empty();
        }
    };
}
