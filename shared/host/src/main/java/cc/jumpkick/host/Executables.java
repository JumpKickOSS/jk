// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Absolute paths for the programs jk starts by name. Windows looks for a bare program name in the
 * current directory before System32 and {@code PATH}, so a {@code reg.exe} in a cloned repository
 * would run in place of the real one; jk hands Windows the path instead. Elsewhere a bare name is
 * searched on {@code PATH} alone and is passed as written.
 */
public final class Executables {

    private Executables() {}

    /** {@code %SystemRoot%\System32\<exe>}, e.g. {@code reg.exe}; {@code C:\Windows} when the variable is unset. */
    public static String windowsSystem(String exe) {
        String root = ProcessEnv.get("SystemRoot");
        return (root == null || root.isBlank() ? "C:\\Windows" : root) + "\\System32\\" + exe;
    }

    /** This machine's Windows PowerShell, {@code powershell.exe} under System32. */
    public static String windowsPowerShell() {
        return windowsSystem("WindowsPowerShell\\v1.0\\powershell.exe");
    }

    /**
     * {@code program} as the path to start: on Windows a bare name is found in this process's
     * {@code PATH} ({@code .exe} added when it has no extension, as Windows adds it), null when no
     * entry has it. A name with a directory, and any name off Windows, is returned as written.
     */
    public static @Nullable String onPath(String program) {
        if (!Os.isWindows()) return program;
        return onWindowsPath(program, ProcessEnv.get("PATH"));
    }

    /**
     * {@code program}'s first match under an absolute entry of {@code path}. A relative or blank
     * entry is skipped: it names the current directory, which is what this lookup exists to avoid.
     */
    static @Nullable String onWindowsPath(String program, @Nullable String path) {
        if (program.indexOf('/') >= 0 || program.indexOf('\\') >= 0) return program;
        String file = program.indexOf('.') >= 0 ? program : program + ".exe";
        for (String entry : SearchPath.entries(path)) {
            Path dir = SearchPath.path(entry);
            if (dir == null || !dir.isAbsolute()) continue;
            Path candidate = dir.resolve(file);
            if (Files.isRegularFile(candidate)) return candidate.toString();
        }
        return null;
    }
}
