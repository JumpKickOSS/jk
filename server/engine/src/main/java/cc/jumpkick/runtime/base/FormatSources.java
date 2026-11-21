// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.WalkSkip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The formattable files of {@code files}, by language and sorted, with the walk's exclusions applied. */
    static CollectedSources of(Path root, List<Path> files) {
        List<Path> java = new ArrayList<>();
        List<Path> kotlin = new ArrayList<>();
        List<Path> groovy = new ArrayList<>();
        List<Path> scala = new ArrayList<>();
        for (Path file : files) {
            Path rel = file.startsWith(root) ? root.relativize(file) : file;
            if (!notExcluded(rel)) continue;
            String name = file.getFileName().toString();
            if (name.endsWith(".java")) java.add(file);
            else if (name.endsWith(".kt")) kotlin.add(file);
            else if (name.endsWith(".groovy")) groovy.add(file);
            else if (name.endsWith(".scala")) scala.add(file);
        }
        java.sort(null);
        kotlin.sort(null);
        groovy.sort(null);
        scala.sort(null);
        return new CollectedSources(List.copyOf(java), List.copyOf(kotlin), List.copyOf(groovy), List.copyOf(scala));
    }

    /**
     * One walk, skipping excluded directories entirely ({@code target/}, Gradle's {@code build/},
     * {@code .git/}, …) instead of descending and filtering files afterwards. Gradle's build dir is
     * known by the script beside it, so a package named {@code build} is formatted like any other.
     * Each module met on the way adds its generated roots to the {@link FormatExclusions}, so a
     * generated root is skipped the same way, and the root's {@code [format] exclude} globs drop
     * the files they match.
     */
    static CollectedSources collectSources(Path root) throws IOException {
        if (!Files.isDirectory(root)) return new CollectedSources(List.of(), List.of(), List.of(), List.of());
        FormatExclusions exclusions = FormatExclusions.forRoot(root).moduleIn(root);
        List<Path> java = new ArrayList<>();
        List<Path> kotlin = new ArrayList<>();
        List<Path> groovy = new ArrayList<>();
        List<Path> scala = new ArrayList<>();
        // Readable, not strict: a directory this user cannot open is not theirs to format.
        PathUtil.forEachReadableFile(
                root,
                dir -> {
                    // A nested checkout's files are another branch's: formatting them rewrites work
                    // in progress somewhere else and reports it under this root's paths.
                    if (WalkSkip.formatSkip(dir) || WalkSkip.nestedCheckout(dir)) return true;
                    exclusions.moduleIn(dir);
                    return exclusions.excludes(dir);
                },
                (file, attrs) -> {
                    Path rel;
                    try {
                        rel = root.relativize(file);
                    } catch (IllegalArgumentException e) {
                        rel = file;
                    }
                    if (!notExcluded(rel) || exclusions.excludes(file)) return;
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java")) java.add(file);
                    else if (name.endsWith(".kt")) kotlin.add(file);
                    else if (name.endsWith(".groovy")) groovy.add(file);
                    else if (name.endsWith(".scala")) scala.add(file);
                });
        java.sort(null);
        kotlin.sort(null);
        groovy.sort(null);
        scala.sort(null);
        return new CollectedSources(List.copyOf(java), List.copyOf(kotlin), List.copyOf(groovy), List.copyOf(scala));
    }

    static boolean notExcluded(Path p) {
        for (Path seg : p) {
            if (WalkSkip.formatSegment(seg.toString())) return false;
        }
        return true;
    }

    /**
     * Directory (or path-segment) names we never format under. A giter8 template root is a
     * directory literally suffixed {@code .g8} or named {@code g8}. A bare "templates"/"giter8"
     * segment is not excluded — this repo's {@code cc.jumpkick.templates} package is real source.
     */
    static boolean excludedSegment(String s) {
        return WalkSkip.formatSegment(s);
    }
}
