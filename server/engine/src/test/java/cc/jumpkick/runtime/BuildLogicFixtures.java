// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** A standalone project: one manifest and one main class under {@code src/main/java}. */
    static Path scaffold(Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(
                project.resolve("src/main/java/demo/App.java"),
                "package demo; public class App { public static void main(String[] a) {} }\n");
        return project;
    }

    /** The action key a cache-consulting task at {@code anchor} records under, when {@code script} is the only stem. */
    static String expectedKey(Path projectDir, Path script, BuildLogicAnchor anchor, List<String> inputTokens)
            throws Exception {
        return expectedKey(projectDir, List.of(script), script, anchor, inputTokens);
    }

    /** The key of {@code script}'s task when {@code scripts} are every stem in its logic dir. */
    static String expectedKey(
            Path projectDir, List<Path> scripts, Path script, BuildLogicAnchor anchor, List<String> inputTokens)
            throws Exception {
        String stem = script.getFileName().toString().replaceFirst("\\.groovy$", "");
        List<String> tokens = new ArrayList<>();
        tokens.add("dir:" + projectDir.relativize(script.getParent()));
        for (Path s : scripts) {
            tokens.add("script:" + s.getFileName() + ":" + Hashing.sha256Hex(Files.readAllBytes(s)));
        }
        tokens.add("anchor:" + anchor.name());
        tokens.addAll(inputTokens);
        tokens.add("task:" + stem);
        tokens.add("kind:script");
        return ActionKey.forArtifact(
                ActionKey.qualifiedTaskId("build-logic-" + stem, projectDir), BuildIdentity.cacheKeyVersion(), tokens);
    }
}
