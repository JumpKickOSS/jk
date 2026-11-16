// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ProcessEnv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Installs JumpKick into one WSL distro, or removes it, from Windows. Every step runs {@code
 * wsl.exe -d <distro> --cd <folder> --exec <program> ./<script>}: the scripts are files this class
 * writes into a Windows folder of the operation's own, the distro's name is one validated against
 * the distros WSL lists, and the version reaches the distro through {@code WSLENV}, so no request's
 * text is ever parsed by a shell on either side.
 *
 * <p>The installer is JumpKick's own {@code install.sh}, run in the distro with {@code JK_VERSION}
 * pinned ({@link ReleasePin}), so it downloads and verifies the release there. A distro with no
 * {@code curl} or {@code wget}, or no {@code openssl} to check the signature, gets the release from
 * Windows instead: JumpKick for Windows downloads it and verifies it against the signed sums, and
 * the installer installs it from that folder. A WSL 1 distro, one whose default user is root (a
 * distro never opened, or imported and not given a user) and one without {@code bash} are refused,
 * each with its reason.
 */
public final class WslSetup {

    /** A distro name WSL accepts and nothing on the way can read as an option or a path. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** How long the probe of a distro may take, the distro's start included. */
    static final Duration PROBE = Duration.ofSeconds(60);

    /** How long the installer may run. */
    static final Duration INSTALL = Duration.ofMinutes(15);

    /** How long starting or stopping the distro's engine may take. */
    static final Duration ENGINE = Duration.ofMinutes(3);

    /** The environment names a step forwards into the distro when Windows has them. */
    static final List<String> FORWARDED = List.of(
            "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy", "no_proxy", "JK_RELEASES_URL");

    static final String PROBE_SH = """
            printf 'uid=%s\\n' "$(id -u)"
            for t in bash curl wget openssl gunzip; do
              if command -v "$t" >/dev/null 2>&1; then printf 'has=%s\\n' "$t"; fi
            done
            printf 'arch=%s\\n' "$(uname -m)"
            if [ -x "$HOME/.jk/bin/jk" ]; then
              printf 'jk=%s\\n' "$("$HOME/.jk/bin/jk" --version 2>/dev/null | head -n 1)"
            fi
            """;

    static final String LOCAL_SH = """
            set -e
            exec bash "$PWD/install.sh" "$JK_SETUP_DIST/jk"
            """;

    /**
     * A Linux dist ({@code jk build}'s {@code target/dist}: {@code jk}, {@code lib/jk-engine-<v>.jar},
     * {@code repos/}) in a Windows folder that installs in place of a published release: how a
     * development build of JumpKick for Windows is tried with a guest of the same tree, since a
     * {@code -SNAPSHOT} is never published. A development setting, not documented for users: read
     * from the Windows engine's own environment when it starts, never from a request, and honoured
     * only by a {@code -SNAPSHOT} engine and only for an absolute folder that holds a dist ({@link
     * #devDist}).
     */
    public static final String DIST_ENV = "JK_DEV_WSL_DIST";

    /**
     * The dist {@code named} (the engine environment's {@link #DIST_ENV}) when a JumpKick of version
     * {@code hostVersion} may install it: a {@code -SNAPSHOT}, an absolute folder, a dist inside;
     * null otherwise, so a release always installs a published, verified release.
     */
    public static @Nullable Path devDist(@Nullable String named, String hostVersion) {
        if (named == null || named.isBlank() || !hostVersion.endsWith("-SNAPSHOT")) return null;
        try {
            Path dist = Path.of(named.strip());
            return dist.isAbsolute() && distPin(dist) != null ? dist : null;
        } catch (IOException | RuntimeException notADist) {
            return null;
        }
    }

    static final String START_SH = """
            "$HOME/.jk/bin/jk" engine start
            """;

    static final String REMOVE_SH = """
            jk="$HOME/.jk/bin/jk"
            if [ -x "$jk" ]; then "$jk" engine stop -A --now >/dev/null 2>&1 || true; fi
            rm -rf "$HOME/.jk"
            for f in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.config/fish/config.fish"; do
              if [ -f "$f" ] && grep -qF '# >>> jk installer >>>' "$f"; then
                sed -i '/^# >>> jk installer >>>$/,/^# <<< jk installer <<<$/d' "$f"
              fi
            done
            if [ -e "$HOME/.jk" ]; then echo "could not remove $HOME/.jk"; exit 1; fi
            echo "removed $HOME/.jk"
            """;

    /** What a distro has, read by {@link #PROBE_SH}: its default user, the tools, its arch and JumpKick. */
    public record Probe(
            int uid,
            Set<String> tools,
            String arch,
            @Nullable String jumpKick) {
        public Probe {
            tools = Set.copyOf(tools);
        }

        boolean has(String tool) {
            return tools.contains(tool);
        }

        /** True when the distro can download and verify the release itself. */
        boolean downloads() {
            return (has("curl") || has("wget")) && has("openssl") && has("gunzip");
        }

        /** {@code 0.51.1} from {@code jk 0.51.1}; null without JumpKick. */
        @Nullable
        String version() {
            if (jumpKick == null) return null;
            String[] words = jumpKick.strip().split("\\s+");
            return words.length >= 2 ? words[1] : null;
        }
    }

    /** How a step ended: the line a person reads, and every line it printed for the change log. */
    public record Outcome(boolean ok, String line, List<String> output) {
        public Outcome {
            output = List.copyOf(output);
        }

        static Outcome ok(String line, List<String> output) {
            return new Outcome(true, line, output);
        }

        static Outcome failed(String line, List<String> output) {
            return new Outcome(false, line, output);
        }
    }

    /** Where the installer and, for a distro that cannot download, the release come from. */
    public interface Downloads {
        /** JumpKick's {@code install.sh}. */
        byte[] installer() throws IOException;

        /** The Linux client of {@code version} for {@code arch}, verified and inflated. */
        byte[] client(String version, String arch) throws IOException;

        /** {@code jk-engine-<version>.jar}, verified. */
        byte[] engine(String version) throws IOException;
    }

    private final WslShell shell;
    private final Downloads downloads;
    private final Map<String, String> windowsEnv;
    private final @Nullable Path localDist;

    public WslSetup(WslShell shell, Downloads downloads) {
        this(shell, downloads, ProcessEnv.all(), null);
    }

    /** As {@link #WslSetup(WslShell, Downloads)}, installing the Linux dist {@code localDist} ({@link #DIST_ENV}). */
    public WslSetup(WslShell shell, Downloads downloads, @Nullable Path localDist) {
        this(shell, downloads, ProcessEnv.all(), localDist);
    }

    WslSetup(WslShell shell, Downloads downloads, Map<String, String> windowsEnv, @Nullable Path localDist) {
        this.shell = shell;
        this.downloads = downloads;
        this.localDist = localDist;
        // Not copied: Windows' own map reads names regardless of case, as Windows does.
        this.windowsEnv = windowsEnv;
    }

    /**
     * The distro {@code name} names among {@code known}, matched as WSL matches (ignoring case); empty
     * when it is not one of them or not a name WSL could have given. Every step takes its distro from
     * here, never from a request's text.
     */
    public static Optional<WslDistros.Distro> find(String name, List<WslDistros.Distro> known) {
        if (!NAME.matcher(name).matches()) return Optional.empty();
        for (WslDistros.Distro d : known) {
            if (d.name().equalsIgnoreCase(name) && NAME.matcher(d.name()).matches()) return Optional.of(d);
        }
        return Optional.empty();
    }

    /** What {@code distro} has; empty when it did not start or answer. Starts a stopped distro. */
    public Optional<Probe> probe(WslDistros.Distro distro, Path dir, Consumer<String> progress) throws IOException {
        write(dir, "probe.sh", PROBE_SH);
        List<String> out = new ArrayList<>();
        int exit = shell.run(command(distro, dir, "/bin/sh", "./probe.sh"), Map.of(), PROBE, out::add);
        Map<String, String> facts = new LinkedHashMap<>();
        Set<String> tools = new LinkedHashSet<>();
        for (String line : out) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq), value = line.substring(eq + 1).strip();
            if (key.equals("has")) tools.add(value);
            else facts.putIfAbsent(key, value);
        }
        if (exit != 0 || !facts.containsKey("uid")) {
            progress.accept(distro.name() + " did not answer: " + last(out, "no output"));
            return Optional.empty();
        }
        int uid;
        try {
            uid = Integer.parseInt(facts.get("uid"));
        } catch (NumberFormatException e) {
            uid = -1;
        }
        String jk = facts.get("jk");
        return Optional.of(
                new Probe(uid, tools, facts.getOrDefault("arch", ""), jk == null || jk.isBlank() ? null : jk));
    }

    /**
     * Installs JumpKick {@code pin} into {@code distro} and starts its engine, the operation's files
     * under {@code dir}; each step's line goes to {@code progress}.
     */
    public Outcome install(WslDistros.Distro distro, ReleasePin.Pin pin, Path dir, Consumer<String> progress)
            throws IOException {
        List<String> log = new ArrayList<>();
        Consumer<String> both = line -> {
            log.add(line);
            progress.accept(line);
        };
        if (distro.version() == 1) {
            return Outcome.failed(
                    distro.name() + " runs on WSL 1, and JumpKick needs WSL 2: convert it with `wsl --set-version "
                            + distro.name() + " 2`",
                    log);
        }
        both.accept("checking " + distro.name());
        Probe probe = probe(distro, dir, both).orElse(null);
        if (probe == null) return Outcome.failed(distro.name() + " did not start or answer: " + last(log, ""), log);
        if (probe.uid() == 0) {
            return Outcome.failed(
                    distro.name() + "'s default user is root: open " + distro.name()
                            + " once to create your user (or name one under [user] in its /etc/wsl.conf), then"
                            + " install again; JumpKick installs into a person's account",
                    log);
        }
        if (!probe.has("bash")) {
            return Outcome.failed(
                    distro.name() + " has no bash, which JumpKick's installer needs: install bash there, then install"
                            + " again",
                    log);
        }
        Path dist = localDist;
        if (dist != null) {
            both.accept("installing JumpKick " + pin.version() + " in " + distro.name() + " (" + pin.why() + ")");
            Files.write(dir.resolve("install.sh"), downloads.installer());
            write(dir, "local.sh", LOCAL_SH);
            Outcome installed = installer(distro, pin, dir, "./local.sh", dist, both);
            if (!installed.ok()) return Outcome.failed(installed.line(), log);
        } else if (pin.version().equals(probe.version())) {
            both.accept(distro.name() + " already has JumpKick " + pin.version());
        } else {
            Outcome installed = probe.downloads()
                    ? runInstaller(distro, pin, dir, both)
                    : installFromWindows(distro, pin, probe, dir, both);
            if (!installed.ok()) return Outcome.failed(installed.line(), log);
        }
        both.accept("starting JumpKick's engine in " + distro.name());
        write(dir, "start.sh", START_SH);
        List<String> started = new ArrayList<>();
        int exit = shell.run(command(distro, dir, "/bin/sh", "./start.sh"), Map.of(), ENGINE, line -> {
            started.add(line);
            both.accept(line);
        });
        if (exit != 0) {
            return Outcome.failed(
                    "installed JumpKick " + pin.version() + " in " + distro.name() + ", but its engine did not start: "
                            + last(started, "exit " + exit),
                    log);
        }
        return Outcome.ok("installed JumpKick " + pin.version() + " in " + distro.name(), log);
    }

    /** The distro downloads and verifies the release itself, through the installer. */
    private Outcome runInstaller(WslDistros.Distro distro, ReleasePin.Pin pin, Path dir, Consumer<String> progress)
            throws IOException {
        progress.accept("installing JumpKick " + pin.version() + " in " + distro.name() + " (" + pin.why() + ")");
        Files.write(dir.resolve("install.sh"), downloads.installer());
        return installer(distro, pin, dir, "./install.sh", null, progress);
    }

    /**
     * A distro that cannot download or verify the release: Windows downloads it, checks it against the
     * release's signed sums, and lays it out as the installer installs a local build from.
     */
    private Outcome installFromWindows(
            WslDistros.Distro distro, ReleasePin.Pin pin, Probe probe, Path dir, Consumer<String> progress)
            throws IOException {
        List<String> missing = new ArrayList<>();
        if (!probe.has("curl") && !probe.has("wget")) missing.add("curl or wget");
        if (!probe.has("openssl")) missing.add("openssl");
        if (!probe.has("gunzip")) missing.add("gunzip");
        String arch = releaseArch(probe.arch());
        if (arch == null) {
            return Outcome.failed(
                    distro.name() + " is " + probe.arch() + ", which no JumpKick release serves", List.of());
        }
        progress.accept(distro.name() + " has no " + String.join(", no ", missing)
                + ": JumpKick for Windows downloads JumpKick " + pin.version() + " (" + pin.why()
                + "), verifies it against the release's signed checksums, and installs it from there");
        Files.write(dir.resolve("install.sh"), downloads.installer());
        Path dist = Files.createDirectories(dir.resolve("dist").resolve("lib"));
        Files.write(dir.resolve("dist").resolve("jk"), downloads.client(pin.version(), arch));
        Files.write(dist.resolve("jk-engine-" + pin.version() + ".jar"), downloads.engine(pin.version()));
        write(dir, "local.sh", LOCAL_SH);
        return installer(distro, pin, dir, "./local.sh", dir.resolve("dist"), progress);
    }

    /** Runs {@code script}; {@code dist} is the Windows folder of a dist it installs, null for a download. */
    private Outcome installer(
            WslDistros.Distro distro,
            ReleasePin.Pin pin,
            Path dir,
            String script,
            @Nullable Path dist,
            Consumer<String> progress) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JK_VERSION", pin.version());
        env.put("JK_NONINTERACTIVE", "1");
        String wslenv = wslenv(windowsEnv.get("WSLENV"));
        if (dist != null) {
            // The /p flag has WSL hand the folder over as the distro's own path to it.
            env.put("JK_SETUP_DIST", dist.toString());
            wslenv += ":JK_SETUP_DIST/p";
        }
        env.put("WSLENV", wslenv);
        Deque<String> tail = new ArrayDeque<>();
        int exit = shell.run(command(distro, dir, "/bin/bash", script), env, INSTALL, line -> {
            if (tail.size() == 4) tail.removeFirst();
            tail.addLast(line);
            progress.accept(line);
        });
        if (exit == 0) return Outcome.ok("installed", List.of());
        String why = exit == WslShell.TIMED_OUT
                ? "the installer ran past " + INSTALL.toMinutes() + " minutes and was stopped"
                : exit == WslShell.NOT_STARTED ? "wsl.exe could not be started" : failure(tail, exit);
        return Outcome.failed("JumpKick's installer failed in " + distro.name() + ": " + why, List.of());
    }

    /** Removes JumpKick from {@code distro}: its engines stopped, its home and its shell lines gone. */
    public Outcome remove(WslDistros.Distro distro, Path dir, Consumer<String> progress) throws IOException {
        List<String> log = new ArrayList<>();
        Consumer<String> both = line -> {
            log.add(line);
            progress.accept(line);
        };
        both.accept("removing JumpKick from " + distro.name());
        write(dir, "remove.sh", REMOVE_SH);
        List<String> out = new ArrayList<>();
        int exit = shell.run(command(distro, dir, "/bin/sh", "./remove.sh"), Map.of(), ENGINE, line -> {
            out.add(line);
            both.accept(line);
        });
        if (exit != 0) {
            return Outcome.failed(
                    "could not remove JumpKick from " + distro.name() + ": " + last(out, "exit " + exit), log);
        }
        return Outcome.ok("removed JumpKick from " + distro.name(), log);
    }

    /** {@code wsl.exe -d <distro> --cd <dir> --exec <program> <script>}: an array, never a shell line. */
    static List<String> command(WslDistros.Distro distro, Path dir, String program, String script) {
        if (!NAME.matcher(distro.name()).matches()) throw new IllegalArgumentException("not a distro name");
        return List.of("wsl.exe", "-d", distro.name(), "--cd", dir.toString(), "--exec", program, script);
    }

    /** {@code WSLENV} forwarding the version and the proxy settings Windows has, after the user's own. */
    String wslenv(@Nullable String existing) {
        List<String> names = new ArrayList<>();
        if (existing != null && !existing.isBlank()) names.add(existing.strip());
        names.add("JK_VERSION/u");
        names.add("JK_NONINTERACTIVE/u");
        Set<String> seen = new LinkedHashSet<>();
        for (String name : FORWARDED) {
            String v = windowsEnv.get(name);
            if (v != null && !v.isBlank() && seen.add(name.toUpperCase(Locale.ROOT))) names.add(name + "/u");
        }
        return String.join(":", names);
    }

    /**
     * The pin for the Linux dist in {@code dist}: the version its engine jar names; null when it is not
     * a dist ({@code jk} and {@code lib/jk-engine-<version>.jar}).
     */
    public static ReleasePin.@Nullable Pin distPin(Path dist) throws IOException {
        if (!Files.isRegularFile(dist.resolve("jk")) || !Files.isDirectory(dist.resolve("lib"))) return null;
        String[] found = {null};
        PathUtil.forEachChild(dist.resolve("lib"), (jar, attrs) -> {
            String name = jar.getFileName().toString();
            if (attrs.isRegularFile() && name.startsWith("jk-engine-") && name.endsWith(".jar")) {
                found[0] = name.substring("jk-engine-".length(), name.length() - ".jar".length());
                return false;
            }
            return true;
        });
        String version = found[0];
        if (version != null) return new ReleasePin.Pin(version, "the Linux dist " + DIST_ENV + " names, " + dist);
        return null;
    }

    /** The release's name for {@code uname -m}'s answer; null for one no release serves. */
    static @Nullable String releaseArch(String uname) {
        return switch (uname.strip().toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
    }

    /** The installer's own reason: its last line marked as an error, else its last line. */
    private static String failure(Deque<String> tail, int exit) {
        for (var it = tail.descendingIterator(); it.hasNext(); ) {
            String line = it.next();
            if (line.startsWith("x ") || line.startsWith("✖ "))
                return line.substring(2).strip();
        }
        return tail.isEmpty() ? "exit " + exit : tail.peekLast();
    }

    private static String last(List<String> lines, String fallback) {
        return lines.isEmpty() ? fallback : lines.getLast();
    }

    private static void write(Path dir, String name, String script) throws IOException {
        Files.writeString(dir.resolve(name), script.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }
}
