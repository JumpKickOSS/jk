// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.layout.TestSuites;
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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
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
        for (TestClassIndex.Entry e : TestClassIndex.scan(testClasses, Set.of(), List.of()))
            byName.put(e.className(), e);

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
                .withTags(SuiteFilter.classTagsIn(testClasses, List.of()));
        assertThat(probe.acceptor().test(IndexNestedTagged.class.getName()))
                .as("the probe suite's launch may run the nested class's tests")
                .isTrue();

        List<TestClassIndex.Entry> fromClasses = TestClassIndex.scan(testClasses, Set.of(), List.of());
        FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(tmp.resolve("target"), "test"));
        assertThat(TestClassIndex.currentFacts(testClasses)).isPresent();
        assertThat(TestClassIndex.scan(testClasses, Set.of(), List.of()))
                .as("the guard lane's facts index resolves the same tags")
                .containsExactlyInAnyOrderElementsOf(fromClasses);
    }

    @Test
    void a_tag_composed_outside_the_tree_puts_the_class_in_that_tags_suite_alone(@TempDir Path tmp) throws Exception {
        Path testClasses = Files.createDirectories(tmp.resolve("target/classes/test"));
        List<Class<?>> tagged = List.of(
                IndexLibraryTagged.class,
                IndexLibraryContainerTagged.class,
                IndexLibraryStackedTagged.class,
                IndexLibraryCycleTagged.class,
                IndexImplementsLibraryContract.class);
        for (Class<?> c : tagged) copy(c, testClasses);
        // A sibling module's classes directory holds the interface, a library jar the annotations.
        Path siblingClasses = Files.createDirectories(tmp.resolve("sibling/target/classes"));
        copy(LibraryTaggedContract.class, siblingClasses);
        Path jar = tmp.resolve("library.jar");
        writeJar(
                jar,
                LibraryIntegrationTest.class,
                LibraryTagsContainer.class,
                LibraryStacked.class,
                LibraryCycleA.class,
                LibraryCycleB.class);
        List<Path> classpath = List.of(siblingClasses, jar);

        assertThat(classTags(testClasses, List.of(), IndexLibraryTagged.class))
                .as("the tree alone does not hold the annotation")
                .isEmpty();
        for (Class<?> c : tagged) {
            assertThat(classTags(testClasses, classpath, c))
                    .as(c.getSimpleName())
                    .containsExactly("integration");
        }

        Path module = Files.createDirectories(tmp.resolve("module"));
        Map<String, DeclaredSuite> declared = DeclaredSuite.withConvention(Map.of());
        SuiteFilter integration = SuiteFilter.of(module, false, List.of(DeclaredSuite.INTEGRATION), declared)
                .withTags(SuiteFilter.classTagsIn(testClasses, classpath));
        SuiteFilter fast = SuiteFilter.of(module, false, List.of(TestSuites.DEFAULT), declared)
                .withTags(SuiteFilter.classTagsIn(testClasses, classpath));
        SuiteFilter both = SuiteFilter.of(
                        module, false, List.of(TestSuites.DEFAULT, DeclaredSuite.INTEGRATION), declared)
                .withTags(SuiteFilter.classTagsIn(testClasses, classpath));
        assertThat(integration.selectsCompiledClass(testClasses, classpath, List.of()))
                .as("jk test -s integration has tests to run")
                .isTrue();
        for (Class<?> c : tagged) {
            assertThat(integration.acceptor().test(c.getName()))
                    .as(c.getSimpleName() + " runs in the integration suite")
                    .isTrue();
            assertThat(fast.acceptor().test(c.getName()))
                    .as(c.getSimpleName() + " does not run in the default suite")
                    .isFalse();
            assertThat(both.suiteOf(c.getName())).isEqualTo(DeclaredSuite.INTEGRATION);
        }

        List<TestClassIndex.Entry> fromClasses = TestClassIndex.scan(testClasses, Set.of(), classpath);
        FactsIndexing.ensure(testClasses, FactsIndexing.indexPath(tmp.resolve("target"), "test"));
        assertThat(TestClassIndex.currentFacts(testClasses)).isPresent();
        assertThat(TestClassIndex.scan(testClasses, Set.of(), classpath))
                .as("the guard lane's facts index resolves the same tags")
                .containsExactlyInAnyOrderElementsOf(fromClasses);

        // The library rewritten without the annotation: its size moves and the memo reads it again.
        writeJar(jar, LibraryTagsContainer.class);
        assertThat(classTags(testClasses, classpath, IndexLibraryTagged.class))
                .as("a rewritten jar is read again")
                .isEmpty();
        assertThat(classTags(testClasses, classpath, IndexLibraryContainerTagged.class))
                .containsExactly("integration");
    }

    private static void writeJar(Path jar, Class<?>... types) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Class<?> c : types) {
                out.putNextEntry(new JarEntry(resource(c)));
                out.write(bytes(c));
                out.closeEntry();
            }
        }
    }

    private static Set<String> classTags(Path testClasses, List<Path> classpath, Class<?> type) throws Exception {
        for (TestClassIndex.Entry e : TestClassIndex.scan(testClasses, Set.of(), classpath)) {
            if (e.className().equals(type.getName())) return e.classTags();
        }
        throw new AssertionError(type.getName() + " not indexed");
    }

    private static String resource(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    private static byte[] bytes(Class<?> type) throws Exception {
        try (InputStream in = Objects.requireNonNull(
                TestClassIndexTagsTest.class.getClassLoader().getResourceAsStream(resource(type)))) {
            return in.readAllBytes();
        }
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

/** Fixture, in the library jar: a composed annotation carrying the integration tag. */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Tag("integration")
@interface LibraryIntegrationTest {}

/** Fixture, in the library jar: a composed annotation carrying the tag in a {@code @Tags} container. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tags({@Tag("integration")})
@interface LibraryTagsContainer {}

/** Fixture, in the library jar: composed of the composed annotation. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@LibraryIntegrationTest
@interface LibraryStacked {}

/** Fixture, in the library jar: half of two annotations that annotate each other. */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@LibraryCycleB
@interface LibraryCycleA {}

/** Fixture, in the library jar: the other half, which carries the tag. */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@LibraryCycleA
@Tag("integration")
@interface LibraryCycleB {}

/** Fixture, in the sibling's classes: an interface tagged through the library's annotation. */
@LibraryIntegrationTest
interface LibraryTaggedContract {}

/** Fixture: tagged through the library's composed annotation. */
@LibraryIntegrationTest
class IndexLibraryTagged {}

/** Fixture: tagged through the library's {@code @Tags} container annotation. */
@LibraryTagsContainer
class IndexLibraryContainerTagged {}

/** Fixture: tagged two composed annotations deep. */
@LibraryStacked
class IndexLibraryStackedTagged {}

/** Fixture: tagged through annotations that annotate each other. */
@LibraryCycleA
class IndexLibraryCycleTagged {}

/** Fixture: inherits the tag of an interface it implements. */
class IndexImplementsLibraryContract implements LibraryTaggedContract {}
