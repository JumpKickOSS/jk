// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A consumer finds its producers' Zinc analyses from the classpath entries alone. */
class ProducerAnalysesTest {

    @Test
    void a_sibling_s_jar_and_classes_dir_both_lead_to_the_compile_that_wrote_them(@TempDir Path dir)
            throws IOException {
        Path classes = dir.resolve("target/lib-mod/classes/main");
        Path jar = dir.resolve("target/lib-mod/lib/lib-mod-1.0.jar");
        Path maven = dir.resolve("m2/guava.jar");
        Files.createDirectories(classes);
        Files.createDirectories(jar.getParent());
        Files.createFile(jar);
        Files.createDirectories(maven.getParent());
        Files.createFile(maven);
        Path inc = dir.resolve("incremental-java");
        Path analysis = stateOf(inc, TaskNames.COMPILE_MAIN, classes).resolve(ProducerAnalyses.ANALYSIS_FILE);
        Files.createDirectories(analysis.getParent());
        Files.createFile(analysis);

        Map<Path, Path> found = ProducerAnalyses.forClasspath(List.of(maven, jar, classes), inc);

        assertThat(found).containsExactly(Map.entry(jar, analysis), Map.entry(classes, analysis));
    }

    @Test
    void a_test_classes_tree_leads_to_its_compile_test_state(@TempDir Path dir) throws IOException {
        Path testClasses = dir.resolve("target/lib-mod/classes/test");
        Files.createDirectories(testClasses);
        Path inc = dir.resolve("incremental-java");
        Path analysis = stateOf(inc, TaskNames.COMPILE_TEST, testClasses).resolve(ProducerAnalyses.ANALYSIS_FILE);
        Files.createDirectories(analysis.getParent());
        Files.createFile(analysis);

        assertThat(ProducerAnalyses.forClasspath(List.of(testClasses), inc))
                .containsExactly(Map.entry(testClasses, analysis));
    }

    @Test
    void a_producer_that_has_not_written_an_analysis_contributes_nothing(@TempDir Path dir) throws IOException {
        Path classes = dir.resolve("target/lib-mod/classes/main");
        Files.createDirectories(classes);
        Path inc = dir.resolve("incremental-java");
        Files.createDirectories(stateOf(inc, TaskNames.COMPILE_MAIN, classes));

        assertThat(ProducerAnalyses.forClasspath(List.of(classes), inc)).isEmpty();
    }

    private static Path stateOf(Path incrementalRoot, String task, Path classes) {
        return incrementalRoot.resolve(ActionKey.qualifiedTaskId(task, classes));
    }
}
