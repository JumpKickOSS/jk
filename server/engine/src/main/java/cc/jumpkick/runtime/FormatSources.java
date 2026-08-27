// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * What {@code jk format} operates on: the tree walk that collects formattable sources.
 * {@link FormatPlans} is the caller.
 */
final class FormatSources {

    private FormatSources() {}

    record CollectedSources(
            List<Path> javaFiles, List<Path> kotlinFiles, List<Path> groovyFiles, List<Path> scalaFiles) {
        int total() {
            return javaFiles.size() + kotlinFiles.size() + groovyFiles.size() + scalaFiles.size();
        }
    }

    /**
     * One walk, skipping excluded directories entirely ({@code target/}, {@code build/}, {@code
     * .git/}, …) instead of descending and filtering files afterwards.
     */
    static CollectedSources collectSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) return new CollectedSources(List.of(), List.of(), List.of(), List.of());
        List<Path> java = new ArrayList<>();
        List<Path> kotlin = new ArrayList<>();
        List<Path> groovy = new ArrayList<>();
        List<Path> scala = new ArrayList<>();
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
                else if (name.endsWith(".groovy")) groovy.add(file);
                else if (name.endsWith(".scala")) scala.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        java.sort(null);
        kotlin.sort(null);
        groovy.sort(null);
        scala.sort(null);
        return new CollectedSources(List.copyOf(java), List.copyOf(kotlin), List.copyOf(groovy), List.copyOf(scala));
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
                || s.equals("jk")
                || s.equals(".git")
                || s.equals("node_modules")) {
            return true;
        }
        if (s.endsWith(".g8") || s.equals("g8")) return true;
        return s.length() > 1 && s.startsWith("$") && s.endsWith("$");
    }
}
