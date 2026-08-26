// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
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

    /** Chars of transcript retained per stream on the streaming path (a tail — errors print last). */
    private static final int STREAM_RETAIN_CHARS = 64 * 1024;

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
     * {@value #STREAM_RETAIN_CHARS} chars per stream; without one, the full transcript is kept.
     */
    public static Result run(
            @NotNull File cwd,
            @NotNull List<String> args,
            @Nullable ProgressIndicator indicator,
            @Nullable Consumer<String> sink)
            throws Exception {
        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(JkBin.path());
        cmd.addParameters(args);
        cmd.setWorkDirectory(cwd);
        cmd.setCharset(StandardCharsets.UTF_8);
        // Prefer plain/machine output when plugins parse stdout.
        cmd.withEnvironment("NO_COLOR", "1");

        OSProcessHandler handler = new OSProcessHandler(cmd);
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        handler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                String text = event.getText();
                if (text == null) return;
                // ProcessOutputTypes.STDERR vs STDOUT — compare by toString for API stability.
                if ("stderr".equalsIgnoreCase(String.valueOf(outputType))
                        || String.valueOf(outputType).contains("STDERR")) {
                    append(err, text, sink != null);
                } else {
                    append(out, text, sink != null);
                }
                if (sink != null) {
                    String line = text.stripTrailing();
                    if (!line.isEmpty()) sink.accept(line);
                }
                if (indicator != null) {
                    indicator.setText2(trimLine(text));
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
                return new Result(130, out.toString(), err.toString());
            }
            try {
                // noinspection BusyWait
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handler.destroyProcess();
                return new Result(130, out.toString(), err.toString());
            }
        }
        Integer code = handler.getExitCode();
        return new Result(code == null ? 1 : code, out.toString(), err.toString());
    }

    public static Result run(@NotNull File cwd, @NotNull String... args) throws Exception {
        return run(cwd, Arrays.asList(args), null);
    }

    /** Full stdout capture (for {@code --print-model}) — the explicit exception to streaming. */
    public static Result runCapture(@NotNull File cwd, @NotNull List<String> args) throws Exception {
        return runCapture(cwd, args, null);
    }

    /** {@link #runCapture(File, List)} with cancellation via {@code indicator}. */
    public static Result runCapture(
            @NotNull File cwd, @NotNull List<String> args, @Nullable ProgressIndicator indicator) throws Exception {
        return run(cwd, args, indicator, null);
    }

    /** Append to a transcript buffer, trimming to the retained tail on the streaming path. */
    private static void append(StringBuilder sb, String text, boolean capped) {
        sb.append(text);
        if (capped && sb.length() > STREAM_RETAIN_CHARS * 2) {
            sb.delete(0, sb.length() - STREAM_RETAIN_CHARS);
        }
    }

    public static List<String> args(String... parts) {
        return new ArrayList<>(Arrays.asList(parts));
    }

    private static String trimLine(String text) {
        String t = text.strip();
        return t.length() > 100 ? t.substring(0, 97) + "…" : t;
    }
}
