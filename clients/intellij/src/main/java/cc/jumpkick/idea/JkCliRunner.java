// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Key;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs {@code jk …} as a subprocess (never loads engine jars). Long-running verbs stream their
 * lines to a sink as they arrive (the plugin log by default) and retain only a bounded transcript
 * tail; {@link #runCapture} is the explicit full-capture exception for output a caller parses
 * ({@code --print-model}).
 */
public final class JkCliRunner {

    private static final Logger LOG = Logger.getInstance(JkCliRunner.class);

    /** How long a cancelled run waits for the killed process's output to drain, in milliseconds. */
    private static final long CANCEL_DRAIN_MS = 2_000;

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private JkCliRunner() {}

    /** Streaming default for long verbs: lines land in the IDE log live, transcript tail retained. */
    public static Result run(@NotNull File cwd, @NotNull List<String> args, @Nullable ProgressIndicator indicator)
            throws Exception {
        return run(cwd, args, indicator, line -> LOG.info("jk: " + line));
    }

    /**
     * Run with an explicit line sink ({@code null} = capture-only). With a sink, each output line
     * is delivered as it arrives and the returned {@link Result} carries only the last
     * {@value JkCliLines#STREAM_RETAIN_CHARS} chars per stream; without one, the full transcript is kept.
     */
    public static Result run(
            @NotNull File cwd,
            @NotNull List<String> args,
            @Nullable ProgressIndicator indicator,
            @Nullable Consumer<String> sink)
            throws Exception {
        return exec(cwd, args, indicator, sink, sink, sink != null);
    }

    /**
     * Full stdout capture with stderr streamed to {@code stderrSink}: the shape a parsed verb
     * needs when its progress still belongs in a console.
     */
    public static Result runCapture(
            @NotNull File cwd,
            @NotNull List<String> args,
            @Nullable ProgressIndicator indicator,
            @Nullable Consumer<String> stderrSink)
            throws Exception {
        return exec(cwd, args, indicator, null, stderrSink, false);
    }

    private static Result exec(
            File cwd,
            List<String> args,
            @Nullable ProgressIndicator indicator,
            @Nullable Consumer<String> outSink,
            @Nullable Consumer<String> errSink,
            boolean capped)
            throws Exception {
        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(JkBin.path());
        cmd.addParameters(args);
        cmd.setWorkDirectory(cwd);
        cmd.setCharset(StandardCharsets.UTF_8);
        // Prefer plain/machine output when plugins parse stdout.
        cmd.withEnvironment("NO_COLOR", "1");

        OSProcessHandler handler = new OSProcessHandler(cmd);
        // Each stream's reader thread appends to its own buffer; a cancelled run reads them while
        // the readers may still be draining, so every access holds the buffer's lock.
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                String text = event.getText();
                if (text == null) return;
                boolean stderr = JkCliLines.isStderr(String.valueOf(outputType));
                StringBuilder transcript = stderr ? err : out;
                synchronized (transcript) {
                    JkCliLines.append(transcript, text, capped);
                }
                Consumer<String> sink = stderr ? errSink : outSink;
                if (sink != null) {
                    String line = text.stripTrailing();
                    if (!line.isEmpty()) sink.accept(line);
                }
                if (indicator != null) {
                    indicator.setText2(JkCliLines.trimLine(text));
                    if (indicator.isCanceled()) {
                        handler.destroyProcess();
                    }
                }
            }
        });
        handler.startNotify();
        while (!handler.isProcessTerminated()) {
            if (indicator != null && indicator.isCanceled()) {
                handler.destroyProcess();
                // The handler reports termination only once both readers have drained.
                handler.waitFor(CANCEL_DRAIN_MS);
                return result(130, out, err);
            }
            try {
                // noinspection BusyWait
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handler.destroyProcess();
                return result(130, out, err);
            }
        }
        Integer code = handler.getExitCode();
        return result(code == null ? 1 : code, out, err);
    }

    private static Result result(int exitCode, StringBuilder out, StringBuilder err) {
        String stdout;
        String stderr;
        synchronized (out) {
            stdout = out.toString();
        }
        synchronized (err) {
            stderr = err.toString();
        }
        return new Result(exitCode, stdout, stderr);
    }

    public static Result run(@NotNull File cwd, @NotNull String... args) throws Exception {
        return run(cwd, Arrays.asList(args), null);
    }

    /** Full stdout capture (for {@code --print-model}) — the explicit exception to streaming. */
    public static Result runCapture(@NotNull File cwd, @NotNull List<String> args) throws Exception {
        return exec(cwd, args, null, null, null, false);
    }

    /** {@link #runCapture(File, List)} with cancellation via {@code indicator}. */
    public static Result runCapture(
            @NotNull File cwd, @NotNull List<String> args, @Nullable ProgressIndicator indicator) throws Exception {
        return exec(cwd, args, indicator, null, null, false);
    }

    public static List<String> args(String... parts) {
        return new ArrayList<>(Arrays.asList(parts));
    }
}
