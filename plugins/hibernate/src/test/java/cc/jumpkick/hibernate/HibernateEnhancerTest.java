// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.hibernate.engine.spi.ManagedEntity;
import org.hibernate.engine.spi.PersistentAttributeInterceptable;
import org.hibernate.engine.spi.SelfDirtinessTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The enhancer over classes compiled here, with this test's Hibernate on the runtime classpath:
 * an entity comes out implementing Hibernate's enhancement interfaces as the switches say, every
 * other file is carried over byte for byte.
 */
class HibernateEnhancerTest {

    static final String PERSON = """
            package demo;

            import jakarta.persistence.Basic;
            import jakarta.persistence.Entity;
            import jakarta.persistence.FetchType;
            import jakarta.persistence.Id;

            @Entity
            public class Person {
                @Id
                private Long id;

                private String name;

                @Basic(fetch = FetchType.LAZY)
                private String bio;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
                public String getBio() { return bio; }
                public void setBio(String bio) { this.bio = bio; }
            }
            """;

    static final String PLAIN = """
            package demo;

            public class Plain {
                public String hello() { return "hello"; }
            }
            """;

    @Test
    void an_entity_is_enhanced_and_everything_else_is_copied(@TempDir Path dir) throws Exception {
        Path classes = compile(dir);
        Files.writeString(classes.resolve("app.properties"), "k=v\n");
        Path out = dir.resolve("out");

        HibernateEnhancer.Result result = HibernateEnhancer.enhance(
                classes, testClasspath(), out, new HibernateEnhancer.Switches(true, true, false, false));

        assertThat(result.enhanced()).isEqualTo(1);
        assertThat(result.hibernateVersion()).startsWith("7.");
        assertThat(interfacesOf(out, "demo.Person"))
                .contains(ManagedEntity.class.getName(), PersistentAttributeInterceptable.class.getName())
                .contains(SelfDirtinessTracker.class.getName());
        assertThat(Files.readAllBytes(out.resolve("demo/Plain.class")))
                .isEqualTo(Files.readAllBytes(classes.resolve("demo/Plain.class")));
        assertThat(out.resolve("app.properties")).hasContent("k=v");
    }

    @Test
    void dirty_tracking_off_leaves_the_entity_without_its_own_tracker(@TempDir Path dir) throws Exception {
        Path classes = compile(dir);
        Path out = dir.resolve("out");

        HibernateEnhancer.enhance(
                classes, testClasspath(), out, new HibernateEnhancer.Switches(true, false, false, false));

        assertThat(interfacesOf(out, "demo.Person"))
                .contains(ManagedEntity.class.getName())
                .doesNotContain(SelfDirtinessTracker.class.getName());
    }

    @Test
    void a_classpath_without_hibernate_names_the_dependency(@TempDir Path dir) throws Exception {
        Path classes = compile(dir);
        assertThatThrownBy(() -> HibernateEnhancer.enhance(
                        classes,
                        List.of(),
                        dir.resolve("out"),
                        new HibernateEnhancer.Switches(true, true, false, false)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("org.hibernate.orm:hibernate-core");
    }

    /** {@link #PERSON} and {@link #PLAIN} compiled against this test's classpath into {@code dir/classes}. */
    static Path compile(Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/demo"));
        Files.writeString(src.resolve("Person.java"), PERSON);
        Files.writeString(src.resolve("Plain.java"), PLAIN);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int exit = javac.run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                src.resolve("Person.java").toString(),
                src.resolve("Plain.java").toString());
        assertThat(exit).isZero();
        return classes;
    }

    static List<Path> testClasspath() {
        List<Path> out = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) out.add(Path.of(entry));
        }
        return out;
    }

    /** The interface names of {@code className} as loaded from {@code classes} over this test's loader. */
    private static List<String> interfacesOf(Path classes, String className) throws Exception {
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {classes.toUri().toURL()}, HibernateEnhancerTest.class.getClassLoader())) {
            return Arrays.stream(Class.forName(className, false, loader).getInterfaces())
                    .map(Class::getName)
                    .toList();
        }
    }
}
