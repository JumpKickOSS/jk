// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.ChildEnv;
import cc.jumpkick.host.Processes;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs {@code wsl.exe} for a setup step, handing each line of its output on as it comes, so a step
 * that takes minutes reports as it goes. The command is an argument array, never a shell string:
 * nothing a distro or a request names is ever parsed by a Windows or Linux shell on the way.
 */
@FunctionalInterface
public interface WslShell {

    /** The exit {@link #run} answers when the command could not be started. */
    int NOT_STARTED = -1;

    /** The exit {@link #run} answers when the command ran past its limit and was killed. */
    int TIMED_OUT = -2;

    /**
     * Runs {@code argv} with {@code env} added to this process's environment, input from nothing,
     * standard error merged into output, each output line handed to {@code out}; answers its exit,
     * {@link #NOT_STARTED} or {@link #TIMED_OUT}.
     */
    int run(List<String> argv, Map<String, String> env, Duration limit, Consumer<String> out);

    /** The real one. */
    WslShell LIVE = WslShell::live;

    private static int live(List<String> argv, Map<String, String> env, Duration limit, Consumer<String> out) {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        ChildEnv.putAll(pb, env);
        // wsl.exe's own messages in UTF-8, as the Linux side writes, rather than UTF-16 on a pipe.
        ChildEnv.put(pb, "WSL_UTF8", "1");
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
        Process child;
        try {
            child = ChildEnv.start(pb);
        } catch (IOException | RuntimeException e) {
            out.accept("could not start " + argv.getFirst() + ": " + e.getMessage());
            return NOT_STARTED;
        }
        // A platform thread: a long-lived pipe reader on a virtual thread pins its carrier.
        Thread reader = SessionContext.startPlatform("wsl-setup-output", () -> {
            try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    String clean = WslShell.clean(line);
                    if (!clean.isBlank()) out.accept(clean);
                }
            } catch (IOException closed) {
                // The child ended or was killed; its output ends here.
            }
        });
        try {
            if (!child.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                Processes.killTree(child);
                reader.join(2_000);
                return TIMED_OUT;
            }
            reader.join(5_000);
            return child.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Processes.killTree(child);
            return TIMED_OUT;
        }
    }

    /**
     * {@code line} without NULs, carriage returns and terminal escapes, and with any token it carries
     * withheld: a distro's engine prints its dashboard URL as {@code …#t=<token>}, and these lines go
     * to the progress every surface shows and to the change log.
     */
    static String clean(String line) {
        return line.replace("\u0000", "")
                .replace("\r", "")
                .replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "")
                .replaceAll("(#t=|access_token=|[Bb]earer\\s+)[^\\s&\"']+", "$1<withheld>")
                .strip();
    }
}
