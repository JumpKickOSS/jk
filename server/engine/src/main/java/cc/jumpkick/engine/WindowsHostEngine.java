// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.TomlValues;
import cc.jumpkick.engine.http.FrontDoor;
import cc.jumpkick.engine.http.GuestPairing;
import cc.jumpkick.host.Guest;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A WSL engine makes sure the Windows engine runs, so the front door is up whichever side started
 * first: it finds JumpKick for Windows through interop ({@code %JK_HOME%} or {@code
 * %USERPROFILE%\.jk}, then {@code bin\jk.exe} through {@code wslpath}), asks it {@code engine status},
 * and runs {@code jk.exe engine start} when it is stopped, never {@code java.exe}: the Windows client
 * owns its JDK, its flags and its detached start. Starting it is a change-log entry, which the
 * dashboard and desktop notify, never a question. {@code [wsl] host-engine = off} turns it off; a
 * private home ({@code JK_HOME} set) or {@code CI} leaves it off unless the config says {@code auto}.
 */
final class WindowsHostEngine {

    /** {@code [wsl] host-engine}. */
    enum Mode {
        /** Unset: on, except in a private home or under CI. */
        DEFAULT,
        AUTO,
        OFF;

        static Mode read(Path userConfig) {
            String value = TomlValues.parse(userConfig)
                    .map(root -> root.getTable("wsl"))
                    .flatMap(wsl -> TomlValues.optString(wsl, "host-engine"))
                    .orElse("");
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "off" -> OFF;
                default -> DEFAULT;
            };
        }
    }

    /** One command's end: its exit code and standard output; null when it could not run. */
    record Ran(int exit, String out) {}

    /** Runs a command from the guest, from a Windows folder; the seam tests replace. */
    interface Shell {
        /** Runs {@code argv} and reads its standard output; null when it could not run or did not end. */
        @Nullable
        Ran run(List<String> argv);
    }

    /** The real shell. */
    static final Shell SHELL = argv -> run(argv, argv.contains("start") ? 60 : 10);

    /** What happened, and the one line the engine logs for it. */
    record Outcome(State state, String line) {}

    enum State {
        /** Not a WSL guest with interop, or turned off: nothing was asked. */
        SKIPPED,
        /** No {@code jk.exe} in the Windows JumpKick home. */
        NOT_INSTALLED,
        /** The Windows engine was already running. */
        RUNNING,
        /** This guest started it. */
        STARTED,
        /** {@code jk.exe} ran and failed. */
        FAILED
    }

    private WindowsHostEngine() {}

    /**
     * Makes sure the Windows engine runs, for {@code guest}; {@code env} reads this process's
     * environment ({@code JK_HOME}, {@code CI}).
     */
    static Outcome ensure(Guest guest, Mode mode, Function<String, @Nullable String> env, Shell shell) {
        if (guest.kind() != Guest.Kind.WSL || !guest.interop()) {
            return new Outcome(State.SKIPPED, "not a WSL guest with interop");
        }
        if (mode == Mode.OFF) return new Outcome(State.SKIPPED, "[wsl] host-engine = off");
        if (mode == Mode.DEFAULT && (set(env.apply("JK_HOME")) || set(env.apply("CI")))) {
            return new Outcome(State.SKIPPED, "a private home or CI; [wsl] host-engine = auto starts it anyway");
        }
        GuestPairing.Interop interop = argv -> {
            Ran r = shell.run(argv);
            return r == null || r.exit() != 0 ? null : r.out();
        };
        String home = GuestPairing.windowsHome(interop);
        if (home == null) return new Outcome(State.SKIPPED, "interop could not name the Windows JumpKick home");
        String exe = interop.output(List.of("wslpath", "-u", home + "\\bin\\jk.exe"));
        if (exe == null || exe.isBlank() || !Files.isRegularFile(Path.of(exe.strip()))) {
            return new Outcome(State.NOT_INSTALLED, "JumpKick for Windows is not installed in " + home);
        }
        String jk = exe.strip();
        long t0 = Clock.SYSTEM.millis();
        Ran status = shell.run(List.of(jk, "engine", "status", "-O", "json"));
        long t1 = Clock.SYSTEM.millis();
        if (status != null && running(status.out())) {
            return new Outcome(State.RUNNING, "the Windows engine is running (asked in " + (t1 - t0) + " ms)");
        }
        // Output to a file (run), not a pipe: the engine jk.exe starts would hold a pipe open for its life.
        Ran start = shell.run(List.of(jk, "engine", "start", "-u", "-x"));
        long t2 = Clock.SYSTEM.millis();
        if (start == null || start.exit() != 0) {
            return new Outcome(
                    State.FAILED,
                    "could not start the Windows engine (jk.exe engine start"
                            + (start == null ? " did not finish" : " exited " + start.exit()) + ")");
        }
        if (start.out().contains("already running")) {
            // The status could not see it (another Windows logon session, or no answer in time).
            return new Outcome(State.RUNNING, "the Windows engine is running (status could not tell; start said so)");
        }
        ChangeJournal.note(
                "JumpKick for Windows",
                "start",
                "Started the Windows engine (jk.exe engine start) so this WSL engine has a front door at 8910");
        return new Outcome(
                State.STARTED,
                "started the Windows engine (status " + (status == null ? "did not answer" : "exit " + status.exit())
                        + " in " + (t1 - t0) + " ms, start in " + (t2 - t1) + " ms)");
    }

    /**
     * Why this guest cannot pair with the engine whose {@code /api/identity} answer is {@code
     * answer} (as the PowerShell probe printed it), speaking front-door protocol {@code mine}; null
     * when it can, or when no host engine answered (nothing to compare with).
     */
    static @Nullable String mismatch(@Nullable String answer, int mine) {
        if (answer == null) return null;
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        if (open < 0 || close < open) return null;
        Map<?, ?> id;
        try {
            if (!(MiniJson.parse(answer.substring(open, close + 1)) instanceof Map<?, ?> m)) return null;
            id = m;
        } catch (RuntimeException notJson) {
            return null;
        }
        if (!"host".equals(id.get("role"))) return null;
        String version = String.valueOf(id.get("version"));
        int theirs = id.get(FrontDoor.PROTOCOL_KEY) instanceof Number n ? n.intValue() : 0;
        if (theirs == mine) return null;
        if (theirs < mine) {
            return "the Windows engine (jk " + version + ") speaks front-door protocol " + theirs + " and this one "
                    + mine + "; update JumpKick for Windows (jk self update there) to see this engine at 8910";
        }
        return "the Windows engine (jk " + version + ") speaks front-door protocol " + theirs + " and this one " + mine
                + "; update JumpKick in this WSL distro (jk self update) to see this engine at 8910";
    }

    /** Whether {@code jk engine status -O json} said the engine runs. */
    static boolean running(@Nullable String json) {
        if (json == null) return false;
        for (String line : json.strip().split("\n")) {
            try {
                if (MiniJson.parse(line.strip()) instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("running"))) {
                    return true;
                }
            } catch (RuntimeException notJson) {
                // a progress or text line
            }
        }
        return false;
    }

    private static boolean set(@Nullable String v) {
        return v != null && !v.isBlank();
    }

    /**
     * From {@code /mnt/c} (no UNC warning), standard output into a file and nothing else attached:
     * a Windows program started through interop from the resident engine hung on its pipes (a
     * status that answers in a second from a shell sometimes took more than 30 s), so it gets
     * {@code /dev/null} for input, a file for output, and {@code seconds}; it is never killed, only
     * abandoned. A start waits a minute; anything else ten seconds.
     */
    static @Nullable Ran run(List<String> argv, long seconds) {
        Path out = null;
        try {
            out = Files.createTempFile("jk-interop-", ".out");
            Process p = fromWindowsFolder(argv)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) return null;
            return new Ran(p.exitValue(), Files.readString(out));
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a temp file the next start leaves too
                }
            }
        }
    }

    private static ProcessBuilder fromWindowsFolder(List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        Path windowsRoot = Path.of("/mnt/c");
        if (Files.isDirectory(windowsRoot)) pb.directory(windowsRoot.toFile());
        return pb;
    }
}
