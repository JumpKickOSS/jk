// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.task.ActionCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The build-logic test fixtures, owned once. {@code BuildLogicSupportTest} and
 * {@code BuildLogicScriptLanguageTest} both drive the same three things — write a stem script,
 * write a task body, run the same project twice and compare — and the alternative to this class was
 * a second copy of all three when the suite was split at the 800-line cap (JK-2444). Copies are
 * what carried every confirmed defect this campaign found; a split must not mint one.
 */
final class BuildLogicFixtures {

    private BuildLogicFixtures() {}

    /**
     * BEFORE_COMPILE output is codegen: it lands in the generated-source root the compilers read,
     * not in classes/. One dir per task.
     */
    static Path generated(BuildLayout layout, String task, String file) {
        return BuildLogicSupport.generatedSourceRoot(layout).resolve(task).resolve(file);
    }

    /** Relative paths of everything under {@code classes}, sorted. */
    static List<String> mergedFiles(Path classes) throws IOException {
        try (var walk = Files.walk(classes)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> classes.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    static void writeStamp(Path file, String fqcn) throws Exception {
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
        Files.writeString(file, """
                package %s;
                import java.nio.file.*;
                public class %s {
                  public static void main(String[] args) throws Exception {
                    Path out = null;
                    for (int i = 0; i < args.length; i++) {
                      if ("--out".equals(args[i])) out = Path.of(args[++i]);
                    }
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("stamp.txt"), "ok");
                  }
                }
                """.formatted(pkg, simple));
    }

    static void writeLineCount(Path file, String fqcn) throws Exception {
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
        Files.writeString(file, """
                package %s;
                import java.nio.file.*;
                import java.util.stream.Stream;
                public class %s {
                  public static void main(String[] args) throws Exception {
                    Path project = null, out = null;
                    for (int i = 0; i < args.length; i++) {
                      if ("--project".equals(args[i])) project = Path.of(args[++i]);
                      else if ("--out".equals(args[i])) out = Path.of(args[++i]);
                    }
                    long n = 0;
                    Path src = project.resolve("src");
                    try (Stream<Path> w = Files.walk(src)) {
                      for (Path f : (Iterable<Path>) w::iterator) {
                        if (Files.isRegularFile(f) && f.toString().endsWith(".java"))
                          n += Files.readAllLines(f).size();
                      }
                    }
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("line-count.txt"), Long.toString(n));
                  }
                }
                """.formatted(pkg, simple));
    }

    static void runTwice(Path project, Path cacheRoot, String ignoredMain) throws Exception {
        ActionCache ac = new ActionCache(new Cas(cacheRoot.resolve("cas")), cacheRoot.resolve("actions"));
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        StringBuilder labels = new StringBuilder();
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        Path generated = classes.resolve("line-count.txt");
        assertTrue(Files.isRegularFile(generated), "expected generated resource");
        String first = Files.readString(generated).trim();
        assertTrue(Integer.parseInt(first) > 0);

        Files.delete(generated);
        labels.setLength(0);
        assertTrue(BuildLogicSupport.run(
                project, layout, ac, classes, s -> labels.append(s).append(';')));
        assertTrue(labels.toString().contains("cache hit"), labels.toString());
        assertEquals(first, Files.readString(generated).trim());
    }
}
