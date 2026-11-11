// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
     * Both output pipes are drained on platform threads: a virtual thread blocked reading a child's
     * pipe pins its carrier, and the engine runs requests on virtual threads.
     */
    SecretCommand REAL = (argv, stdin, cap) -> {
        Process p = new ProcessBuilder(argv).start();
        AtomicReference<String> out = new AtomicReference<>("");
        AtomicReference<String> err = new AtomicReference<>("");
        Thread outDrain = drain(p.getInputStream(), out, "jk-keychain-stdout");
        Thread errDrain = drain(p.getErrorStream(), err, "jk-keychain-stderr");
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) in.write(stdin);
        } catch (IOException closedEarly) {
            // The tool exited without reading its input; its exit status says why.
        }
        try {
            if (!p.waitFor(cap.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IOException(argv.get(0) + " did not answer within " + cap.toSeconds() + "s");
            }
            outDrain.join(1_000);
            errDrain.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException(argv.get(0) + " was interrupted", e);
        }
        return new Result(p.exitValue(), out.get(), err.get());
    };

    private static Thread drain(InputStream stream, AtomicReference<String> into, String name) {
        Thread t = Thread.ofPlatform().daemon().name(name).start(() -> {
            try (InputStream in = stream) {
                into.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The tool was killed mid-write; what it said before is all there is.
            }
        });
        return t;
    }
}
