// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Fish flavour. Uses {@code --on-event fish_prompt} and {@code --on-variable PWD} for the
 * equivalent of precmd/chpwd. PATH is treated as a colon-joined string for emission — fish accepts
 * that via its {@code $PATH} compat shim.
 */
public final class FishShell implements Shell {

    @Override
    public String name() {
        return "fish";
    }

    @Override
    public String setEnv(String key, @Nullable String value) {
        // Fish PATH is a list, but `set -gx PATH <colon-string>` still works and
        // is what the rest of the ecosystem (mise, asdf, direnv) emits too —
        // fish auto-splits on the colon when the variable name is PATH.
        return "set -gx " + key + " " + PosixQuote.quote(value) + "\n";
    }

    @Override
    public String unsetEnv(String key) {
        return "set -e " + key + "\n";
    }

    @Override
    public String activateScript(String jkExe) {
        return ShellResources.load("fish.fish").replace("__JK_EXE__", PosixQuote.quote(jkExe));
    }

    @Override
    public String deactivateScript() {
        return ShellResources.load("fish_deactivate.fish");
    }

    @Override
    public Path rcFile(Path home) {
        return home.resolve(".config").resolve("fish").resolve("config.fish");
    }

    @Override
    public String rcFileDisplay() {
        return "~/.config/fish/config.fish";
    }

    @Override
    public String activationLine(String jkCommand) {
        return jkCommand + " activate fish | source";
    }

    @Override
    public String pathEnsureSnippet(String binDir) {
        // fish_add_path is idempotent and prepends when missing.
        return "fish_add_path -g \"" + binDir + "\"\n";
    }

    @Override
    public String completionWiring(String storeDir) {
        String dir = storeDir + "/completions/fish";
        return "if test -d \"" + dir + "\"\n    set -gp fish_complete_path \"" + dir + "\"\nend\n";
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
