// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/** The output of a host tool discovery asks (a registry query, {@code java_home}), bounded in time. */
final class ToolOutput {

    private ToolOutput() {}

    /**
     * {@code command}'s output (stderr merged) when it exits 0 within {@code timeout}; {@code null}
     * on a non-zero exit, a timeout, or a command that cannot start. The output drains on its own
     * thread, so the bound holds for a tool that neither exits nor closes its pipe.
     */
    static @Nullable String run(List<String> command, Duration timeout) {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        CompletableFuture<String> output = new CompletableFuture<>();
        Thread.ofPlatform().daemon().name("jk-discovery-output").start(() -> {
            try (InputStream in = p.getInputStream()) {
                output.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                output.complete("");
            }
        });
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }
}
