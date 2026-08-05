// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Key;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Runs {@code jk …} as a subprocess (never loads engine jars). */
public final class JkCliRunner {

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private JkCliRunner() {}

    public static Result run(
            @NotNull File cwd,
            @NotNull List<String> args,
            @Nullable ProgressIndicator indicator)
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
                    err.append(text);
                } else {
                    out.append(text);
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

    /** Capture stdout only (for {@code --print-model}). */
    public static Result runCapture(@NotNull File cwd, @NotNull List<String> args) throws Exception {
        return run(cwd, args, null);
    }

    public static List<String> args(String... parts) {
        return new ArrayList<>(Arrays.asList(parts));
    }

    private static String trimLine(String text) {
        String t = text.strip();
        return t.length() > 100 ? t.substring(0, 97) + "…" : t;
    }
}
