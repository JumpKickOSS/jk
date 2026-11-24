// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.engine.plugin.HeapFlags;
import cc.jumpkick.host.Os;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whether a program a test class started could run the test JVM's own workspace classes, and which
 * workspace files its command names.
 *
 * <p>A body-only edit reaches a program only through the classes it runs: {@code git}, {@code node},
 * {@code setsid} wrapping a worker JVM, a JDK's {@code javadoc} load no class of the test's class
 * path. Such a program keys its class by what the suite's stamp says every program it started is
 * ({@link ClassReads#EXTERNAL_PROGRAM}), by the workspace files its command names, and by every
 * worker jar the suite was handed, not by the whole module. A command could run the workspace's
 * classes when one of its words, split at the shell's quotes and operators and at the path
 * separator, names one of the test JVM's class trees or workspace jars, or a path under one; when a
 * word expands a variable or a command ({@code $CP}, a backquote) or hands a launcher an argument
 * file ({@code @args}), which no command line shows; or when the program is one the test wrote in
 * its temp root, which runs what the test wrote into it.
 *
 * <p>What none of this sees is a class path a program takes from its environment ({@code
 * CLASSPATH}); the test JVM's is an allow-list its suite's key holds, and what the test sets for its
 * child is the test's own code.
 */
final class ClassPathCommands {

    private ClassPathCommands() {}

    /** Where a word is cut: the shell's quotes and operators, an assignment's {@code =}, the path separator. */
    private static final Pattern CUT = Pattern.compile("[\"'<>|;&()=,{}" + (Os.isWindows() ? "" : ":") + "]+");

    /**
     * Whether {@code command}, started in {@code directory} (null: the JVM's own, {@code cwd}), could
     * run a class of the test JVM's class path as {@code layout} names it.
     */
    static boolean reaches(String command, @Nullable String directory, Path cwd, ClassReads.Layout layout) {
        Path dir = directory(directory, cwd);
        if (dir == null) return true;
        String[] words = command.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return false;
        Path program = resolved(dir, HeapFlags.executable(List.of(words)));
        if (program != null && under(program, layout.temp())) return true;
        List<Path> classPath = classPath(layout);
        for (String word : words) {
            if (word.contains("$") || word.contains("`")) return true;
            for (String part : CUT.split(word)) {
                if (part.isEmpty()) continue;
                if (part.startsWith("@") && part.length() > 1) return true;
                Path p = resolved(dir, part);
                if (p != null && under(p, classPath)) return true;
            }
        }
        return false;
    }

    /**
     * The regular files of the workspace outside the test JVMs' temp roots that {@code command},
     * started in {@code directory} (null: {@code cwd}), names: a script it runs, a file it reads.
     */
    static Set<Path> named(String command, @Nullable String directory, Path cwd, ClassReads.Layout layout) {
        Set<Path> out = new TreeSet<>();
        Path dir = directory(directory, cwd);
        if (dir == null) return out;
        for (String word : command.strip().split("\\s+")) {
            for (String part : CUT.split(word)) {
                if (part.isEmpty() || part.startsWith("-")) continue;
                Path p = resolved(dir, part);
                if (p != null && p.startsWith(layout.workspace()) && !under(p, layout.temp()) && Files.isRegularFile(p))
                    out.add(p);
            }
        }
        return out;
    }

    /** The test JVM's class trees and the workspace jars on its class path. */
    private static List<Path> classPath(ClassReads.Layout layout) {
        List<Path> out = new ArrayList<>(layout.classTrees());
        for (Path jar : layout.externalJars()) if (jar.startsWith(layout.workspace())) out.add(jar);
        return out;
    }

    private static @Nullable Path directory(@Nullable String directory, Path cwd) {
        try {
            return directory == null || directory.isBlank()
                    ? cwd.toAbsolutePath().normalize()
                    : cwd.resolve(directory).toAbsolutePath().normalize();
        } catch (InvalidPathException odd) {
            return null;
        }
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
