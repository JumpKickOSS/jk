// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Runs one short-lived tool — a platform keychain ({@code secret-tool}, {@code security}, {@code
 * powershell}), or {@code git} for {@code jk release} — and hands back what it said. A seam: the
 * keychain backends are tested over their command lines with a fake.
 */
@FunctionalInterface
public interface SecretCommand {

    /** What the tool said: its exit status and both streams, decoded as UTF-8. */
    record Result(int exit, String stdout, String stderr) {
        /** The tool's complaint, trimmed to one line, or its exit status when it said nothing. */
        public String complaint() {
            String text = stderr.isBlank() ? stdout : stderr;
            String first = text.strip().lines().findFirst().orElse("");
            return first.isEmpty() ? "exit status " + exit : first;
        }
    }

    /**
     * Run {@code argv}, writing {@code stdin} to it (nothing when null), and wait at most {@code
     * cap}.
     *
     * @throws IOException when the tool cannot be started (not installed) or outlives {@code cap}
     */
    Result run(List<String> argv, byte @Nullable [] stdin, Duration cap) throws IOException;

    /**
     * The real runner. The secret travels on stdin, never in argv, so no process listing shows it.
     * Both output pipes are captured through {@link Processes}, on platform threads.
     */
    SecretCommand REAL = (argv, stdin, cap) -> {
        Processes.Captured c = Processes.capture(new ProcessBuilder(argv).start(), stdin, cap, Integer.MAX_VALUE);
        if (c.timedOut()) throw new IOException(argv.get(0) + " did not answer within " + cap.toSeconds() + "s");
        return new Result(c.exit(), c.output(), c.error());
    };
}
