// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.task.ToolIdentity;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which programs other than git a test class started read nothing its key does not hold: a utility
 * that reads only what its command names ({@link #UTILITIES}: {@code sleep}, {@code setsid}, {@code
 * sh -c "exit 0"}, {@code ps}), as an administrator installed it or {@code PATH} finds it, or a
 * program the test wrote in its own temp root, whose command names no file but under the test
 * JVMs' temp roots and the operating system's own trees.
 *
 * <p>Such a program keys a class by its identity, as the suite's stamp names it ({@link
 * SuitePrograms}), or as {@link ClassReads#WRITTEN_PROGRAM} for one the test wrote, whose bytes the
 * class's cone already decides. The recording keeps the command as one line with its words joined
 * by spaces; every word, split at the shell's quotes and operators, is judged (outside Windows,
 * where a backslash escapes a character rather than separates directories, both as written and
 * with its backslashes dropped):
 *
 * <ul>
 *   <li>a word naming a path lies under a temp root, {@code /dev}, {@code /proc} or a system
 *       program directory;
 *   <li>outside a temp root (the JVM's own directory is the module's), a bare word names no entry of
 *       the directory and holds no wildcard;
 *   <li>a bare word that names a program on {@code PATH} names one of {@link #UTILITIES}: a shell
 *       or a wrapper runs its words, and a build tool reads its user's settings;
 *   <li>no word expands a variable or a command ({@code $HOME}, a backquote; {@code "$@"} passes the
 *       command's own words), starts with {@code ~}, changes directory ({@code cd}), names another
 *       host ({@code ://}, {@code user@host:}) or runs git, whose repository {@link GitRuns} judges.
 * </ul>
 *
 * <p>A program the test wrote runs what the test wrote into it, which no command line shows; the
 * class's cone holds the code that wrote it.
 *
 * <p>What none of this sees is a path a program takes from its environment; the test JVM's is an
 * allow-list its suite's key holds, and what the test sets for its child is the test's own code.
 */
final class OwnPrograms {

    private OwnPrograms() {}

    /** A program named bare, as {@code PATH} resolves it. */
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");

    /** {@code user@host:path}, an ssh address. */
    private static final Pattern USER_AT_HOST = Pattern.compile("[^/\\\\:@]+@[^/\\\\:]+:.*");

    /** Where a word is cut: the shell's quotes, operators and an assignment's {@code =}. */
    private static final Pattern CUT = Pattern.compile("[\"'<>|;&()=,{}]+");

    /** The operating system's own state: a device, a process's. */
    private static final List<String> STATE_TREES = List.of("/dev/", "/proc/");

    /** The directories only an administrator installs programs into. */
    private static final List<String> PROGRAM_TREES =
            List.of("/bin/", "/sbin/", "/usr/bin/", "/usr/sbin/", "/usr/libexec/", "/System/");

    /**
     * The utilities that read nothing but what their command names and the environment: the POSIX
     * ones {@link ToolIdentity#stable} names, the shells that read no file of the user's when run
     * with a command, wrappers that run their words, and text filters.
     */
    static final Set<String> UTILITIES = Set.of(
            "setsid",
            "timeout",
            "bash",
            "dash",
            "head",
            "tail",
            "tr",
            "wc",
            "sort",
            "uniq",
            "cut",
            "grep",
            "sed",
            "awk",
            "dd",
            "sha256sum",
            "shasum",
            "md5sum",
            "base64",
            "basename",
            "dirname",
            "date",
            "whoami",
            "hostname",
            "yes",
            "seq",
            "expr",
            "tee",
            "stat");

    /** Words that move the shell to a directory no word names. */
    private static final Set<String> CHANGES_DIRECTORY = Set.of("cd", "pushd", "popd");

    /**
     * The name {@code command}, started in {@code directory} (null: the JVM's own, {@code cwd}), keys
     * its class by, or null when it could read what no recording sees. {@code temp} are the temp roots
     * no repository encloses ({@link GitRuns#unenclosed}); {@code path} is the {@code PATH} a bare
     * word is looked up on.
     */
    static @Nullable String tool(
            String command, @Nullable String directory, Path cwd, Collection<Path> temp, @Nullable String path) {
        String[] words = command.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return null;
        Path dir;
        try {
            dir = directory == null || directory.isBlank()
                    ? cwd.toAbsolutePath().normalize()
                    : cwd.resolve(directory).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return null;
        }
        String name = program(words[0], dir, temp);
        if (name == null) return null;
        boolean own = under(dir, temp);
        for (int i = 1; i < words.length; i++) {
            for (String part : CUT.split(words[i])) {
                if (!part.isEmpty() && !ownPart(part, dir, own, temp, path)) return null;
            }
        }
        return name;
    }

    /**
     * The name {@code program} keys by: the stamp's name for a utility ({@link ToolIdentity#toolName}),
     * {@link ClassReads#WRITTEN_PROGRAM} for one in a temp root, or null for anything else (a
     * checked-in script, a program in jk's store or this user's home, a JDK's).
     */
    private static @Nullable String program(String program, Path dir, Collection<Path> temp) {
        String name = ToolIdentity.toolName(program);
        if (BARE.matcher(program).matches()) return utility(program) ? program : null;
        Path file;
        try {
            file = dir.resolve(program).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return null;
        }
        if (under(file, temp)) return ClassReads.WRITTEN_PROGRAM;
        if (!Path.of(program).isAbsolute()) return null;
        return utility(name)
                        && Files.isRegularFile(file)
                        && SuitePrograms.administered(file)
                        && startsWithAny(file, PROGRAM_TREES)
                ? name
                : null;
    }

    private static boolean utility(String name) {
        return ToolIdentity.stable(name) || UTILITIES.contains(name);
    }

    private static boolean ownPart(String part, Path dir, boolean own, Collection<Path> temp, @Nullable String path) {
        // Outside Windows a backslash is no directory: the shell drops one that escapes a character
        // outside quotes and keeps it inside single quotes, so the program gets either word.
        if (!Os.isWindows() && part.indexOf('\\') >= 0) {
            String unescaped = part.replace("\\", "");
            return ownWord(part, dir, own, temp, path)
                    && (unescaped.isEmpty() || ownWord(unescaped, dir, own, temp, path));
        }
        return ownWord(part, dir, own, temp, path);
    }

    private static boolean ownWord(String part, Path dir, boolean own, Collection<Path> temp, @Nullable String path) {
        if (part.equals("$@")) return true;
        if (part.contains("$") || part.contains("`") || part.startsWith("~")) return false;
        if (part.contains("://") || USER_AT_HOST.matcher(part).matches()) return false;
        if (CHANGES_DIRECTORY.contains(part)) return false;
        if (ToolIdentity.toolName(part).equals(GitRuns.GIT)) return false;
        boolean pathLike =
                part.contains("/") || (Os.isWindows() && part.contains("\\")) || part.equals(".") || part.equals("..");
        if (!pathLike) {
            if (BARE.matcher(part).matches() && !utility(part) && onPath(part, path)) return false;
            if (own) return true;
            if (part.indexOf('*') >= 0 || part.indexOf('?') >= 0 || part.indexOf('[') >= 0) return false;
            if (part.startsWith("-")) return true;
            try {
                return !Files.exists(dir.resolve(part));
            } catch (InvalidPathException odd) {
                return true;
            }
        }
        Path p;
        try {
            p = dir.resolve(part).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return false;
        }
        if (under(p, temp) || startsWithAny(p, STATE_TREES) || startsWithAny(rooted(part), STATE_TREES)) return true;
        // An installed program's path is one more program the command runs.
        return startsWithAny(p, PROGRAM_TREES) && (utility(ToolIdentity.toolName(part)) || !Files.isRegularFile(p));
    }

    /**
     * {@code part} normalized as written when it starts at {@code /}, which on Windows a shell like
     * Git Bash reads as its own tree ({@code /dev/zero}) and resolving would give the drive's root;
     * an empty path otherwise.
     */
    private static Path rooted(String part) {
        if (!part.startsWith("/")) return Path.of("");
        try {
            return Path.of(part).normalize();
        } catch (InvalidPathException odd) {
            return Path.of("");
        }
    }

    /**
     * Whether {@code name} is a runnable file in a directory of {@code path}: on Windows also with
     * each {@code PATHEXT} extension, as Windows finds {@code mvn} as {@code mvn.cmd}.
     */
    private static boolean onPath(String name, @Nullable String path) {
        List<String> extensions = Os.isWindows() ? PathUtil.windowsExecutableExtensions() : List.of();
        for (String entry : SearchPath.entries(path)) {
            Path d = SearchPath.path(entry);
            if (d == null) continue;
            try {
                if (runnable(d.resolve(name))) return true;
                for (String ext : extensions) {
                    if (runnable(d.resolve(name + ext))) return true;
                }
            } catch (InvalidPathException odd) {
                // no file of that directory
            }
        }
        return false;
    }

    private static boolean runnable(Path f) {
        return Files.isRegularFile(f) && PathUtil.isRunnable(f);
    }

    private static boolean startsWithAny(Path p, List<String> trees) {
        String path = p.toString().replace('\\', '/');
        for (String tree : trees) {
            if (path.startsWith(tree)) return true;
        }
        return false;
    }

    private static boolean under(Path p, Collection<Path> roots) {
        for (Path r : roots) {
            if (p.startsWith(r)) return true;
        }
        return false;
    }
}
