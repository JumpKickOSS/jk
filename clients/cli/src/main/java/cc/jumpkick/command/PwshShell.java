// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.host.Os;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * PowerShell 7+ ({@code pwsh}). Windows PowerShell 5.1 is {@link WindowsPowerShellShell}.
 */
public final class PwshShell implements Shell {

    @Override
    public String name() {
        return "pwsh";
    }

    @Override
    public String setEnv(String key, @Nullable String value) {
        return "$Env:" + key + " = '" + pwshEscape(value) + "'\n";
    }

    @Override
    public String unsetEnv(String key) {
        return "Remove-Item -ErrorAction SilentlyContinue -Path Env:/" + key + "\n";
    }

    @Override
    public String activateScript(String jkExe) {
        return loadActivate(name(), jkExe);
    }

    @Override
    public String deactivateScript() {
        return loadDeactivate(name());
    }

    @Override
    public Path rcFile(Path home) {
        if (Os.isWindows()) {
            return home.resolve("Documents").resolve("PowerShell").resolve("Microsoft.PowerShell_profile.ps1");
        }
        return home.resolve(".config").resolve("powershell").resolve("Microsoft.PowerShell_profile.ps1");
    }

    @Override
    public String rcFileDisplay() {
        return Os.isWindows()
                ? "~\\Documents\\PowerShell\\Microsoft.PowerShell_profile.ps1"
                : "~/.config/powershell/Microsoft.PowerShell_profile.ps1";
    }

    @Override
    public String activationLine(String jkCommand) {
        return "& " + jkCommand + " activate " + name() + " | Out-String | Invoke-Expression";
    }

    @Override
    public String pathEnsureSnippet(String binDir) {
        // binDir is a double-quote-safe expression ($HOME/… or escaped absolute).
        //
        // Split on the separator and compare whole entries. A `-notlike "*$__jk_bin*"` substring
        // test says "already there" for a PATH that merely mentions the directory — an unrelated
        // `…\.jk\bin-backup` entry suppressed the prepend and left jk off PATH. The POSIX
        // snippets fence with `:` for the same reason.
        return "$__jk_bin = \"" + binDir + "\"\n"
                + "if (-not ($env:PATH -split [IO.Path]::PathSeparator | Where-Object { $_ -eq $__jk_bin })) {"
                + " $env:PATH = \"$__jk_bin$([IO.Path]::PathSeparator)$env:PATH\" }\n"
                + "Remove-Variable __jk_bin -ErrorAction SilentlyContinue\n";
    }

    @Override
    public String completionWiring(String storeDir) {
        String file = storeDir + "/completions/pwsh/jk.ps1";
        return "if (Test-Path -LiteralPath \"" + file + "\") { . \"" + file + "\" }\n";
    }

    @Override
    public String pathExpr(Path path, Path home) {
        return ShellPathExpr.pwsh(path, home);
    }

    @Override
    public String commandExpr(Path path, Path home) {
        return ShellPathExpr.pwshCommand(path, home);
    }

    /**
     * PowerShell single-quoted string escape: doubles embedded single quotes and replaces common
     * control characters with backtick escapes that work inside single-quoted strings.
     */
    static String loadActivate(String shellName, String jkExe) {
        return ShellResources.load("pwsh.ps1")
                .replace("__JK_EXE__", jkExe.replace("'", "''"))
                .replace("__JK_SHELL__", shellName);
    }

    static String loadDeactivate(String shellName) {
        return ShellResources.load("pwsh_deactivate.ps1").replace("__JK_SHELL__", shellName);
    }

    static String pwshEscape(String value) {
        var sb = new StringBuilder(value.length() + 2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\'' -> sb.append("''");
                case '\n' -> sb.append("`n");
                case '\r' -> sb.append("`r");
                case '\t' -> sb.append("`t");
                case '`' -> sb.append("``");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
