// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.test.fixture.TaggedFixtureTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The facts index and the class files yield the same entries; a stale index is not consulted. */
class TestClassIndexFactsTest {

    private static Path classesDir(Path tmp) throws IOException {
        Path testClasses = Files.createDirectories(tmp.resolve("target/classes/test"));
        for (Class<?> c : List.of(TaggedFixtureTest.class, TestClassIndexFactsTest.class)) {
            String res = c.getName().replace('.', '/') + ".class";
            Path out = testClasses.resolve(res);
            Files.createDirectories(out.getParent());
            try (InputStream in = Objects.requireNonNull(c.getClassLoader().getResourceAsStream(res), res)) {
                Files.write(out, in.readAllBytes());
            }
        }
        return testClasses;
    }

    @Test
    void facts_index_and_class_files_agree(@TempDir Path tmp) throws Exception {
        Path testClasses = classesDir(tmp);
        Set<String> production = Set.of("cc.jumpkick.test.TestClassIndex", "cc.jumpkick.test.Unused");
        assertThat(TestClassIndex.currentFacts(testClasses)).as("no index yet").isEmpty();
        List<TestClassIndex.Entry> fromClasses = TestClassIndex.scan(testClasses, production);

        FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(tmp.resolve("target"), "test"));
        assertThat(TestClassIndex.currentFacts(testClasses)).isPresent();
        List<TestClassIndex.Entry> fromFacts = TestClassIndex.scan(testClasses, production);

        assertThat(fromFacts).containsExactlyInAnyOrderElementsOf(fromClasses);
        TestClassIndex.Entry tagged = fromFacts.stream()
                .filter(e -> e.className().equals(TaggedFixtureTest.class.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(tagged.tags()).containsExactlyInAnyOrder("slow", "network", "integration");
        assertThat(tagged.imports()).containsExactly("cc.jumpkick.test.TestClassIndex");
        assertThat(tagged.nameMatchSimple()).isEqualTo("TaggedFixture");
    }

    @Test
    void a_recompiled_class_makes_the_index_stale(@TempDir Path tmp) throws Exception {
        Path testClasses = classesDir(tmp);
        FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(tmp.resolve("target"), "test"));
        assertThat(TestClassIndex.currentFacts(testClasses)).isPresent();
        Path one = testClasses.resolve(TaggedFixtureTest.class.getName().replace('.', '/') + ".class");
        Files.write(
                one, Files.readAllBytes(testClasses.resolve(getClass().getName().replace('.', '/') + ".class")));
        assertThat(TestClassIndex.currentFacts(testClasses))
                .as("size changed: stamps differ")
                .isEmpty();
    }
}
