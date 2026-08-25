// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * What {@code jk format} operates on: the tree walk that collects formattable sources, and the
 * classpath the import-shortening pass resolves against. {@link FormatPlans} is the caller.
 */
final class FormatSources {

    private FormatSources() {}

    record CollectedSources(List<Path> javaFiles, List<Path> kotlinFiles) {
        int total() {
            return javaFiles.size() + kotlinFiles.size();
        }
    }

    /**
     * One walk, skipping excluded directories entirely ({@code target/}, {@code build/}, {@code
     * .git/}, …) instead of descending and filtering files afterwards.
     */
    static CollectedSources collectSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) return new CollectedSources(List.of(), List.of());
        List<Path> java = new ArrayList<>();
        List<Path> kotlin = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                return excludedSegment(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel;
                try {
                    rel = root.relativize(file);
                } catch (IllegalArgumentException e) {
                    rel = file;
                }
                if (!notExcluded(rel)) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString();
                if (name.endsWith(".java")) java.add(file);
                else if (name.endsWith(".kt")) kotlin.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        java.sort(null);
        kotlin.sort(null);
        return new CollectedSources(List.copyOf(java), List.copyOf(kotlin));
    }

    /**
     * The classpath {@code optimize-imports} resolves type names against — without one OpenRewrite
     * attributes every name to {@code Unknown}, so the pass parses every file and shortens nothing.
     *
     * <p>It is every workspace module's class output, taken from the lockfile's module list. That
     * is what makes the project's <em>own</em> types nameable, and a tree's own types are what it
     * writes fully-qualified: on jk itself, 3,798 of 4,284 fully-qualified references.
     *
     * <p><strong>Dependency jars are deliberately not on it,</strong> and this is the one place
     * that says so. OpenRewrite builds a javac file manager per file, so every classpath entry is
     * re-opened for every file parsed. Measured over jk's own 2,011 sources: these directories cost
     * 18s on top of a 108s {@code jk format} and shorten 3,293 references; adding the lockfile's
     * 150 dependency jars shortens 47 more (1.4%) and costs a further 6 minutes. It also runs the
     * worker's heap hard enough to have crashed it (a HotSpot SIGSEGV unloading classes under a
     * full GC) in two of four whole-tree runs. A dependency's type written out in full therefore
     * stays that way, which is a stated limit in {@code docs/user/format.md}, not an accident.
     *
     * <p>Directories that do not exist yet are still listed: javac ignores them, and dropping them
     * would make the entry list — and therefore {@link FormatKey#digest()} — change every time a
     * module's tests first compile, re-formatting the tree for nothing. A module that has never
     * been built simply contributes no types, and its callers keep their fully-qualified names
     * until it has.
     *
     * <p>No lockfile means no classpath: {@code jk format} does not resolve or fetch anything, and
     * shortening degrades to the JDK types javac supplies on its own.
     */
    static List<Path> compileClasspath(Path projectDir) {
        Path lockFile = LockPaths.lockFile(projectDir);
        if (!Files.isRegularFile(lockFile)) return List.of();
        Lockfile lock;
        try {
            lock = LockfileReader.read(lockFile);
        } catch (Exception e) {
            return List.of();
        }
        Path workspaceRoot = LockPaths.lockOwnerDir(projectDir);
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        for (Lockfile.ModuleEntry module : lock.modules()) {
            Path target = BuildLayout.moduleTargetDir(workspaceRoot, workspaceRoot.resolve(module.path()));
            entries.add(target.resolve("classes").resolve("main"));
            entries.add(target.resolve("classes").resolve("test"));
        }
        return List.copyOf(entries);
    }

    static boolean notExcluded(Path p) {
        for (Path seg : p) {
            if (excludedSegment(seg.toString())) return false;
        }
        return true;
    }

    /**
     * Directory (or path-segment) names we never format under. A giter8 template root is a
     * directory literally suffixed {@code .g8} or named {@code g8}. A bare "templates"/"giter8"
     * segment is not excluded — this repo's {@code cc.jumpkick.templates} package is real source.
     */
    static boolean excludedSegment(String s) {
        if (s.equals(BuildLayout.TARGET)
                || s.equals("build")
                || s.equals(".jk")
                || s.equals(".git")
                || s.equals("node_modules")) {
            return true;
        }
        if (s.endsWith(".g8") || s.equals("g8")) return true;
        return s.length() > 1 && s.startsWith("$") && s.endsWith("$");
    }
}
