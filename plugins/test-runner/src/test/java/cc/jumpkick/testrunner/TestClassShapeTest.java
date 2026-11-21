// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A class file's shape is read from its bytes: no class is loaded to decide whether it is a test class. */
class TestClassShapeTest {

    @Test
    void a_class_files_name_supertypes_flags_and_strings_are_read_from_its_bytes() throws IOException {
        TestClassShape.Facts facts = TestClassShape.read(bytesOf(TagEmptiedFixture.class));

        assertThat(facts.name()).isEqualTo("cc/jumpkick/testrunner/TagEmptiedFixture");
        assertThat(facts.superName()).isEqualTo("java/lang/Object");
        assertThat(facts.interfaces()).isEmpty();
        assertThat(facts.strings()).contains("Lorg/junit/jupiter/api/Test;", "untagged", "tagged");
        assertThat(facts.type().memberAnnotations()).contains("org.junit.jupiter.api.Test");
        assertThat(TestClassShape.read(bytesOf(InheritedFromInterfaceFixture.class))
                        .type()
                        .supertypes())
                .containsExactly("java.lang.Object", InheritedFromInterfaceFixtureContract.class.getName());
    }

    @Test
    void a_class_declaring_a_test_method_is_a_test_class(@TempDir Path tmp) throws IOException {
        assertThat(shape(tmp, TagEmptiedFixture.class).isTestClass(TagEmptiedFixture.class.getName()))
                .isTrue();
    }

    @Test
    void a_helper_with_no_test_is_not(@TempDir Path tmp) throws IOException {
        assertThat(shape(tmp, StaticInitFixture.class).isTestClass(StaticInitFixture.class.getName()))
                .isFalse();
        assertThat(shape(tmp, MissingBaseHelperFixture.class).isTestClass(MissingBaseHelperFixture.class.getName()))
                .as("a helper whose abstract base declares no test")
                .isFalse();
    }

    /** No annotation anywhere: the JUnit 3 base is the declaration, as Spock's is for a specification. */
    @Test
    void a_junit3_test_case_is_a_test_class(@TempDir Path tmp) throws IOException {
        assertThat(shape(tmp, JUnit3StyleFixture.class).isTestClass(JUnit3StyleFixture.class.getName()))
                .isTrue();
    }

    @Test
    void an_abstract_class_is_not_a_test_class_even_with_test_methods(@TempDir Path tmp) throws IOException {
        assertThat(shape(tmp, MissingBaseFixtureBase.class).isTestClass(MissingBaseFixtureBase.class.getName()))
                .isFalse();
    }

    @Test
    void a_class_inheriting_its_tests_is_a_test_class_through_the_loaders_copy_of_the_base(@TempDir Path tmp)
            throws IOException {
        // Only the child is under the root; the base is reached as a resource of the loader.
        assertThat(shape(tmp, MissingBaseFixture.class).isTestClass(MissingBaseFixture.class.getName()))
                .isTrue();
    }

    @Test
    void a_class_whose_only_test_wears_a_composed_annotation_is_a_test_class(@TempDir Path tmp) throws IOException {
        assertThat(shape(tmp, ComposedFixture.class).isTestClass(ComposedFixture.class.getName()))
                .isTrue();
    }

    @Test
    void a_class_whose_only_test_is_in_a_nested_class_is_a_test_class(@TempDir Path tmp) throws IOException {
        assertThat(shape(tmp, NestedOnlyFixture.class, NestedOnlyFixture.Inner.class)
                        .isTestClass(NestedOnlyFixture.class.getName()))
                .isTrue();
    }

    @Test
    void a_class_inheriting_a_test_from_an_interface_is_a_test_class(@TempDir Path tmp) throws IOException {
        assertThat(shape(tmp, InheritedFromInterfaceFixture.class)
                        .isTestClass(InheritedFromInterfaceFixture.class.getName()))
                .isTrue();
    }

    @Test
    void a_file_that_is_not_a_class_file_keeps_the_failure(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("a"));
        Files.write(pkg.resolve("Garbage.class"), "not a class".getBytes(StandardCharsets.UTF_8));

        assertThat(new TestClassShape(tmp, getClass().getClassLoader()).isTestClass("a.Garbage"))
                .isTrue();
    }

    private TestClassShape shape(Path root, Class<?>... classes) throws IOException {
        for (Class<?> c : classes) {
            Path file = root.resolve(c.getName().replace('.', '/') + ".class");
            Files.createDirectories(Objects.requireNonNull(file.getParent()));
            Files.write(file, bytesOf(c));
        }
        return new TestClassShape(root, getClass().getClassLoader());
    }

    private static byte[] bytesOf(Class<?> c) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class"), c.getName())) {
            return in.readAllBytes();
        }
    }
}
