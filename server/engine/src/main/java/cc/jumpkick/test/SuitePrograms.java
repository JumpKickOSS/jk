// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.ToolIdentity;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A program a suite was found to start, as its stamp keys it and as its read list names it.
 *
 * <p>What a program is decides how it keys, never where this host happens to keep it:
 *
 * <ul>
 *   <li>a program in jk's store ({@code store:tools/node/24.22.0/bin/node}) keys by that path,
 *       which names the tool and the version jk fetched and checked, on every platform;
 *   <li>a file of the workspace or under another of jk's roots (a checked-in script) keys by
 *       content, named relative to its root;
 *   <li>a POSIX utility whose version is the operating system's ({@link ToolIdentity#STABLE}:
 *       {@code sh}, {@code sleep}, {@code ps}) keys by name and OS family alone, however it was
 *       named ({@code /bin/sh} and {@code sh} are one tool);
 *   <li>a tool the module declares in {@code [test] tools} keys by that declaration, which already
 *       carries its version, and adds nothing of its own here;
 *   <li>any other tool keys by name and what it says to {@code --version} ({@link ToolIdentity}):
 *       a bare name as the PATH the test JVM gets resolves it, a path an administrator installed
 *       ({@code /usr/bin/git}, {@code /usr/libexec/java_home}) as that binary; a program in a
 *       place this user may write ({@code /opt/homebrew/bin/node}, {@code ~/.jdks/…/bin/java})
 *       keys by content, since running a binary a list from elsewhere names is safe only for what
 *       an administrator installed;
 *   <li>a relative path ({@code ./jk}) names a file in the test's own working directory, which no
 *       stamp can see before the run: it keys as {@code relative}.
 * </ul>
 *
 * <p>The read list ({@link SuiteReadLists}) names each as {@code proc:<name>}, {@code
 * proc:<root>:<path>}, {@code proc:sys:<path>} (an absolute path outside the user's home, its
 * leading slash dropped) or {@code proc:rel:<encoded>}; a program in the user's home outside
 * jk's roots names the user and gives the suite no list.
 */
final class SuitePrograms {

    private SuitePrograms() {}

    /** The read-list prefix of a started program. */
    static final String LIST_PREFIX = "proc:";

    private static final String SYSTEM = "sys:";
    private static final String STORE = "store:";
    private static final String RELATIVE = "rel:";

    /** A program named bare, as a list from elsewhere may name one. */
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    /** Directories only an administrator installs into, though a root user may write them. */
    private static final List<String> SYSTEM_DIRS =
            List.of("/bin/", "/sbin/", "/usr/bin/", "/usr/sbin/", "/usr/libexec/", "/System/");

    /** The stamp token of {@code program}: {@code proc:<name>=<identity>}. */
    static String token(Path root, String program, Path moduleDir, Collection<String> declared) {
        Path file = absolute(program);
        if (file != null) {
            String spelled = SuiteReads.spellUnder(root, file);
            // jk's store keeps a tool under its name and version (tools/node/24.22.0/bin/node),
            // checked when it was fetched: that path is what it is, on every platform.
            if (spelled.startsWith(STORE))
                return LIST_PREFIX + spelled + "=" + (Files.isRegularFile(file) ? "store" : "absent");
            if (!spelled.equals(slashed(file))) return LIST_PREFIX + spelled + "=" + content(file);
            String tool = ToolIdentity.toolName(program);
            if (!Files.isRegularFile(file)) return LIST_PREFIX + tool + "=absent";
            if (ToolIdentity.stable(tool)) return LIST_PREFIX + tool + "=" + ToolIdentity.osFamily();
            if (declared.contains(tool)) return LIST_PREFIX + tool + "=declared";
            return LIST_PREFIX + tool + "="
                    + (administered(file) ? ToolIdentity.ofProgram(file, moduleDir) : content(file));
        }
        if (program.contains("/") || program.contains("\\")) return LIST_PREFIX + program + "=relative";
        if (ToolIdentity.stable(program)) return LIST_PREFIX + program + "=" + ToolIdentity.osFamily();
        if (declared.contains(program)) return LIST_PREFIX + program + "=declared";
        return LIST_PREFIX + program + "="
                + ToolIdentity.of(program, BuildEnv.machine().get("PATH"), moduleDir);
    }

    /** {@code program} as a read list may carry it, or null when no portable name carries it. */
    static @Nullable String portable(Path workspace, String program) {
        Path file = absolute(program);
        if (file == null) {
            if (program.contains("/") || program.contains("\\"))
                return LIST_PREFIX + RELATIVE + URLEncoder.encode(program, StandardCharsets.UTF_8);
            return BARE.matcher(program).matches() ? LIST_PREFIX + program : null;
        }
        String kept = SuiteReads.keep(workspace, file);
        if (SuiteReads.spelledUnderRoot(kept)) return LIST_PREFIX + kept;
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && file.startsWith(Path.of(home))) return null;
        String path = slashed(file);
        return LIST_PREFIX + SYSTEM + (path.startsWith("/") ? path.substring(1) : path);
    }

    /** The program a list entry {@link #portable} wrote names on this host, or null when the entry is none. */
    static @Nullable String fromPortable(Path workspace, String entry) {
        if (!entry.startsWith(LIST_PREFIX)) return null;
        String rest = entry.substring(LIST_PREFIX.length());
        if (rest.startsWith(RELATIVE)) {
            String program = URLDecoder.decode(rest.substring(RELATIVE.length()), StandardCharsets.UTF_8);
            return absolute(program) == null && !program.isBlank() ? program : null;
        }
        if (rest.startsWith(SYSTEM)) {
            String path = rest.substring(SYSTEM.length());
            String abs = path.length() > 1 && path.charAt(1) == ':' ? path : "/" + path;
            if (abs.contains("\\") || List.of(abs.split("/", -1)).contains("..")) return null;
            return absolute(abs) == null ? null : SuiteReads.hostSpelling(abs);
        }
        if (SuiteReads.spelledUnderRoot(rest)) {
            return SuiteReadLists.portableName(rest)
                    ? SuiteReads.unspell(workspace, rest).toString()
                    : null;
        }
        return BARE.matcher(rest).matches() ? rest : null;
    }

    /**
     * Whether {@code file} is a program an administrator installed: in a system directory, or one
     * this user may neither change nor replace.
     */
    static boolean administered(Path file) {
        String path = slashed(file);
        for (String dir : SYSTEM_DIRS) {
            if (path.startsWith(dir)) return true;
        }
        Path parent = file.getParent();
        return !Files.isWritable(file) && (parent == null || !Files.isWritable(parent));
    }

    private static String content(Path file) {
        try {
            return Files.isRegularFile(file) ? FileHashMemo.contentHash(file) : "absent";
        } catch (IOException unreadable) {
            return "unreadable";
        }
    }

    private static @Nullable Path absolute(String program) {
        try {
            Path p = Path.of(program);
            return p.isAbsolute() ? p.normalize() : null;
        } catch (InvalidPathException notAPath) {
            return null;
        }
    }

    private static String slashed(Path p) {
        return p.toString().replace('\\', '/');
    }
}
