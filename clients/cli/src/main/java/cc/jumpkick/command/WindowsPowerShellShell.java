// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;

/**
 * Windows PowerShell 5.1 ({@code powershell.exe}). Same hook script as {@link PwshShell}; a
 * different {@code $PROFILE} path. Directory-change hooks still require PowerShell 7; 5.1 updates
 * at prompt, like bash.
 */
public final class WindowsPowerShellShell implements Shell {
    private final PwshShell pwsh = new PwshShell();

    @Override
    public String name() {
        return "powershell";
    }

    @Override
    public String setEnv(String key, String value) {
        return pwsh.setEnv(key, value);
    }

    @Override
    public String unsetEnv(String key) {
        return pwsh.unsetEnv(key);
    }

    @Override
    public String activateScript(String jkExe) {
        return PwshShell.loadActivate(name(), jkExe);
    }

    @Override
    public String deactivateScript() {
        return PwshShell.loadDeactivate(name());
    }

    @Override
    public Path rcFile(Path home) {
        return home.resolve("Documents").resolve("WindowsPowerShell").resolve("Microsoft.PowerShell_profile.ps1");
    }

    @Override
    public String rcFileDisplay() {
        return "~\\Documents\\WindowsPowerShell\\Microsoft.PowerShell_profile.ps1";
    }

    @Override
    public String activationLine(String jkCommand) {
        return "& " + jkCommand + " activate " + name() + " | Out-String | Invoke-Expression";
    }

    @Override
    public String pathEnsureSnippet(String binDir) {
        return pwsh.pathEnsureSnippet(binDir);
    }

    @Override
    public String completionWiring(String storeDir) {
        return pwsh.completionWiring(storeDir);
    }

    @Override
    public String pathExpr(Path path, Path home) {
        return pwsh.pathExpr(path, home);
    }

    @Override
    public String commandExpr(Path path, Path home) {
        return pwsh.commandExpr(path, home);
    }
}
