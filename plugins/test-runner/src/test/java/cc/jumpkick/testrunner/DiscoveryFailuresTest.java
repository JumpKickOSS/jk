// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test class the Platform's scan could not load is dropped without a word; the runner loads the
 * root's classes once more through the same loader and fails discovery naming each test class
 * that throws and why, while a helper that throws is left where discovery left it. The loaders
 * here stand in for a framework's: one initializes the class while loading it and its static
 * initializer throws, one hides a supertype so the class fails to link.
 */
class DiscoveryFailuresTest {

    private static final String STATIC_INIT = StaticInitTestFixture.class.getName();
    private static final String STATIC_INIT_HELPER = StaticInitFixture.class.getName();
    private static final String CHILD = MissingBaseFixture.class.getName();
    private static final String BASE = MissingBaseFixtureBase.class.getName();
    private static final String HELPER = MissingBaseHelperFixture.class.getName();
    private static final String HELPER_BASE = MissingBaseHelperFixtureBase.class.getName();
    private static final String SIGNATURE = MissingSignatureFixture.class.getName();
    private static final String SIGNATURE_TYPE = MissingSignatureFixtureType.class.getName();

    @Test
    void a_test_class_whose_static_initializer_throws_on_load_fails_discovery_naming_it_and_the_cause(@TempDir Path tmp)
            throws Exception {
        Path root = classpathRootOf(tmp, StaticInitTestFixture.class);
        var events = new Recorder();
        Run run = listWith(new InitializingLoader(getClass().getClassLoader(), STATIC_INIT), root, null, events);

        assertThat(run.exit()).isEqualTo(Exit.SOFTWARE);
        assertThat(run.err())
                .contains("jk-test-runner: test discovery failed: 1 class could not be loaded during discovery: "
                        + STATIC_INIT)
                .contains("  under " + root)
                .contains("  class: " + STATIC_INIT)
                .contains("  caused by: java.lang.ExceptionInInitializerError")
                .contains("  caused by: java.lang.IllegalStateException: application bootstrap failed in the static"
                        + " initializer")
                .contains("    at " + STATIC_INIT + ".boot(");
        assertThat(events.discovered())
                .as("no class is announced from a list the engine cannot trust")
                .isEmpty();
    }

    @Test
    void a_helper_whose_static_initializer_throws_on_load_is_not_a_failed_run(@TempDir Path tmp) throws Exception {
        Path root = classpathRootOf(tmp, StaticInitFixture.class, TagEmptiedFixture.class);
        var events = new Recorder();
        Run run = listWith(new InitializingLoader(getClass().getClassLoader(), STATIC_INIT_HELPER), root, null, events);

        assertThat(run.exit()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(events.discovered()).containsExactly(TagEmptiedFixture.class.getName());
    }

    @Test
    void a_helper_whose_supertype_is_missing_is_not_a_failed_run(@TempDir Path tmp) throws Exception {
        Path root = classpathRootOf(tmp, MissingBaseHelperFixture.class, TagEmptiedFixture.class);
        var events = new Recorder();
        Run run =
                listWith(new HidingLoader(getClass().getClassLoader(), root, HELPER, HELPER_BASE), root, null, events);

        assertThat(run.exit()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(events.discovered()).containsExactly(TagEmptiedFixture.class.getName());
    }

    /** The child declares no test of its own; the tests it inherits make it a test class. */
    @Test
    void a_test_class_whose_supertype_is_missing_fails_discovery_with_the_linkage_error(@TempDir Path tmp)
            throws Exception {
        Path root = classpathRootOf(tmp, MissingBaseFixture.class);
        var events = new Recorder();
        Run run = listWith(new HidingLoader(getClass().getClassLoader(), root, CHILD, BASE), root, null, events);

        assertThat(run.exit()).isEqualTo(Exit.SOFTWARE);
        assertThat(run.err())
                .contains("1 class could not be loaded during discovery: " + CHILD)
                .contains("  class: " + CHILD)
                .contains("  caused by: java.lang.NoClassDefFoundError: " + BASE.replace('.', '/'));
    }

    /**
     * The class loads — a parameter type is not resolved by loading — and the Platform drops it when
     * its filter reads the declared methods, so the probe reads them too and reports the type the
     * test classpath lacks.
     */
    @Test
    void a_test_class_whose_method_signature_names_a_missing_type_fails_discovery_with_the_linkage_error(
            @TempDir Path tmp) throws Exception {
        Path root = classpathRootOf(tmp, MissingSignatureFixture.class);
        var events = new Recorder();
        Run run = listWith(
                new HidingLoader(getClass().getClassLoader(), root, SIGNATURE, SIGNATURE_TYPE), root, null, events);

        assertThat(run.exit()).isEqualTo(Exit.SOFTWARE);
        assertThat(run.err())
                .contains("1 class could not be loaded during discovery: " + SIGNATURE)
                .contains("  class: " + SIGNATURE)
                .contains("  caused by: java.lang.NoClassDefFoundError: " + SIGNATURE_TYPE.replace('.', '/'));
        assertThat(events.discovered()).isEmpty();
    }

    @Test
    void the_class_filter_bounds_the_probe_to_the_classes_it_admits(@TempDir Path tmp) throws Exception {
        Path root = classpathRootOf(tmp, StaticInitFixture.class, TagEmptiedFixture.class);
        var events = new Recorder();
        Run run = listWith(
                new InitializingLoader(getClass().getClassLoader(), STATIC_INIT), root, "TagEmptiedFixture", events);

        assertThat(run.exit()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(events.discovered()).containsExactly(TagEmptiedFixture.class.getName());
    }

    @Test
    void a_root_every_class_of_which_loads_lists_its_classes_and_prints_nothing(@TempDir Path tmp) throws Exception {
        Path root = classpathRootOf(tmp, TagEmptiedFixture.class);
        var events = new Recorder();
        Run run = listWith(getClass().getClassLoader(), root, null, events);

        assertThat(run.exit()).isZero();
        assertThat(run.err()).isEmpty();
        assertThat(events.discovered()).containsExactly(TagEmptiedFixture.class.getName());
    }

    @Test
    void top_level_class_names_skip_nested_classes_and_descriptors(@TempDir Path tmp) throws Exception {
        Path pkg = Files.createDirectories(tmp.resolve("a/b"));
        Files.createFile(pkg.resolve("Outer.class"));
        Files.createFile(pkg.resolve("Outer$Inner.class"));
        Files.createFile(pkg.resolve("package-info.class"));
        Files.createFile(tmp.resolve("module-info.class"));
        Files.createFile(pkg.resolve("notes.txt"));

        assertThat(DiscoveryFailures.topLevelClassNames(tmp)).containsExactly("a.b.Outer");
    }

    @Test
    void the_headline_counts_classes_and_names_an_engine_apart() {
        assertThat(DiscoveryFailures.headline(List.of("a.B")))
                .isEqualTo("1 class could not be loaded during discovery: a.B");
        assertThat(DiscoveryFailures.headline(List.of("a.B", "a.C")))
                .isEqualTo("2 classes could not be loaded during discovery: a.B, a.C");
        assertThat(DiscoveryFailures.headline(List.of("engine junit-jupiter", "a.B")))
                .isEqualTo("discovery failed for: engine junit-jupiter, a.B");
    }

    // --- harness ------------------------------------------------------------------

    record Run(int exit, String err) {}

    /** List-only discovery over {@code root} with {@code loader} as the context loader, stderr captured. */
    private static Run listWith(ClassLoader loader, Path root, @Nullable String filter, Recorder events) {
        Thread thread = Thread.currentThread();
        ClassLoader prevLoader = thread.getContextClassLoader();
        PrintStream prevErr = System.err;
        var err = new ByteArrayOutputStream();
        thread.setContextClassLoader(loader);
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            int exit = LauncherPath.runListOnly(root, filter, List.of(), List.of(), 0, events);
            return new Run(exit, err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(prevErr);
            thread.setContextClassLoader(prevLoader);
        }
    }

    /** A classpath root holding only {@code classes}, copied out of this module's own output. */
    private static Path classpathRootOf(Path tmp, Class<?>... classes) throws IOException {
        for (Class<?> c : classes) {
            Path file = tmp.resolve(c.getName().replace('.', '/') + ".class");
            Files.createDirectories(Objects.requireNonNull(file.getParent()));
            try (InputStream in =
                    Objects.requireNonNull(c.getResourceAsStream(c.getSimpleName() + ".class"), c.getName())) {
                Files.copy(in, file);
            }
        }
        return tmp;
    }

    /**
     * Initializes {@code initialized} while loading it, the way a framework loader that boots the
     * application does; the first throwable is what every later load of that name gets, since the
     * JVM answers a second initialization of an erroneous class with a different error.
     */
    private static final class InitializingLoader extends ClassLoader {
        private final String initialized;
        private @Nullable Throwable first;

        InitializingLoader(ClassLoader parent, String initialized) {
            super(parent);
            this.initialized = initialized;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (!name.equals(initialized)) return super.loadClass(name);
            if (first != null) sneak(first);
            try {
                return Class.forName(name, true, getParent());
            } catch (Throwable t) {
                first = t;
                throw t;
            }
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneak(Throwable t) throws T {
            throw (T) t;
        }
    }

    /** Defines {@code child} from the root's bytes and answers {@code hidden} with class-not-found. */
    private static final class HidingLoader extends ClassLoader {
        private final Path root;
        private final String child;
        private final String hidden;

        HidingLoader(ClassLoader parent, Path root, String child, String hidden) {
            super(parent);
            this.root = root;
            this.child = child;
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(hidden)) throw new ClassNotFoundException(name);
            if (!name.equals(child)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try {
                    byte[] bytes = Files.readAllBytes(root.resolve(name.replace('.', '/') + ".class"));
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    /** Captures every event the emitter writes, in order. */
    private static final class Recorder implements EventWriter {
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final List<EventType> types = new ArrayList<>();

        @Override
        public void write(EventType type, Map<String, Object> payload) {
            types.add(type);
            events.add(new LinkedHashMap<>(payload));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        List<String> discovered() {
            var out = new ArrayList<String>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) == EventType.DISCOVERED)
                    out.add(String.valueOf(events.get(i).get("class")));
            }
            return out;
        }
    }
}
