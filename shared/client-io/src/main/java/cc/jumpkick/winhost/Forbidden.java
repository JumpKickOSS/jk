// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The folders never excluded from Defender, whatever proposes them: a drive root, the profile and
 * every folder above it, Documents and Desktop themselves, anything under Downloads, AppData except
 * {@code JK_HOME}, Windows and the program folders. Only an absolute drive path without wildcards or
 * environment variables is a candidate at all, since Defender expands those as SYSTEM. The elevated
 * script applies the same rules again ({@link ElevatedScript}).
 *
 * @param profile {@code %USERPROFILE%}
 * @param home {@code JK_HOME}, the one folder allowed under AppData
 * @param systemRoot {@code %SystemRoot%}
 * @param programFolders {@code %ProgramFiles%}, {@code %ProgramFiles(x86)%} and {@code %ProgramData%}
 * @param drivePaths true on Windows, where a candidate must be a {@code X:\} path; false reads POSIX
 *     paths, for tests on other hosts
 */
public record Forbidden(
        String profile, String home, String systemRoot, List<String> programFolders, boolean drivePaths) {

    private static final Pattern DRIVE_PATH = Pattern.compile("[A-Za-z]:\\\\[^*?%\"<>|]*");
    private static final Pattern POSIX_PATH = Pattern.compile("/[^*?%\"<>|]*");

    public Forbidden {
        programFolders = List.copyOf(programFolders);
    }

    /** The rules for this machine's environment. */
    public static Forbidden of(HostFacts facts, String home) {
        List<String> programs = new ArrayList<>();
        for (String name : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
            String v = facts.env(name);
            if (v != null && !v.isBlank()) programs.add(v);
        }
        String profile = facts.env("USERPROFILE");
        String root = facts.env("SystemRoot");
        return new Forbidden(
                profile == null ? "C:\\Users\\Default" : profile,
                home,
                root == null ? "C:\\Windows" : root,
                programs,
                true);
    }

    /** Why {@code path} may not be excluded, or null when it may. */
    public @Nullable String reason(String path) {
        if (!(drivePaths ? DRIVE_PATH : POSIX_PATH).matcher(path).matches())
            return "not an absolute drive path without wildcards";
        if (WinPaths.key(path).length() <= 3) return "a drive root";
        for (String p : List.of(profile, systemRoot)) {
            if (WinPaths.under(p, path)) return "the profile, Windows, or a folder above them";
        }
        for (String p : programFolders) if (WinPaths.under(p, path)) return "a program folder or one above it";
        for (String name : List.of("Documents", "Desktop")) {
            if (WinPaths.key(path).equals(WinPaths.key(WinPaths.join(profile, name)))) return "the whole " + name;
        }
        if (WinPaths.under(path, WinPaths.join(profile, "Downloads"))) return "under Downloads";
        if (WinPaths.under(path, systemRoot)) return "under Windows";
        for (String p : programFolders) if (WinPaths.under(path, p)) return "under a program folder";
        if (WinPaths.under(path, WinPaths.join(profile, "AppData")) && !WinPaths.under(path, home)) {
            return "under AppData";
        }
        return null;
    }
}
