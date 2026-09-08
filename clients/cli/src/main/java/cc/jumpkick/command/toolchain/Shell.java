// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import cc.jumpkick.host.Os;
import cc.jumpkick.terminal.posix.PosixPasswd;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Per-shell syntax for environment-modification commands emitted by {@code jk hook-env}. Each
 * {@link Shell} returns a single newline-terminated line that the shell can {@code eval} / {@code
 * source} verbatim.
 *
 * <p>Modelled on mise's {@code Shell} trait but trimmed to the shells we target: bash, zsh, fish,
 * PowerShell 7 ({@code pwsh}), and Windows PowerShell 5.1 ({@code powershell}).
 */
public sealed interface Shell permits BashShell, ZshShell, FishShell, PwshShell, WindowsPowerShellShell {

    /**
     * Canonical short name used both as the picocli value and in serialized state (e.g. {@code
     * __JK_SHELL=bash}).
     */
    String name();

    /** Render {@code export FOO=bar} (or the shell's equivalent). */
    String setEnv(String key, String value);

    /** Render the {@code unset FOO} statement (or the shell's equivalent). */
    String unsetEnv(String key);

    /**
     * Render the directory-aware hook body of {@code jk activate &lt;shell&gt;}. {@code jkExe} is
     * the resolved absolute path to the {@code jk} binary (embedded as {@code __JK_EXE} for
     * hook-env). PATH ensure and completions are composed separately by the caller.
     */
    String activateScript(String jkExe);

    /** Render the deactivation script (undoes {@link #activateScript}). */
    String deactivateScript();

    /**
     * RC file the activation line should be appended to. This is the file the user's interactive
     * shell sources at startup — {@code .zshrc} (not {@code .zshenv}), {@code .bashrc}, {@code
     * config/fish/config.fish}, PowerShell 7 {@code $PROFILE}, or Windows PowerShell 5.1 {@code
     * $PROFILE}.
     */
    Path rcFile(Path home);

    /** {@code ~}-prefixed display form of {@link #rcFile} for user-facing prompts. */
    String rcFileDisplay();

    /**
     * Single rc line: eval/source {@code jk activate &lt;shell&gt;} via a home-relative or absolute
     * path to the binary so PATH need not be set first. {@code jkCommand} is a shell-ready command
     * word (e.g. {@code "$HOME/.jk/bin/jk"} or {@code /opt/jk/bin/jk}).
     */
    String activationLine(String jkCommand);

    /**
     * Snippet that ensures {@code binDir} is on PATH when missing (idempotent). {@code binDir} is a
     * shell expression ({@code $HOME/.jk/bin} or an absolute path). Ends with newline.
     */
    String pathEnsureSnippet(String binDir);

    /**
     * Snippet that wires completions from {@code storeDir}/completions/&lt;shell&gt;. {@code storeDir}
     * is a shell expression. Ends with newline; empty when unsupported.
     */
    String completionWiring(String storeDir);

    /**
     * Full stdout of {@code jk activate &lt;shell&gt;}: PATH ensure, directory hooks, completions.
     * {@code jkExe} is the absolute path embedded for hook-env; {@code binDir}/{@code storeDir} are
     * live paths converted to {@code $HOME}-relative expressions when under {@code home}.
     */
    default String fullActivateScript(String jkExe, Path binDir, Path storeDir, Path home) {
        String binExpr = pathExpr(binDir, home);
        String storeExpr = pathExpr(storeDir, home);
        StringBuilder sb = new StringBuilder();
        sb.append(pathEnsureSnippet(binExpr));
        sb.append(activateScript(jkExe));
        String completions = completionWiring(storeExpr);
        if (completions != null && !completions.isBlank()) {
            sb.append(completions);
            if (!completions.endsWith("\n")) sb.append('\n');
        }
        return sb.toString();
    }

    /** Path expression for this shell ({@code $HOME/…} when under home). */
    String pathExpr(Path path, Path home);

    /** Command-word expression for invoking {@code path} (quoted {@code $HOME/…} when under home). */
    String commandExpr(Path path, Path home);

    /** Resolve a shell from its name (case-insensitive). */
    static Optional<Shell> byName(String name) {
        if (name == null) return Optional.empty();
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "bash", "sh" -> Optional.of(new BashShell());
            case "zsh" -> Optional.of(new ZshShell());
            case "fish" -> Optional.of(new FishShell());
            case "pwsh", "pwsh.exe" -> Optional.of(new PwshShell());
            case "powershell", "powershell.exe" -> Optional.of(new WindowsPowerShellShell());
            default -> Optional.empty();
        };
    }

    /**
     * Profiles {@code jk activate} (no shell name) writes: the login shell ({@code $SHELL}, then
     * {@code pw_shell}) whether or not its rc file exists yet, the platform default(s) — zsh on
     * macOS, bash on Linux, both PowerShell profiles on Windows — and any other supported rc file
     * that already exists (Git Bash, fish, extra PowerShell, …).
     *
     * <p>The login shell comes first and is written unconditionally: a fish or zsh user on Linux
     * with no {@code config.fish} / {@code .zshrc} yet would otherwise get only a {@code .bashrc}
     * their interactive shell never sources, and {@code jk activate} would report success while
     * {@code jkx} stayed off PATH.
     */
    static List<Shell> installTargets(Path home) {
        return installTargets(
                home,
                Os.name(),
                System.getenv("SHELL"),
                PosixPasswd.loginShell().orElse(null));
    }

    /** Test seam — caller supplies {@code os.name}; no login shell is known. */
    static List<Shell> installTargets(Path home, String osName) {
        return installTargets(home, osName, null, null);
    }

    /**
     * Test seam — caller supplies {@code os.name}, {@code $SHELL} and {@code pw_shell}. Script hosts
     * ({@code sh}, {@code dash}) are not interactive shells and do not count as a login shell.
     */
    static List<Shell> installTargets(
            Path home, String osName, @Nullable String shellEnv, @Nullable String passwdShell) {
        List<Shell> targets = new ArrayList<>();
        loginShell(shellEnv, passwdShell).ifPresent(targets::add);
        List<Shell> platform = Os.isWindows(osName)
                ? List.of(new PwshShell(), new WindowsPowerShellShell())
                : List.of(Os.isDarwin(osName) ? new ZshShell() : new BashShell());
        for (Shell shell : platform) {
            if (!named(targets, shell.name())) {
                targets.add(shell);
            }
        }
        for (Shell shell : all()) {
            if (named(targets, shell.name())) {
                continue;
            }
            if (shell instanceof WindowsPowerShellShell && !Os.isWindows(osName)) {
                continue;
            }
            if (home != null && Files.isRegularFile(shell.rcFile(home))) {
                targets.add(shell);
            }
        }
        return List.copyOf(targets);
    }

    /** The login shell from {@code $SHELL}, else {@code pw_shell}; empty when neither names one. */
    private static Optional<Shell> loginShell(@Nullable String shellEnv, @Nullable String passwdShell) {
        Optional<Shell> fromEnv = interactive(shellEnv);
        return fromEnv.isPresent() ? fromEnv : interactive(passwdShell);
    }

    /** {@link #detect(String)} minus the script hosts: {@code sh}/{@code dash} are not a login shell to write for. */
    private static Optional<Shell> interactive(@Nullable String rawShell) {
        String name = basename(rawShell);
        return scriptHost(name) ? Optional.empty() : byName(name);
    }

    private static List<Shell> all() {
        return List.of(new BashShell(), new ZshShell(), new FishShell(), new PwshShell(), new WindowsPowerShellShell());
    }

    private static boolean named(List<Shell> shells, String name) {
        for (Shell shell : shells) {
            if (shell.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best-effort single-shell guess for commands that still want one rc ({@code jk jdk
     * update-shell}). {@code jk activate} with no name uses {@link #installTargets} instead.
     */
    static Optional<Shell> detect() {
        Path home = Path.of(System.getProperty("user.home", ""));
        return detect(System.getenv("SHELL"), PosixPasswd.loginShell().orElse(null), home, Os.name());
    }

    /** Map a raw shell path or name ({@code $SHELL}, {@code bash}, …). Empty when unset or unsupported. */
    static Optional<Shell> detect(@Nullable String rawShell) {
        return byName(basename(rawShell));
    }

    /**
     * Shells {@code jk doctor} should inspect: login ({@code $SHELL}, then {@code pw_shell}) and the
     * parent process, if that parent is a supported interactive shell. Script hosts ({@code sh},
     * {@code dash}) are ignored as parents so an installer or CI wrapper is not treated as bash.
     */
    static List<Shell> live(@Nullable String shellEnv, @Nullable String passwdShell, @Nullable String parentCommand) {
        LinkedHashMap<String, Shell> out = new LinkedHashMap<>();
        addLive(out, detect(shellEnv));
        addLive(out, detect(passwdShell));
        if (parentCommand != null && !parentCommand.isBlank() && !scriptHost(basename(parentCommand))) {
            addLive(out, detect(parentCommand));
        }
        return List.copyOf(out.values());
    }

    private static void addLive(LinkedHashMap<String, Shell> out, Optional<Shell> shell) {
        shell.ifPresent(s -> out.putIfAbsent(s.name(), s));
    }

    private static boolean scriptHost(String basename) {
        String name = basename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return Set.of("sh", "dash", "ash", "busybox").contains(name);
    }

    /**
     * Full detection chain. {@code shellEnv} is {@code $SHELL}; {@code passwdShell} is {@code
     * pw_shell} from the account database.
     */
    static Optional<Shell> detect(String shellEnv, @Nullable String passwdShell, Path home, String osName) {
        Optional<Shell> fromEnv = detect(shellEnv);
        if (fromEnv.isPresent()) {
            return fromEnv;
        }
        Optional<Shell> fromPasswd = detect(passwdShell);
        if (fromPasswd.isPresent()) {
            return fromPasswd;
        }
        Optional<Shell> fromRc = uniqueExistingRc(home);
        if (fromRc.isPresent()) {
            return fromRc;
        }
        return osDefault(osName);
    }

    private static String basename(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        return slash >= 0 ? raw.substring(slash + 1) : raw;
    }

    private static Optional<Shell> uniqueExistingRc(Path home) {
        if (home == null) {
            return Optional.empty();
        }
        List<Shell> found = new ArrayList<>();
        for (Shell shell : all()) {
            if (Files.isRegularFile(shell.rcFile(home))) {
                found.add(shell);
            }
        }
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    private static Optional<Shell> osDefault(String osName) {
        if (Os.isDarwin(osName)) {
            return Optional.of(new ZshShell());
        }
        if (Os.isWindows(osName)) {
            return Optional.of(new PwshShell());
        }
        return Optional.of(new BashShell());
    }
}
