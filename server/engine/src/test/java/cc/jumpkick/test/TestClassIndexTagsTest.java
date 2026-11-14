// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.model.DeclaredSuite;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine reads a class's tags as JUnit does: a {@code @Nested} class's tag, a composed
 * annotation's and a superclass's place the class in the tag's suite.
 */
class TestClassIndexTagsTest {

    @Test
    void nested_composed_and_inherited_tags_are_read(@TempDir Path tmp) throws Exception {
        Path testClasses = Files.createDirectories(tmp.resolve("target/classes/test"));
        for (Class<?> c : List.of(
                IndexNestedTagged.class,
                IndexNestedTagged.Db.class,
                IndexComposedTagged.class,
                IndexComposedTag.class,
                IndexTaggedBase.class,
                IndexInheritsTag.class)) copy(c, testClasses);

        Map<String, TestClassIndex.Entry> byName = new HashMap<>();
        for (TestClassIndex.Entry e : TestClassIndex.scan(testClasses, Set.of())) byName.put(e.className(), e);

        assertThat(Objects.requireNonNull(byName.get(IndexNestedTagged.class.getName()))
                        .tags())
                .as("@Nested @Tag inside an untagged class")
                .contains("bench");
        assertThat(Objects.requireNonNull(byName.get(IndexComposedTagged.class.getName()))
                        .classTags())
                .as("a composed annotation meta-annotated @Tag")
                .contains("bench");
        assertThat(Objects.requireNonNull(byName.get(IndexInheritsTag.class.getName()))
                        .classTags())
                .as("@Tag on the superclass, which JUnit inherits")
                .contains("bench");

        SuiteFilter probe = SuiteFilter.of(
                        Files.createDirectories(tmp.resolve("module")),
                        false,
                        List.of("probe"),
                        Map.of("probe", DeclaredSuite.ofTags(List.of("bench"))))
                .withTags(SuiteFilter.classTagsIn(testClasses));
        assertThat(probe.acceptor().test(IndexNestedTagged.class.getName()))
                .as("the probe suite's launch may run the nested class's tests")
                .isTrue();

        List<TestClassIndex.Entry> fromClasses = TestClassIndex.scan(testClasses, Set.of());
        FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(tmp.resolve("target"), "test"));
        assertThat(TestClassIndex.currentFacts(testClasses)).isPresent();
        assertThat(TestClassIndex.scan(testClasses, Set.of()))
                .as("the guard lane's facts index resolves the same tags")
                .containsExactlyInAnyOrderElementsOf(fromClasses);
    }

    private static void copy(Class<?> type, Path root) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        Path to = root.resolve(resource);
        Files.createDirectories(to.getParent());
        try (InputStream in = Objects.requireNonNull(
                TestClassIndexTagsTest.class.getClassLoader().getResourceAsStream(resource))) {
            Files.write(to, in.readAllBytes());
        }
    }
}

/** Fixture: an untagged class whose nested class carries the tag. No test methods, so nothing runs. */
class IndexNestedTagged {
    @Nested
    @Tag("bench")
    class Db {}
}

/** Fixture: a composed tag annotation. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("bench")
@interface IndexComposedTag {}

/** Fixture: a class tagged through the composed annotation. */
@IndexComposedTag
class IndexComposedTagged {}

/** Fixture: a tagged base class. */
@Tag("bench")
abstract class IndexTaggedBase {}

/** Fixture: a class that inherits the base's tag. */
class IndexInheritsTag extends IndexTaggedBase {}
