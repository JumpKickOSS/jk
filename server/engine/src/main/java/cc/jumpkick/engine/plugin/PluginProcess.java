// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Parent-side child-JVM plugin driver: fork, split protocol lines from chatter, wait for exit.
 * {@link #run} is fire-and-read; {@link #converse} is two-way over stdin.
 */
public final class PluginProcess {

    private PluginProcess() {}

    /** A handle for talking back to a running plugin over its stdin. */
    public interface Conversation {
        /** Send one line to the plugin's stdin (a newline is appended and flushed). */
        void send(String line);

        /** Close the plugin's stdin, signalling end-of-input (EOF). */
        void closeInput();
    }

    /**
     * Fork {@code command}, stream its output, and return the process exit code. The parent only
     * reads — there is no stdin interaction.
     *
     * @param command full process command line (java exe, classpath/jar, main, args)
     * @param prefix marker identifying protocol lines (e.g. {@code "##JKGIT:"})
     * @param onProtocol receives each protocol line with the prefix stripped
     * @param onPassthrough receives each non-protocol line verbatim; may be {@code null} to drop them
     */
    public static int run(
            List<String> command, String prefix, Consumer<String> onProtocol, Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(command, java.util.Map.of(), prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #run(List, String, Consumer, Consumer)}, adding {@code extraEnv} to the child's
     * environment.
     */
    public static int run(
            List<String> command,
            java.util.Map<String, String> extraEnv,
            String prefix,
            Consumer<String> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, extraEnv, prefix, (json, convo) -> onProtocol.accept(json), onPassthrough);
    }

    /**
     * Fork {@code command} and drive a two-way conversation: each protocol line is delivered to
     * {@code onProtocol} along with a {@link Conversation} for sending commands back to the plugin's
     * stdin.
     *
     * <p>Reading stdout and writing stdin both happen on the calling thread, so the plugin must
     * alternate (await input → emit → await input) rather than flood stdout while blocked on stdin —
     * which the pull protocol does (it emits {@code ready}, then waits for the next command). Run one
     * {@code converse} per worker on its own thread for parallel pull queues.
     *
     * @param onPassthrough receives each non-protocol line; may be {@code null} to drop
     */
    public static int converse(
            List<String> command,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, java.util.Map.of(), prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #converse(List, String, BiConsumer, Consumer)}, adding {@code extraEnv} to the
     * child's environment.
     */
    public static int converse(
            List<String> command,
            java.util.Map<String, String> extraEnv,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (extraEnv != null && !extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
        // Hold a worker slot for the child's whole lifetime so no more than the
        // memory plan's parallelism run at once (open gate when unconfigured).
        try (PluginSlots.Lease lease = PluginSlots.acquire()) {
            return converse(pb, prefix, onProtocol, onPassthrough);
        }
    }

    private static int converse(
            ProcessBuilder pb,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        Process process = pb.start();
        try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter stdin =
                        new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            Conversation convo = new Conversation() {
                @Override
                public void send(String line) {
                    try {
                        stdin.write(line);
                        stdin.write('\n');
                        stdin.flush();
                    } catch (IOException ignored) {
                        // Plugin is gone / pipe broken; the read loop will end.
                    }
                }

                @Override
                public void closeInput() {
                    try {
                        stdin.close();
                    } catch (IOException ignored) {
                        // Already closed is fine.
                    }
                }
            };
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(prefix)) {
                        onProtocol.accept(line.substring(prefix.length()), convo);
                    } else if (onPassthrough != null) {
                        onPassthrough.accept(line);
                    }
                }
            } catch (IOException e) {
                // Worker died or pipe closed mid-stream (ticket-1053: bare "closed"). Prefer a
                // waitFor exit code over an opaque IOException when the process is already gone.
                if (isPipeClosed(e) && !process.isAlive()) {
                    return process.waitFor();
                }
                if (isPipeClosed(e)) {
                    process.destroyForcibly();
                    int exit;
                    try {
                        exit = process.waitFor();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(
                                "plugin pipe closed while waiting (interrupted); partial exit unknown", e);
                    }
                    throw new IOException(
                            "plugin pipe closed (worker exit " + exit + "): " + e.getMessage(), e);
                }
                throw e;
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return process.waitFor();
    }

    /** {@code IOException} messages like {@code closed} / {@code Stream closed} from broken pipes. */
    public static boolean isPipeClosed(IOException e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName().contains("Closed");
        }
        String lower = m.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("closed")
                || lower.contains("stream closed")
                || lower.contains("pipe closed")
                || lower.contains("broken pipe");
    }
}

