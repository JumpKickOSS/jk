// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.test.fixture.Referenced;
import cc.jumpkick.test.fixture.ReferencesFixtureTest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A test imports every production type its bytecode names, however it names it. */
class TestClassIndexRefsTest {

    private static final Set<String> PRODUCTION = Set.of(
            Referenced.Literal.class.getName(),
            Referenced.MethodRef.class.getName(),
            Referenced.Caught.class.getName(),
            Referenced.AnnotationValue.class.getName());

    @Test
    void literal_method_ref_catch_and_annotation_value_are_imports(@TempDir Path tmp) throws Exception {
        Path testClasses = Files.createDirectories(tmp.resolve("target/classes/test"));
        String res = ReferencesFixtureTest.class.getName().replace('.', '/') + ".class";
        Path out = testClasses.resolve(res);
        Files.createDirectories(out.getParent());
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(res), res)) {
            Files.write(out, in.readAllBytes());
        }

        assertThat(TestClassIndex.currentFacts(testClasses)).as("no index yet").isEmpty();
        assertThat(TestClassIndex.scan(testClasses, PRODUCTION, List.of()))
                .singleElement()
                .satisfies(e ->
                        assertThat(e.imports()).as("read from the class files").isEqualTo(PRODUCTION));

        FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(tmp.resolve("target"), "test"));
        assertThat(TestClassIndex.currentFacts(testClasses)).isPresent();
        assertThat(TestClassIndex.scan(testClasses, PRODUCTION, List.of()))
                .singleElement()
                .satisfies(e ->
                        assertThat(e.imports()).as("read from the facts index").isEqualTo(PRODUCTION));
    }
}
