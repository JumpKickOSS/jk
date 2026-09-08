// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.host.Os;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Puts {@code <home>/bin} on the Windows <strong>User</strong> {@code PATH} — the registry-backed
 * one, not this process's copy.
 *
 * <p>A profile block is enough on Unix, where every shell sources one. On Windows it reaches
 * PowerShell and nothing else: {@code cmd.exe}, a GUI-launched process and a fresh Terminal tab
 * running the legacy shell all miss it, so "jk is on PATH" would be true only for users who happen
 * to live in pwsh. The User PATH is the mechanism that covers all of them, and it is the same one
 * {@code install.ps1} writes — which is why this is idempotent by whole-entry comparison rather
 * than by appending: installer-then-activate and activate-then-installer both leave exactly one
 * entry.
 *
 * <p>Best-effort by contract. No PowerShell, a policy that refuses it, or a locked-down registry
 * costs the user a manual PATH edit; none of them may fail {@code jk activate}.
 */
final class WindowsUserPath {

    /**
     * Reads the User PATH, adds {@code $env:JK_ACTIVATE_BIN} when no entry already equals it, and
     * says which happened. The target travels in an environment variable so no quoting of a path
     * with spaces, quotes or backticks reaches the script text.
     */
    private static final String SCRIPT = """
            $bin = $env:JK_ACTIVATE_BIN
            $current = [Environment]::GetEnvironmentVariable('Path','User')
            if ($null -eq $current) { $current = '' }
            $entries = $current -split ';' | Where-Object { $_ -ne '' }
            if ($entries | Where-Object { $_.TrimEnd('\\') -ieq $bin.TrimEnd('\\') }) {
              Write-Output 'PRESENT'
            } else {
              $next = if ($current -eq '') { $bin } else { "$bin;$current" }
              [Environment]::SetEnvironmentVariable('Path', $next, 'User')
              Write-Output 'ADDED'
            }
            """;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int CAPTURE_LIMIT = 8 * 1024;

    /** What {@link #ensure} did. */
    enum Result {
        /** Not Windows: the profile block is the whole mechanism. */
        NOT_WINDOWS,
        /** An entry for this directory was already there. */
        ALREADY_PRESENT,
        /** The entry was prepended to the User PATH. */
        ADDED,
        /** Could not be done — no PowerShell, refused, or timed out. */
        FAILED
    }

    private WindowsUserPath() {}

    /** Ensure {@code binDir} appears exactly once on the Windows User PATH. Never throws. */
    static Result ensure(Path binDir) {
        if (!Os.isWindows() || binDir == null) return Result.NOT_WINDOWS;
        return ensure(binDir, TIMEOUT, WindowsUserPath::startPowerShell);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(Path binDir) throws IOException;
    }

    static Result ensure(Path binDir, Duration timeout, ProcessStarter starter) {
        Process p = null;
        ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> stdout = null;
        Future<byte[]> stderr = null;
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            p = starter.start(binDir);
            p.getOutputStream().close();
            Process running = p;
            stdout = drains.submit(() -> drain(running.getInputStream()));
            stderr = drains.submit(() -> drain(running.getErrorStream()));
            if (!p.waitFor(remaining(deadline), TimeUnit.NANOSECONDS) || p.exitValue() != 0) return Result.FAILED;
            String out =
                    new String(stdout.get(remaining(deadline), TimeUnit.NANOSECONDS), StandardCharsets.UTF_8).trim();
            stderr.get(remaining(deadline), TimeUnit.NANOSECONDS);
            return switch (out) {
                case "PRESENT" -> Result.ALREADY_PRESENT;
                case "ADDED" -> Result.ADDED;
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

    private static Process startPowerShell(Path binDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT);
        pb.environment()
                .put("JK_ACTIVATE_BIN", binDir.toAbsolutePath().normalize().toString());
        return pb.redirectErrorStream(false).start();
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
