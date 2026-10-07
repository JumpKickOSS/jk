// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The behaviour enhancement exists for: a {@code FetchType.LAZY} basic attribute of an enhanced
 * entity stays unloaded until it is read, through a real session over an H2 database.
 */
@Tag("slow")
class HibernateLazyLoadingTest {

    @Test
    void a_lazy_basic_attribute_loads_on_first_access(@TempDir Path dir) throws Exception {
        Path classes = HibernateEnhancerTest.compile(dir);
        Path enhanced = dir.resolve("enhanced");
        HibernateEnhancer.enhance(
                classes,
                HibernateEnhancerTest.testClasspath(),
                enhanced,
                new HibernateEnhancer.Switches(true, true, false, false));

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {enhanced.toUri().toURL()}, HibernateLazyLoadingTest.class.getClassLoader())) {
            thread.setContextClassLoader(loader);
            Class<?> person = Class.forName("demo.Person", true, loader);
            try (SessionFactory factory = new Configuration()
                    .addAnnotatedClass(person)
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:lazy;DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .buildSessionFactory()) {
                factory.inTransaction(session -> session.persist(newPerson(person)));
                try (Session session = factory.openSession()) {
                    Object loaded = session.find(person, 1L);
                    assertThat(Hibernate.isPropertyInitialized(loaded, "name")).isTrue();
                    assertThat(Hibernate.isPropertyInitialized(loaded, "bio")).isFalse();
                    assertThat(person.getMethod("getBio").invoke(loaded)).isEqualTo("a long biography");
                    assertThat(Hibernate.isPropertyInitialized(loaded, "bio")).isTrue();
                }
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static Object newPerson(Class<?> person) {
        try {
            Object p = person.getConstructor().newInstance();
            person.getMethod("setId", Long.class).invoke(p, 1L);
            person.getMethod("setName", String.class).invoke(p, "Ada");
            person.getMethod("setBio", String.class).invoke(p, "a long biography");
            return p;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
