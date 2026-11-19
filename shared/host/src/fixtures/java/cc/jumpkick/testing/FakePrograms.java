// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.junit.jupiter.api.Assumptions.abort;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Fake programs for tests, runnable on this OS. A POSIX fake is a {@code /bin/sh} script. Windows
 * runs a {@code .cmd} batch file by name but never a script under an {@code .exe} name, so a fake
 * {@code .exe} is a {@linkplain #windowsShim() shim} that runs the batch file beside it.
 */
public final class FakePrograms {

    /** What a fake does: a {@code /bin/sh} body and a batch body ({@code @echo off} is added). */
    public record Script(String sh, String cmd) {

        /** Prints {@code line} and exits 0, whatever its arguments. */
        public static Script printing(String line) {
            return new Script("echo " + line, "echo " + line);
        }
    }

    /** The batch file a {@linkplain #windowsShim() shim} named {@code x.exe} runs: {@code x.fake.cmd}. */
    public static final String SHIM_SCRIPT_SUFFIX = ".fake.cmd";

    private static final String SHIM_SOURCE = """
            using System;
            using System.Diagnostics;
            using System.Text;

            static class Shim {
                static int Main(string[] args) {
                    string exe = Process.GetCurrentProcess().MainModule.FileName;
                    string script = exe.Substring(0, exe.Length - 4) + "%s";
                    StringBuilder line = new StringBuilder("/d /c \\"\\"" + script + "\\"");
                    foreach (string a in args) line.Append(" \\"").Append(a.Replace("\\"", "\\"\\"")).Append('"');
                    line.Append('"');
                    ProcessStartInfo info = new ProcessStartInfo("cmd.exe", line.ToString());
                    info.UseShellExecute = false;
                    using (Process p = Process.Start(info)) {
                        p.WaitForExit();
                        return p.ExitCode;
                    }
                }
            }
            """.formatted(SHIM_SCRIPT_SUFFIX);

    private static byte @Nullable [] shim;

    private FakePrograms() {}

    /**
     * A program {@code name} in {@code dir} that the OS finds by name: {@code dir/name} on POSIX,
     * {@code dir/name.cmd} on Windows. Returns the file written.
     */
    public static Path script(Path dir, String name, Script script) throws IOException {
        Files.createDirectories(dir);
        if (Os.isWindows()) return Files.writeString(dir.resolve(name + ".cmd"), batch(script));
        return executable(dir.resolve(name), script);
    }

    /**
     * A program at exactly {@code file}, which the OS starts directly: a script on POSIX; on Windows
     * {@code file} names an {@code .exe}, written as the shim with its batch file beside it.
     */
    public static Path executable(Path file, Script script) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        if (Os.isWindows()) {
            String name = file.getFileName().toString();
            if (!name.endsWith(".exe")) throw new IllegalArgumentException("not an .exe: " + file);
            Files.write(file, windowsShim());
            Files.writeString(file.resolveSibling(shimScript(name)), batch(script));
            return file;
        }
        Files.writeString(file, "#!/bin/sh\n" + script.sh() + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /** The argument a {@link #warmed} script exits on at once, before its body runs. */
    public static final String WARM_ARG = "--jk-warm";

    /**
     * A POSIX script at {@code file} holding {@code text}, run once untimed before it is returned.
     * Security software that scans a new executable's first run (for seconds, on a slow network)
     * then never spends a test's timed window. A first line of {@code text} that starts with
     * {@code #!} stays first; the line after it exits on {@value #WARM_ARG}.
     */
    public static Path warmed(Path file, String text) throws IOException {
        String guard = "[ \"$1\" = " + WARM_ARG + " ] && exit 0\n";
        String body;
        if (text.startsWith("#!")) {
            int eol = text.indexOf('\n');
            body = eol < 0 ? text + "\n" + guard : text.substring(0, eol + 1) + guard + text.substring(eol + 1);
        } else {
            body = "#!/bin/sh\n" + guard + text;
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        warm(file);
        return file;
    }

    /** Run {@code exe} once with {@value #WARM_ARG} and wait for it, so its first run is behind it. */
    public static void warm(Path exe) throws IOException {
        Process p = new ProcessBuilder(exe.toString(), WARM_ARG)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.getOutputStream().close();
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the warm run of " + exe + " did not finish in 60 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted warming " + exe, e);
        }
    }

    /** The batch file name a shim called {@code exeName} runs. */
    public static String shimScript(String exeName) {
        return exeName.substring(0, exeName.length() - ".exe".length()) + SHIM_SCRIPT_SUFFIX;
    }

    /** {@code script}'s batch body as a {@code .cmd} file holds it. */
    public static String batch(Script script) {
        String body = "@echo off\r\n" + script.cmd().replace("\r\n", "\n").replace("\n", "\r\n");
        return body.endsWith("\r\n") ? body : body + "\r\n";
    }

    /**
     * A Windows console program that runs {@code <its own name less .exe>}{@value #SHIM_SCRIPT_SUFFIX}
     * with its arguments and exits with its exit code. Compiled once per JVM by the .NET Framework's
     * {@code csc.exe}, which every supported Windows ships; aborts the calling test where it is absent.
     */
    public static synchronized byte[] windowsShim() throws IOException {
        byte[] cached = shim;
        if (cached != null) return cached;
        Path csc = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"))
                .resolve("Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe");
        if (!Files.isRegularFile(csc)) abort("no .NET Framework C# compiler at " + csc + " to build a fake .exe");
        Path dir = Files.createTempDirectory("jk-fake-exe-");
        Path source = Files.writeString(dir.resolve("shim.cs"), SHIM_SOURCE);
        Path out = dir.resolve("shim.exe");
        Processes.Captured run = Processes.capture(
                List.of(csc.toString(), "/nologo", "/out:" + out, source.toString()), Duration.ofMinutes(2));
        if (!run.ok()) throw new IOException("csc could not build the fake .exe shim:\n" + run.output());
        cached = Files.readAllBytes(out);
        shim = cached;
        return cached;
    }
}
