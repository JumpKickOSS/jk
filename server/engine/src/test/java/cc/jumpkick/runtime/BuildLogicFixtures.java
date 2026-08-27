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

/** Shared setup for stem-script build-logic tests. */
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

    static void writeLineCountGroovy(Path file) throws IOException {
        Files.writeString(file, """
                def src = projectDir.resolve('src')
                long n = 0
                src.toFile().eachFileRecurse { f ->
                  if (f.file && f.name.endsWith('.java')) n += f.readLines().size()
                }
                outDir.resolve('line-count.txt').toFile().parentFile.mkdirs()
                outDir.resolve('line-count.txt').toFile().text = String.valueOf(n)
                """);
    }

    static void writeStampGroovy(Path file) throws IOException {
        Files.writeString(file, """
                outDir.resolve('stamp.txt').toFile().parentFile.mkdirs()
                outDir.resolve('stamp.txt').toFile().text = 'ok'
                """);
    }

    static void runTwice(Path project, Path cacheRoot) throws Exception {
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
