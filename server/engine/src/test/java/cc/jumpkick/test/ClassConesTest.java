// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test class's static cone follows every workspace class its class files name, through a field's
 * type, a call, a nested class and a class name it loads by name, but not one it only passes on as
 * data, stops at the workspace's edge, takes in a
 * service file's providers when it names the service loader, and changes with any class in it.
 */
class ClassConesTest {

    @Test
    void the_cone_holds_what_the_class_reaches_and_nothing_else(@TempDir Path tmp) throws Exception {
        Path root = tree(
                tmp.resolve("classes"),
                ConeRoot.class,
                ConeRoot.Inner.class,
                ConeDep.class,
                ConeFar.class,
                ConeNamed.class,
                ConeUnrelated.class);
        ClassCones.Index index = ClassCones.index(List.of(root));
        ClassCones.Cone cone = index.cone(ConeRoot.class.getName());
        assertThat(cone.classes())
                .contains(
                        ConeRoot.class.getName(),
                        ConeRoot.Inner.class.getName(),
                        ConeDep.class.getName(),
                        ConeFar.class.getName(),
                        ConeNamed.class.getName())
                .doesNotContain(ConeUnrelated.class.getName());
        assertThat(cone.scanner()).isNull();
        assertThat(index.cone(ConeUnrelated.class.getName()).classes()).containsExactly(ConeUnrelated.class.getName());

        Files.write(root.resolve(path(ConeFar.class)), bytes(ConeUnrelated.class));
        ClassCones.Cone moved = ClassCones.index(List.of(root)).cone(ConeRoot.class.getName());
        assertThat(moved.digest()).as("a class two hops away changed").isNotEqualTo(cone.digest());
    }

    @Test
    void a_class_loaded_outside_the_cone_joins_it_with_what_it_reaches(@TempDir Path tmp) throws Exception {
        Path root = tree(
                tmp.resolve("classes"),
                ConeDep.class,
                ConeRoot.class,
                ConeRoot.Inner.class,
                ConeFar.class,
                ConeNamed.class);
        ClassCones.Index index = ClassCones.index(List.of(root));
        ClassCones.Cone alone = index.cone(ConeFar.class.getName());
        ClassCones.Cone joined = index.cone(ConeFar.class.getName(), List.of(ConeDep.class.getName(), "demo.Gone"));
        assertThat(alone.classes()).containsExactly(ConeFar.class.getName());
        assertThat(joined.classes())
                .as("the loaded class and what it reaches")
                .contains(ConeDep.class.getName(), ConeRoot.Inner.class.getName(), ConeFar.class.getName());
        assertThat(joined.digest()).isNotEqualTo(alone.digest());

        Files.delete(root.resolve(path(ConeDep.class)));
        assertThat(ClassCones.index(List.of(root))
                        .cone(ConeFar.class.getName(), List.of(ConeDep.class.getName()))
                        .digest())
                .as("the loaded class gone from the workspace")
                .isNotEqualTo(joined.digest());
    }

    @Test
    void a_class_name_passed_on_as_data_reaches_nothing(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp.resolve("classes"), ConeSpawns.class, ConeNamed.class);
        ClassCones.Cone cone = ClassCones.index(List.of(root)).cone(ConeSpawns.class.getName());
        assertThat(cone.classes()).containsExactly(ConeSpawns.class.getName());
    }

    @Test
    void a_cone_naming_the_service_loader_takes_in_the_providers(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp.resolve("classes"), ConeLoads.class, ConeProvider.class);
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        Files.writeString(services.resolve("java.lang.Runnable"), "# provider\n" + ConeProvider.class.getName() + "\n");
        ClassCones.Cone cone = ClassCones.index(List.of(root)).cone(ConeLoads.class.getName());
        assertThat(cone.classes()).contains(ConeProvider.class.getName());
    }

    private static Path tree(Path root, Class<?>... classes) throws Exception {
        for (Class<?> c : classes) {
            Path f = root.resolve(path(c));
            Files.createDirectories(f.getParent());
            Files.write(f, bytes(c));
        }
        return root;
    }

    private static String path(Class<?> c) {
        return c.getName().replace('.', '/') + ".class";
    }

    private static byte[] bytes(Class<?> c) throws Exception {
        try (InputStream in =
                Objects.requireNonNull(ClassConesTest.class.getClassLoader().getResourceAsStream(path(c)))) {
            return in.readAllBytes();
        }
    }
}

class ConeRoot {
    ConeDep dep = new ConeDep();

    static final class Inner {
        int far() {
            return new ConeFar().value();
        }
    }

    Object named() throws Exception {
        return Class.forName("cc.jumpkick.test.ConeNamed");
    }
}

class ConeDep {
    int twice() {
        return new ConeRoot.Inner().far() * 2;
    }
}

class ConeFar {
    int value() {
        return 1;
    }
}

class ConeNamed {}

class ConeSpawns {
    ProcessBuilder engine() {
        return new ProcessBuilder("java", "-cp", "engine.jar", "cc.jumpkick.test.ConeNamed");
    }
}

class ConeUnrelated {}

class ConeLoads {
    @Nullable
    Object first() {
        return ServiceLoader.load(Runnable.class).findFirst().orElse(null);
    }
}

class ConeProvider implements Runnable {
    @Override
    public void run() {}
}
