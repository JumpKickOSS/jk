// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import java.nio.file.Path;

/**
 * Zsh flavour. Uses {@code precmd} + {@code chpwd} hooks via {@code add-zsh-hook} so directory
 * changes update env immediately and a background-spawned tool can refresh on the next prompt.
 */
public final class ZshShell implements Shell {

    @Override
    public String name() {
        return "zsh";
    }

    @Override
    public String setEnv(String key, String value) {
        return "export " + key + "=" + PosixQuote.quote(value) + "\n";
    }

    @Override
    public String unsetEnv(String key) {
        return "unset " + key + "\n";
    }

    @Override
    public String activateScript(String jkExe) {
        return ShellResources.load("zsh.sh").replace("__JK_EXE__", PosixQuote.quote(jkExe));
    }

    @Override
    public String deactivateScript() {
        return ShellResources.load("zsh_deactivate.sh");
    }

    @Override
    public Path rcFile(Path home) {
        return home.resolve(".zshrc");
    }

    @Override
    public String rcFileDisplay() {
        return "~/.zshrc";
    }

    @Override
    public String activationLine(String jkCommand) {
        return "eval \"$(" + jkCommand + " activate zsh)\"";
    }

    @Override
    public String pathEnsureSnippet(String binDir) {
        return "case \":$PATH:\" in *\":" + binDir + ":\"*) ;; *) export PATH=\"" + binDir + ":$PATH\" ;; esac\n";
    }

    @Override
    public String completionWiring(String storeDir) {
        String dir = storeDir + "/completions/zsh";
        return "fpath=(\"" + dir + "\" $fpath)\n";
    }

    @Override
    public String pathExpr(Path path, Path home) {
        return ShellPathExpr.posix(path, home);
    }

    @Override
    public String commandExpr(Path path, Path home) {
        return ShellPathExpr.posixCommand(path, home);
    }
}
