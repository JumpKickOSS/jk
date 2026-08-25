// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The contract behind "a wire message is a record with {@code encode()} and {@code decode(String)}
 * in one file" (JK-2431).
 *
 * <p>The alternative the house rule rejects is a {@code WireKeys} constant table: it moves the
 * literals into one file and still lets a decoder spell a key no encoder writes, because the two
 * halves stay in different classes. A record closes that by construction — but only if every record
 * actually carries both halves and both halves actually agree on every field. Nothing was checking
 * either.
 *
 * <p><strong>The corpus is discovered, never listed.</strong> {@link ReportDiscriminatorTest} names
 * eight records by hand and has been correct and incomplete since: the tree holds twelve. Four
 * ({@code CatalogReadAck}, {@code CacheInventoryAck}, {@code ModuleGraphAck}, {@code NewProjectAck})
 * were outside it, and seven — {@code DenyReport}, {@code PluginCommandReport}, {@code ExecPlan},
 * {@code GeneratedFiles}, {@code IdeWireModel}, {@code WhyReport}, {@code OutdatedReport} — had a
 * {@code decode} used in production (as a {@code ::decode} method reference from
 * {@code EngineReads}) and executed by no test at all. A hand-written list is how that happens, so
 * this one walks the package's own source directory and a new record is covered the day it lands.
 *
 * <p>Measured when written: 12 records discovered, 12 with both halves, 12 whose discriminator is a
 * token {@link EngineProtocol} declares, 12 round-tripping a fully populated instance. The
 * assertion on the discovered count is there because "no violations" and "scanned nothing" are the
 * same green otherwise.
 */
class WireRecordContractTest {

    /** Records in the package the day this landed. The floor, not the expectation. */
    private static final int RECORDS_WHEN_WRITTEN = 12;

    @Test
    void every_wire_record_carries_both_halves_in_one_file() throws Exception {
        for (Class<?> type : wireRecords()) {
            assertThat(type.isRecord())
                    .as("%s encodes a wire message, so it is a record", type.getSimpleName())
                    .isTrue();
            Method decode = type.getDeclaredMethod("decode", String.class);
            assertThat(Modifier.isStatic(decode.getModifiers()) && Modifier.isPublic(decode.getModifiers()))
                    .as("%s.decode(String) is public static", type.getSimpleName())
                    .isTrue();
            assertThat(decode.getReturnType())
                    .as("%s.decode returns its own type, not a map", type.getSimpleName())
                    .isEqualTo(type);
        }
    }

    /**
     * The discriminator is the first field of the line and its value comes from
     * {@link EngineProtocol}, whose constants are read here rather than re-typed — a re-typed
     * expectation is a second speller, which is the thing this whole campaign is about. G17 bans a
     * bare hyphenated type in source; this checks the value that actually reaches the wire.
     */
    @Test
    void every_wire_record_leads_with_a_type_engineprotocol_declares() throws Exception {
        Set<String> vocabulary = protocolTokens();
        assertThat(vocabulary).as("EngineProtocol's declared wire tokens").hasSizeGreaterThan(50);

        for (Class<?> type : wireRecords()) {
            String line = encode(populated(type));
            assertThat(line)
                    .as("%s must open with its discriminator — every client dispatches on it", type.getSimpleName())
                    .startsWith("{\"" + EngineProtocol.TYPE_FIELD + "\":\"");
            String discriminator = EngineProtocol.typeOf(line);
            assertThat(discriminator)
                    .as("%s's encoded line has a readable type", type.getSimpleName())
                    .isNotNull();
            assertThat(vocabulary)
                    .as("%s encodes `%s`, which EngineProtocol does not declare", type.getSimpleName(), discriminator)
                    .contains(discriminator);
        }
    }

    /**
     * A populated round trip, not an {@code error(…)} one. An error instance leaves almost every
     * component at its empty default, so it round-trips through an encoder that never wrote the
     * field and a decoder that defaulted it — true, and true for a reason unrelated to the
     * behaviour under test. Every component here holds a distinct synthetic value, so a dropped
     * field, a swapped pair, or a decoder reading a different key all fail.
     *
     * <p>The value is also asserted against the <em>wire text</em>: a String component's synthetic
     * value has to appear in the encoded line. A round trip alone is green when a field never
     * leaves the object and comes back from a default.
     */
    @Test
    void every_wire_record_round_trips_a_fully_populated_instance() throws Exception {
        for (Class<?> type : wireRecords()) {
            Object original = populated(type);
            String line = encode(original);

            for (RecordComponent c : type.getRecordComponents()) {
                if (c.getType() != String.class) continue;
                Object value = c.getAccessor().invoke(original);
                assertThat(line)
                        .as("%s.%s never reached the wire", type.getSimpleName(), c.getName())
                        .contains(String.valueOf(value));
            }

            Object back = type.getDeclaredMethod("decode", String.class).invoke(null, line);
            String detail = type.getSimpleName() + " does not survive its own encode/decode pair";
            assertThat(back).as(detail).isEqualTo(original);
        }
    }

    // ---- discovery + instance synthesis --------------------------------------------------------

    private static final String PACKAGE = "cc.jumpkick.engine.protocol";
    private static final String SRC = "shared/wire/src/main/java/cc/jumpkick/engine/protocol";

    /**
     * Every class in this package whose source declares {@code public String encode()}. The size
     * floor lives here rather than in one test, so that <em>no</em> caller can iterate an empty
     * corpus and report green — a discovery walk that finds nothing satisfies every for-loop below.
     */
    private static List<Class<?>> wireRecords() throws IOException, ClassNotFoundException {
        Path dir = repoRoot().resolve(SRC);
        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : new TreeSet<>(files.toList())) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".java")) continue;
                String body = Files.readString(f, StandardCharsets.UTF_8);
                if (!body.contains("public String encode()")) continue;
                found.add(Class.forName(PACKAGE + "." + name.substring(0, name.length() - ".java".length())));
            }
        }
        assertThat(found)
                .as("records declaring `public String encode()` under %s — too few means the walk missed them", SRC)
                .hasSizeGreaterThanOrEqualTo(RECORDS_WHEN_WRITTEN);
        return found;
    }

    /** Every wire token {@link EngineProtocol} declares — the vocabulary, read from its owner. */
    private static Set<String> protocolTokens() throws IllegalAccessException {
        Set<String> tokens = new LinkedHashSet<>();
        for (var f : EngineProtocol.class.getDeclaredFields()) {
            if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
            String value = (String) f.get(null);
            if (value != null && !value.isEmpty()) tokens.add(value);
        }
        return tokens;
    }

    private static String encode(Object record) throws Exception {
        return (String) record.getClass().getMethod("encode").invoke(record);
    }

    /**
     * Build an instance through the canonical constructor with a distinct value per component:
     * {@code v1}, {@code v2}, … for strings (alphanumeric, so the {@code |}- and {@code ,}-joined
     * row encodings stay unambiguous), ascending numbers, alternating booleans, two-element lists,
     * and nested records built the same way.
     */
    private static Object populated(Class<?> type) throws ReflectiveOperationException {
        return build(type, new int[] {0});
    }

    private static Object build(Class<?> type, int[] seq) throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
            arguments[i] = value(components[i].getType(), components[i].getGenericType(), seq);
        }
        try {
            return type.getDeclaredConstructor(parameterTypes).newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("cannot build a populated " + type.getSimpleName(), e.getCause());
        }
    }

    private static Object value(Class<?> type, Type generic, int[] seq) throws ReflectiveOperationException {
        int n = ++seq[0];
        if (type == String.class) return "v" + n;
        if (type == int.class || type == Integer.class) return n;
        if (type == long.class || type == Long.class) return 1_000L + n;
        if (type == double.class || type == Double.class) return n + 0.5d;
        if (type == boolean.class || type == Boolean.class) return n % 2 == 0;
        if (type == List.class) {
            Class<?> element = (Class<?>) ((ParameterizedType) generic).getActualTypeArguments()[0];
            return List.of(value(element, element, seq), value(element, element, seq));
        }
        if (type.isRecord()) return build(type, seq);
        throw new IllegalStateException("no synthetic value for wire component type " + type);
    }

    /** The checkout root, walking up from this class's own location — never {@code user.dir}. */
    private static Path repoRoot() throws IOException {
        Path here;
        try {
            here = Path.of(WireRecordContractTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IOException("cannot locate this test's own class output", e);
        }
        for (Path d = here; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("settings.gradle.kts")) && Files.isDirectory(d.resolve("shared/wire"))) {
                return d;
            }
        }
        throw new IOException("cannot locate the jk checkout root from " + here);
    }
}
