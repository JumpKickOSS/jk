// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.host.Os;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ensures a new Windows PowerShell session can load {@code $PROFILE}.
 *
 * <p>{@code jk activate} writes a profile block; the default client policy
 * ({@code Restricted}) refuses to load it. {@code install.ps1} often runs under
 * Process {@code Bypass}, which does not help the next interactive shell — only
 * a CurrentUser (or higher) policy does.
 *
 * <p>Best-effort and never fatal: group policy, missing PowerShell, or a timeout
 * leaves the user with a manual {@code Set-ExecutionPolicy} note from the
 * installer; none of them may fail {@code jk activate}.
 */
final class WindowsExecutionPolicy {

    /**
     * Ignores Process scope (installer Bypass). Sets CurrentUser to RemoteSigned
     * when a fresh session would otherwise be Restricted or AllSigned.
     */
    private static final String SCRIPT = """
            $byScope = @{}
            foreach ($e in @(Get-ExecutionPolicy -List)) {
              $byScope[$e.Scope.ToString()] = $e.ExecutionPolicy.ToString()
            }
            foreach ($locked in @('MachinePolicy', 'UserPolicy')) {
              $p = $byScope[$locked]
              if ($p -and $p -ne 'Undefined' -and ($p -eq 'Restricted' -or $p -eq 'AllSigned')) {
                Write-Output 'BLOCKED'
                exit 0
              }
            }
            $newSession = 'Restricted'
            foreach ($scope in @('MachinePolicy', 'UserPolicy', 'CurrentUser', 'LocalMachine')) {
              $p = $byScope[$scope]
              if ($p -and $p -ne 'Undefined') {
                $newSession = $p
                break
              }
            }
            if ($newSession -eq 'RemoteSigned' -or $newSession -eq 'Unrestricted' -or $newSession -eq 'Bypass') {
              Write-Output 'OK'
              exit 0
            }
            Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force
            Write-Output 'SET'
            """;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int CAPTURE_LIMIT = 8 * 1024;

    /** What {@link #ensure} did. */
    enum Result {
        /** Not Windows: profiles are ordinary shell rc files. */
        NOT_WINDOWS,
        /** A new session already allows unsigned local scripts. */
        ALREADY_OK,
        /** CurrentUser was set to RemoteSigned. */
        SET,
        /** MachinePolicy/UserPolicy blocks profile scripts. */
        BLOCKED,
        /** Could not be done — no PowerShell, refused, or timed out. */
        FAILED
    }

    private WindowsExecutionPolicy() {}

    /** Ensure profile scripts can load in a new PowerShell. Never throws. */
    static Result ensure() {
        if (!Os.isWindows()) return Result.NOT_WINDOWS;
        return ensure(TIMEOUT, WindowsExecutionPolicy::startPowerShell);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    static Result ensure(Duration timeout, ProcessStarter starter) {
        Process p = null;
        ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> stdout = null;
        Future<byte[]> stderr = null;
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            p = starter.start();
            p.getOutputStream().close();
            Process running = p;
            stdout = drains.submit(() -> drain(running.getInputStream()));
            stderr = drains.submit(() -> drain(running.getErrorStream()));
            if (!p.waitFor(remaining(deadline), TimeUnit.NANOSECONDS) || p.exitValue() != 0) return Result.FAILED;
            String out =
                    new String(stdout.get(remaining(deadline), TimeUnit.NANOSECONDS), StandardCharsets.UTF_8).trim();
            stderr.get(remaining(deadline), TimeUnit.NANOSECONDS);
            return switch (out) {
                case "OK" -> Result.ALREADY_OK;
                case "SET" -> Result.SET;
                case "BLOCKED" -> Result.BLOCKED;
                default -> Result.FAILED;
            };
        } catch (IOException | ExecutionException | TimeoutException e) {
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
            if (stdout != null) stdout.cancel(true);
            if (stderr != null) stderr.cancel(true);
            drains.shutdownNow();
        }
    }

    private static Process startPowerShell() throws IOException {
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT)
                .redirectErrorStream(false)
                .start();
    }

    private static byte[] drain(InputStream input) throws IOException {
        try (input;
                ByteArrayOutputStream captured = new ByteArrayOutputStream(CAPTURE_LIMIT)) {
            byte[] buffer = new byte[4096];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                int remaining = CAPTURE_LIMIT - captured.size();
                if (remaining > 0) captured.write(buffer, 0, Math.min(read, remaining));
            }
            return captured.toByteArray();
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }
}
