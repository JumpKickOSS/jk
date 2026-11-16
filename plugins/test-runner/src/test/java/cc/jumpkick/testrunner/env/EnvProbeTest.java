// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The probe repoints a class's {@code System.getenv} calls at the recorder by its constant pool
 * alone, and the recorder notes each lookup by name — never the value — and passes it on.
 */
class EnvProbeTest {

    /** A class that looks variables up, rewritten and loaded by its own loader below. */
    public static final class Reader {
        public static String path() {
            return Objects.requireNonNullElse(System.getenv("PATH"), "");
        }

        public static int all() {
            return System.getenv().size();
        }
    }

    /** A class that reads system properties every way the probe repoints. */
    public static final class PropReader {
        public static String home() {
            return Objects.requireNonNullElse(System.getProperty("user.home"), "");
        }

        public static String fallback() {
            return System.getProperty("jk.probe.absent", "dflt");
        }

        public static int counted() {
            return Integer.getInteger("jk.probe.int", 7)
                    + (Boolean.getBoolean("jk.probe.bool") ? 1 : 0)
                    + Long.getLong("jk.probe.long", 2L).intValue();
        }
    }

    /** A class with no environment read. */
    public static final class Plain {
        public static int one() {
            return 1;
        }
    }

    @Test
    void a_rewritten_class_reports_its_lookups_and_still_gets_the_values(@TempDir Path tmp) throws Exception {
        byte[] rewritten = Objects.requireNonNull(EnvProbe.repoint(bytesOf(Reader.class)));
        Class<?> probed = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(Reader.class.getName(), rewritten, 0, rewritten.length);
            }
        }.define();
        System.setProperty(EnvLookups.CURRENT_TEST, "ex.SomeTest");
        try {
            assertThat(probed.getMethod("path").invoke(null)).isEqualTo(System.getenv("PATH"));
            assertThat(probed.getMethod("all").invoke(null))
                    .isEqualTo(System.getenv().size());
        } finally {
            System.clearProperty(EnvLookups.CURRENT_TEST);
        }

        Path out = tmp.resolve("lookups").resolve("1.env");
        EnvLookups.writeTo(out);
        assertThat(Files.readAllLines(out))
                .contains("ex.SomeTest\t" + Reader.class.getName() + "\tPATH\t" + EnvLookups.SET)
                .contains(
                        "ex.SomeTest\t" + Reader.class.getName() + "\t" + EnvLookups.WHOLE_MAP + "\t" + EnvLookups.SET)
                .noneMatch(line -> line.contains(Objects.requireNonNullElse(System.getenv("PATH"), "\u0000")));
    }

    @Test
    void a_rewritten_class_reports_its_property_reads_by_name_and_still_gets_the_values(@TempDir Path tmp)
            throws Exception {
        Class<?> probed = define(PropReader.class, EnvProbe.repoint(bytesOf(PropReader.class)));
        System.setProperty(EnvLookups.CURRENT_TEST, "ex.PropTest");
        try {
            assertThat(probed.getMethod("home").invoke(null)).isEqualTo(System.getProperty("user.home"));
            assertThat(probed.getMethod("fallback").invoke(null)).isEqualTo("dflt");
            assertThat(probed.getMethod("counted").invoke(null)).isEqualTo(9);
        } finally {
            System.clearProperty(EnvLookups.CURRENT_TEST);
        }

        Path out = tmp.resolve("lookups").resolve("1.props");
        PropLookups.writeTo(out);
        assertThat(Files.readAllLines(out))
                .contains("ex.PropTest\tuser.home\t" + EnvLookups.SET)
                .contains("ex.PropTest\tjk.probe.absent\t" + EnvLookups.UNSET)
                .contains("ex.PropTest\tjk.probe.int\t" + EnvLookups.UNSET)
                .contains("ex.PropTest\tjk.probe.bool\t" + EnvLookups.UNSET)
                .contains("ex.PropTest\tjk.probe.long\t" + EnvLookups.UNSET)
                .noneMatch(line -> line.contains(System.getProperty("user.home")));
    }

    @Test
    void with_properties_off_a_property_read_is_left_as_compiled() throws Exception {
        assertThat(EnvProbe.repoint(bytesOf(PropReader.class), false)).isNull();
        assertThat(EnvProbe.repoint(bytesOf(Reader.class), false))
                .as("variables are still probed")
                .isNotNull();
    }

    private Class<?> define(Class<?> type, byte @Nullable [] rewritten) {
        byte[] bytes = Objects.requireNonNull(rewritten);
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(type.getName(), bytes, 0, bytes.length);
            }
        }.define();
    }

    @Test
    void a_class_without_an_environment_read_is_left_as_compiled() throws Exception {
        assertThat(EnvProbe.repoint(bytesOf(Plain.class))).isNull();
    }

    private static byte[] bytesOf(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in =
                Objects.requireNonNull(EnvProbeTest.class.getClassLoader().getResourceAsStream(resource))) {
            return in.readAllBytes();
        }
    }
}
