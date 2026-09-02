// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-shell syntax for environment-modification commands emitted by {@code jk hook-env}. Each
 * {@link Shell} returns a single newline-terminated line that the shell can {@code eval} / {@code
 * source} verbatim.
 *
 * <p>Modelled on mise's {@code Shell} trait but trimmed to the four shells we target: bash, zsh,
 * fish, PowerShell. Other shells (xonsh, nushell, elvish) are intentionally absent — adding them is
 * the obvious extension.
 */
public sealed interface Shell permits BashShell, ZshShell, FishShell, PwshShell {

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
     * config/fish/config.fish}, {@code $PROFILE} on PowerShell.
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
            case "pwsh", "powershell" -> Optional.of(new PwshShell());
            default -> Optional.empty();
        };
    }

    /** Detect the user's shell from {@code $SHELL}. */
    static Optional<Shell> detect() {
        return detect(System.getenv("SHELL"));
    }

    /** Test seam — caller supplies the raw {@code $SHELL} value. */
    static Optional<Shell> detect(String rawShell) {
        if (rawShell == null || rawShell.isBlank()) return Optional.empty();
        int slash = rawShell.lastIndexOf('/');
        String name = slash >= 0 ? rawShell.substring(slash + 1) : rawShell;
        return byName(name);
    }
}
