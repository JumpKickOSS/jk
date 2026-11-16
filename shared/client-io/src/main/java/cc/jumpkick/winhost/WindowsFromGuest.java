// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.desktop.PowerShell;
import cc.jumpkick.desktop.WslWindowsDesktop;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.WslInterop;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.repo.ReleaseArtifacts;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * JumpKick for Windows, offered and installed from a WSL guest whose Windows side has none: the one
 * setup row ("JumpKick for Windows"), how the guest tells whether Windows has JumpKick ({@code
 * %JK_HOME%} or {@code %USERPROFILE%\.jk}, then {@code bin\jk.exe}, through interop), and the install,
 * which runs JumpKick's own {@code install.ps1} in Windows PowerShell through interop, pinned to the
 * guest's version, as the person's own Windows account: a per-user install with no administrator
 * prompt. The installer starts the Windows engine; the guest's engine, already paired through the
 * Windows profile, then has a front door. The engine's offer after a person's build, {@code jk doctor
 * --fix} and {@code jk self setup-windows} all install through here.
 *
 * <p>Every value reaches PowerShell through {@link PowerShell#literal}, so no version, URL or folder
 * is ever parsed as script.
 */
public final class WindowsFromGuest {

    /** The row's id in the setup decision and in the memo's answered rows. */
    public static final String ROW = "jumpkick-windows";

    /** How the row names what it installs in {@link SetupChange#path}. */
    public static final String PATH = "windows:";

    public static final String QUESTION = "Add JumpKick for Windows";

    public static final String YES = "Install JumpKick for Windows";

    /** The one line a person's build prints when it offers the row; never a prompt. */
    public static final String HINT =
            "JumpKick for Windows adds a desktop dashboard and notifications: `jk self setup-windows`";

    /**
     * A Windows folder holding a Windows dist ({@code jk.exe}, {@code lib\jk-engine-<v>.jar}) and the
     * {@code install.ps1} to install it with, which installs in place of a published release: how a
     * development build of the guest is tried with a Windows side of the same tree, since a {@code
     * -SNAPSHOT} is never published. A development setting, not documented for users, honoured only by a
     * {@code -SNAPSHOT} ({@link #source}).
     */
    public static final String DIST_ENV = "JK_DEV_WINDOWS_DIST";

    /** How long the installer may take: it downloads the client, the engine and a JDK. */
    static final Duration INSTALL_WAIT = Duration.ofMinutes(15);

    private static final Duration START_WAIT = Duration.ofSeconds(60);

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    /** Runs a command from the guest and answers its standard output; null when it could not or failed. */
    @FunctionalInterface
    public interface Interop {
        @Nullable
        String output(List<String> argv);
    }

    /** Runs a Windows program from the guest to its end, each line of its output to {@code line}. */
    @FunctionalInterface
    public interface Runner {
        /** The exit code; -1 when it could not start or did not end within {@code wait}. */
        int run(List<String> argv, Duration wait, Consumer<String> line);
    }

    /** What the installer installs: a published release, or a development dist ({@link #DIST_ENV}). */
    public sealed interface Source {
        String version();

        /** The release {@code version}, by the {@code install.ps1} at {@code installer}. */
        record Release(String version, URI installer, String why) implements Source {}

        /** The dist in Windows folder {@code folder}, by the {@code install.ps1} in it. */
        record Dist(String version, String folder) implements Source {}
    }

    private WindowsFromGuest() {}

    /** The setup row: one per guest, answered once. */
    public static SetupChange row() {
        return new SetupChange(
                ROW,
                "JumpKick for Windows",
                "A dashboard on your desktop, notifications, and one front door at localhost:8910 for all your"
                        + " distros.",
                "Installs JumpKick for Windows into your Windows account, with no administrator prompt, starts"
                        + " it and pairs this distro's engine with it.",
                SetupChange.Kind.ENVIRONMENT,
                PATH);
    }

    /** The lines under the question. */
    public static List<String> detail() {
        return List.of(
                "This WSL distro has JumpKick; Windows has none.",
                "Declining is fine: builds here keep working, with this distro's own dashboard.",
                "The answer is remembered; `jk doctor --fix` asks again, `jk self setup-windows` installs.");
    }

    /**
     * The Windows user's JumpKick home as a Windows path: {@code %JK_HOME%}, else {@code
     * %USERPROFILE%\.jk}; null when interop cannot say.
     */
    public static @Nullable String windowsHome(Interop interop) {
        String home = windowsVar(interop, "JK_HOME");
        if (home != null) return home;
        String profile = windowsVar(interop, "USERPROFILE");
        return profile == null ? null : profile + "\\.jk";
    }

    /** Windows' value of {@code name}, as {@code cmd.exe} expands it; null when unset. */
    private static @Nullable String windowsVar(Interop interop, String name) {
        String out = interop.output(List.of("cmd.exe", "/d", "/c", "echo %" + name + "%"));
        if (out == null) return null;
        String v = out.strip();
        return v.isEmpty() || v.contains("%") || v.contains("\n") ? null : v;
    }

    /** Windows' {@code jk.exe} as this guest reaches it; null when Windows has none or interop cannot say. */
    public static @Nullable Path windowsJk(Interop interop) {
        String home = windowsHome(interop);
        if (home == null) return null;
        String mapped = interop.output(List.of("wslpath", "-u", home + "\\bin\\jk.exe"));
        if (mapped == null || mapped.isBlank()) return null;
        Path jk = Path.of(mapped.strip());
        return Files.isRegularFile(jk) ? jk : null;
    }

    /**
     * Whether Windows lacks JumpKick: interop named its home and that home has no {@code jk.exe}. False
     * when interop cannot say, so a guest that cannot ask never offers.
     */
    public static boolean missing(Interop interop) {
        String home = windowsHome(interop);
        if (home == null) return false;
        String mapped = interop.output(List.of("wslpath", "-u", home + "\\bin\\jk.exe"));
        return mapped != null && !mapped.isBlank() && !Files.isRegularFile(Path.of(mapped.strip()));
    }

    /**
     * What a guest of version {@code guestVersion} installs: the dist {@code devDist} names when the
     * guest is a {@code -SNAPSHOT} and the folder holds {@code jk.exe} and {@code install.ps1}; else its
     * own release, or for a {@code -SNAPSHOT} the newest release at or below it ({@link ReleasePin});
     * null when no published release fits.
     */
    public static @Nullable Source source(
            String guestVersion,
            @Nullable String devDist,
            Interop interop,
            ReleasePin.Published published,
            ReleasePin.Latest latest,
            URI releases)
            throws IOException {
        if (devDist != null && !devDist.isBlank() && guestVersion.endsWith("-SNAPSHOT")) {
            String folder = devDist.strip();
            if (folder.endsWith("\\")) folder = folder.substring(0, folder.length() - 1);
            if (windowsFile(interop, folder + "\\jk.exe") && windowsFile(interop, folder + "\\install.ps1")) {
                return new Source.Dist(guestVersion, folder);
            }
        }
        ReleasePin.Pin pin = ReleasePin.of(guestVersion, published, latest);
        if (pin == null) return null;
        String why = pin.why().replace("JumpKick for Windows", "this distro's JumpKick");
        return new Source.Release(pin.version(), installer(releases), why);
    }

    /** The source for this guest from the live release site, {@link #DIST_ENV} read from {@code env}. */
    public static @Nullable Source source(String guestVersion, @Nullable String devDist, Interop interop)
            throws IOException {
        WslDownloads downloads = WslDownloads.live();
        return source(
                guestVersion,
                devDist,
                interop,
                downloads::published,
                downloads::latest,
                ReleaseArtifacts.releasesBase());
    }

    /** The {@code install.ps1} beside the release site, as {@code install.sh} is ({@link WslDownloads}). */
    static URI installer(URI releases) {
        String base = releases.toString();
        return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .resolve("install.ps1");
    }

    private static boolean windowsFile(Interop interop, String windowsPath) {
        String mapped = interop.output(List.of("wslpath", "-u", windowsPath));
        return mapped != null && !mapped.isBlank() && Files.isRegularFile(Path.of(mapped.strip()));
    }

    /** The PowerShell script that installs {@code source}: non-interactive, the version pinned. */
    static String script(Source source) {
        StringBuilder s = new StringBuilder(PowerShell.PRELUDE);
        s.append("$env:JK_NONINTERACTIVE = '1'\n");
        switch (source) {
            case Source.Release r -> {
                s.append("$env:JK_VERSION = ")
                        .append(PowerShell.literal(r.version()))
                        .append('\n');
                s.append("$ProgressPreference = 'SilentlyContinue'\n");
                s.append("Invoke-RestMethod -UseBasicParsing ")
                        .append(PowerShell.literal(r.installer().toString()))
                        .append(" | Invoke-Expression\n");
            }
            case Source.Dist d ->
                s.append("& ")
                        .append(PowerShell.literal(d.folder() + "\\install.ps1"))
                        .append(' ')
                        .append(PowerShell.literal(d.folder() + "\\jk.exe"))
                        .append('\n');
        }
        return s.toString();
    }

    /**
     * Installs {@code source} through {@code powershell} (the guest's path to Windows PowerShell),
     * each installer line to {@code progress}, then makes sure the Windows engine runs; answers how it
     * ended as {@link #ROW}'s result.
     */
    public static MachineSetup.Result install(
            Source source, String powershell, Runner runner, Interop interop, Consumer<String> progress) {
        String what = "JumpKick for Windows " + source.version();
        progress.accept("installing " + what + (source instanceof Source.Release r ? " (" + r.why() + ")" : ""));
        List<String> argv = new ArrayList<>(PowerShell.command(script(source)));
        argv.set(0, powershell);
        String[] last = {""};
        int exit = runner.run(argv, INSTALL_WAIT, line -> {
            String clean = ANSI.matcher(line).replaceAll("").strip();
            if (clean.isEmpty()) return;
            last[0] = clean;
            progress.accept(clean);
        });
        Path jk = windowsJk(interop);
        if (exit != 0 || jk == null) {
            String why = exit == -1
                    ? "the installer did not finish in " + INSTALL_WAIT.toMinutes() + " minutes"
                    : exit != 0 ? "the installer exited " + exit : "the installer left no jk.exe";
            return new MachineSetup.Result(
                    ROW,
                    false,
                    "could not install " + what + ": " + why + (last[0].isEmpty() ? "" : " (" + last[0] + ")"));
        }
        // The installer starts the engine; a start that finds it running says so and changes nothing.
        int started = runner.run(List.of(jk.toString(), "engine", "start", "-u", "-x"), START_WAIT, line -> {});
        return new MachineSetup.Result(
                ROW,
                true,
                started == 0
                        ? "installed " + what + "; its engine runs, the front door at localhost:8910"
                        : "installed " + what + "; its engine did not start (jk engine start exited " + started
                                + "), the next build here starts it");
    }

    /**
     * Installs JumpKick for Windows for a guest of version {@code version} from the live release site
     * (or the dist {@link #DIST_ENV} names in this process's environment), through Windows PowerShell.
     */
    public static MachineSetup.Result installFor(String version, Consumer<String> progress) {
        try {
            Source source = source(version, ProcessEnv.get(DIST_ENV), INTEROP);
            if (source == null) {
                return new MachineSetup.Result(ROW, false, "no published JumpKick release fits " + version);
            }
            String powershell = new WslWindowsDesktop(new WslWindowsDesktop.Shell() {
                        @Override
                        public @Nullable String output(List<String> argv) {
                            return INTEROP.output(argv);
                        }

                        @Override
                        public boolean run(List<String> argv) {
                            return INTEROP.output(argv) != null;
                        }
                    })
                    .powershell();
            return install(source, powershell, RUNNER, INTEROP, progress);
        } catch (IOException | RuntimeException e) {
            return new MachineSetup.Result(ROW, false, "could not install JumpKick for Windows: " + e.getMessage());
        }
    }

    /** The live interop: standard output alone, from a Windows folder, ten seconds at most. */
    public static final Interop INTEROP = argv -> {
        Path out = null;
        try {
            out = Files.createTempFile("jk-interop-", ".out");
            Process p = fromWindowsFolder(argv)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            return Files.readString(out);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            deleteQuietly(out);
        }
    };

    /**
     * The live runner: output into a file, never a pipe (an engine the program starts would hold a pipe
     * open for its life), read back line by line while it runs; a program past its wait is abandoned.
     */
    public static final Runner RUNNER = (argv, wait, line) -> {
        Path out = null;
        try {
            out = Files.createTempFile("jk-windows-setup-", ".out");
            Process p = fromWindowsFolder(argv)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(out.toFile())
                    .redirectErrorStream(true)
                    .start();
            long until = Clock.SYSTEM.nanos() + wait.toNanos();
            int[] read = {0};
            boolean ended = false;
            while (!ended && Clock.SYSTEM.nanos() < until) {
                ended = p.waitFor(500, TimeUnit.MILLISECONDS);
                read[0] = emit(out, read[0], line, ended);
            }
            return ended ? p.exitValue() : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            deleteQuietly(out);
        }
    };

    /** Hands the complete lines of {@code file} past {@code from} chars to {@code line}; the new offset. */
    static int emit(Path file, int from, Consumer<String> line, boolean all) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int at = from;
        while (true) {
            int nl = text.indexOf('\n', at);
            if (nl < 0) break;
            line.accept(text.substring(at, nl).replace("\r", ""));
            at = nl + 1;
        }
        if (all && at < text.length()) {
            line.accept(text.substring(at).replace("\r", ""));
            at = text.length();
        }
        return at;
    }

    private static ProcessBuilder fromWindowsFolder(List<String> argv) {
        ProcessBuilder pb = WslInterop.builder(argv);
        Path windowsRoot = Path.of("/mnt/c");
        if (Files.isDirectory(windowsRoot)) pb.directory(windowsRoot.toFile());
        return pb;
    }

    private static void deleteQuietly(@Nullable Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // a temp file the system cleans
        }
    }

    /** Records that the person answered the row, accepted or not, so no offer asks again. */
    public static void remember(Path memoFile, boolean accepted, long now) throws IOException {
        SetupMemo m = SetupMemo.read(memoFile);
        List<String> rows = new ArrayList<>(m.answeredRows());
        if (!rows.contains(ROW)) rows.add(ROW);
        new SetupMemo(
                        accepted ? SetupMemo.Answer.ACCEPTED : SetupMemo.Answer.DECLINED,
                        now,
                        m.checkedAt(),
                        m.verified(),
                        m.notExcluded(),
                        m.declined(),
                        m.refused(),
                        m.beforeMs(),
                        m.afterMs(),
                        rows)
                .write(memoFile);
    }

    /** Whether the person has answered the row. */
    public static boolean answered(Path memoFile) {
        return SetupMemo.read(memoFile).answeredRows().contains(ROW);
    }
}
