// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which {@code git} commands a test class started read nothing but a repository the test made.
 *
 * <p>A test JVM's git reads no configuration of whoever runs it: the launch sets {@code
 * GIT_CONFIG_NOSYSTEM} and points {@code GIT_CONFIG_GLOBAL} into the sandbox home, and the test
 * JVM's environment is an allow-list that passes no other {@code GIT_*} variable unless the module
 * declares it, which its suite's key holds. What is left is the binary, which the suite's stamp
 * keys by what it says to {@code --version}, and the repository it runs in. A repository under the
 * test JVMs' own temp roots is one a test made, as a file there is a file a test wrote: no input. So
 * such a command keys a class by git's identity, not by its whole module.
 *
 * <p>A command is one of these when git is the administered one or the one {@code PATH} finds, it
 * runs in a temp root (its directory, and every {@code -C}, resolved in turn), and every argument
 * that names a path names one in a temp root too. An argument naming another host ({@code
 * https://}, {@code ssh://}, {@code user@host:path}) reads what no recording sees, as does a
 * repository above a temp root that git would find walking up from one, so neither is one. {@code
 * git --version} reads no repository and runs anywhere. The recording keeps the command as one line
 * with its words joined by spaces; a path with a space in it reads as two words, which can only
 * turn a command into one that is not.
 */
final class GitRuns {

    private GitRuns() {}

    /** The program's name a class's record keeps for such a command. */
    static final String GIT = "git";

    /** {@code user@host:path}, an ssh remote anywhere it appears. */
    private static final Pattern USER_AT_HOST = Pattern.compile("[^/\\\\:@]+@[^/\\\\:]+:.*");

    /** {@code host:path}: a colon before any slash, an ssh remote where a remote is read. */
    private static final Pattern HOST_PATH = Pattern.compile("[^/\\\\:]{2,}:.*");

    /** The subcommands that read a remote's name or address from their arguments. */
    private static final Set<String> REMOTE =
            Set.of("clone", "fetch", "pull", "push", "ls-remote", "remote", "submodule", "archive", "request-pull");

    /**
     * The temp roots of {@code roots} no repository encloses: git run in a directory that is no
     * repository walks up to the first one it finds, and one above a temp root is not the test's.
     */
    static List<Path> unenclosed(Collection<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            Path p = root.toAbsolutePath().normalize();
            boolean enclosed = false;
            for (Path up = p; up != null && !enclosed; up = up.getParent()) {
                enclosed = Files.exists(up.resolve(".git"));
            }
            if (!enclosed) out.add(p);
        }
        return out;
    }

    /**
     * Whether {@code command}, started in {@code directory} (null: the JVM's own, {@code cwd}), is a
     * git command that reads nothing but a repository under one of {@code temp}.
     */
    static boolean ownRepository(String command, @Nullable String directory, Path cwd, Collection<Path> temp) {
        if (temp.isEmpty()) return false;
        String[] words = command.strip().split("\\s+");
        if (words.length == 0 || !git(words[0], temp)) return false;
        Path dir;
        try {
            dir = directory == null || directory.isBlank()
                    ? cwd.toAbsolutePath().normalize()
                    : cwd.resolve(directory).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return false;
        }
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            if (w.equals("-C") && i + 1 < words.length) {
                Path next = resolved(dir, words[++i]);
                if (next == null) return false;
                dir = next;
            } else if (w.equals("-c") && i + 1 < words.length) {
                if (!ownArgument(dir, words[++i], temp, false)) return false;
            } else {
                rest.add(w);
            }
        }
        if (rest.equals(List.of("--version")) || rest.equals(List.of("version"))) return true;
        if (!under(dir, temp)) return false;
        boolean remote = false;
        for (String w : rest) {
            if (!w.startsWith("-")) {
                remote = REMOTE.contains(w);
                break;
            }
        }
        for (String w : rest) {
            if (!ownArgument(dir, w, temp, remote)) return false;
        }
        return true;
    }

    /**
     * Whether {@code program} is git as an administrator installed it or as {@code PATH} finds it:
     * a program of that name a test wrote (gone by now, or still in a temp root), or a checked-in
     * script, reads what it likes.
     */
    private static boolean git(String program, Collection<Path> temp) {
        if (program.equals(GIT)) return true;
        Path file;
        try {
            file = Path.of(program);
        } catch (InvalidPathException odd) {
            return false;
        }
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        if (!name.equals(GIT) && !name.equals("git.exe")) return false;
        if (!file.isAbsolute()) return false;
        Path normal = file.normalize();
        return !under(normal, temp) && Files.isRegularFile(normal) && SuitePrograms.administered(normal);
    }

    /**
     * Whether the argument {@code word} names no path outside {@code temp} and no other host, and for
     * {@code key=value} neither half does. {@code remote}: the subcommand talks to a remote, so a
     * colon before any slash ({@code host:path}) names a host, as git reads it there.
     */
    private static boolean ownArgument(Path dir, String word, Collection<Path> temp, boolean remote) {
        int eq = word.indexOf('=');
        if (eq < 0) return ownValue(dir, word, temp, remote);
        return ownValue(dir, word.substring(0, eq), temp, remote)
                && ownValue(dir, word.substring(eq + 1), temp, remote);
    }

    private static boolean ownValue(Path dir, String value, Collection<Path> temp, boolean remote) {
        if (value.regionMatches(true, 0, "file://", 0, "file://".length())) {
            Path p = resolved(dir, value.substring("file://".length()));
            return p != null && under(p, temp);
        }
        if (value.contains("://") || USER_AT_HOST.matcher(value).matches()) return false;
        if (remote && HOST_PATH.matcher(value).matches() && !drive(value)) return false;
        if (!pathLike(value)) return true;
        Path p = resolved(dir, value);
        return p != null && under(p, temp);
    }

    /** Whether {@code value} could name a file other than one in the directory a command runs in. */
    private static boolean pathLike(String value) {
        return value.contains("/") || value.contains("\\") || value.equals("..") || value.startsWith("~");
    }

    /** A Windows path's drive ({@code C:\x}, {@code C:/x}), which is no host. */
    private static boolean drive(String value) {
        return value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '/' || value.charAt(2) == '\\');
    }

    private static @Nullable Path resolved(Path dir, String word) {
        try {
            return dir.resolve(word).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return null;
        }
    }

    private static boolean under(Path p, Collection<Path> roots) {
        for (Path r : roots) {
            if (p.startsWith(r)) return true;
        }
        return false;
    }
}
