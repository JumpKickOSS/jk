// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The setup changes a standard user makes alone, without the elevated script. */
public interface UserCommands {

    /** {@code git config --global core.longpaths true}; true when it worked. */
    boolean gitLongPaths();

    /** CurrentUser's execution policy set to {@code RemoteSigned}; true when it worked. */
    boolean executionPolicy();

    /** This machine's git and Windows PowerShell. */
    static UserCommands live() {
        return new UserCommands() {
            @Override
            public boolean gitLongPaths() {
                return run(List.of("git", "config", "--global", "core.longpaths", "true"));
            }

            @Override
            public boolean executionPolicy() {
                return run(List.of(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force"));
            }
        };
    }

    private static boolean run(List<String> argv) {
        try {
            Process p = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
