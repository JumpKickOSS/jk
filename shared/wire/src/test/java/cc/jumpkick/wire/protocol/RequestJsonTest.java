// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.testing.RepoRoot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Contract for ordered job-request writing and default omission. */
class RequestJsonTest {

    private static final String SRC = "shared/wire/src/main/java/cc/jumpkick/wire/protocol";

    /**
     * The trigger and progress mode are the requester's facts, carried as components: a build the
     * engine encodes for the dashboard says web whatever JK_BUILD_TRIGGER the daemon started under.
     */
    @Test
    void trigger_and_progress_mode_are_components_not_environment_reads() {
        TestRequest cli = new TestRequest(
                "/p", "/c", null, 0, null, false, false, false, true, TestSelection.DEFAULT, "ci", "plain");
        String json = cli.encode();
        assertThat(json).contains("\"trigger\":\"ci\"").contains("\"progressMode\":\"plain\"");
        TestRequest back = TestRequest.decode(json);
        assertThat(back.trigger()).isEqualTo("ci");
        assertThat(back.progressMode()).isEqualTo("plain");
        TestRequest bare = new TestRequest(
                "/p", "/c", null, 0, null, false, false, false, true, TestSelection.DEFAULT, null, null);
        assertThat(bare.encode()).doesNotContain("trigger").doesNotContain("progressMode");
    }

    @Test
    void writer_preserves_order_and_owns_typed_omission() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("k", "v");

        String json = RequestJson.request(EngineProtocol.BUILD_REQUEST)
                .bool("bool", true)
                .number("int", 3)
                .number("long", 4L)
                .string("string", "value")
                .string("nullString", null)
                .array("array", List.of("a"))
                .map("map", map)
                .optionalTrue("falseDefault", false)
                .optionalString("missingString", null)
                .optionalNonBlankString("blankString", " ")
                .optionalArray("emptyArray", List.of())
                .optionalMap("emptyMap", Map.of())
                .finish();

        assertThat(json)
                .isEqualTo("{\"type\":\"build-request\",\"bool\":true,\"int\":3,\"long\":4,"
                        + "\"string\":\"value\",\"nullString\":null,\"array\":[\"a\"],"
                        + "\"map\":{\"k\":\"v\"}}");
    }

    @Test
    void every_job_request_round_trips_its_defaults() throws Exception {
        List<Class<?>> records = requestRecords();
        assertThat(records).hasSize(47);
        for (Class<?> type : records) {
            Object original = defaultInstance(type);
            String encoded = (String) type.getMethod("encode").invoke(original);
            Object decoded = type.getMethod("decode", String.class).invoke(null, encoded);
            assertThat(decoded)
                    .as("%s default encode/decode", type.getSimpleName())
                    .isEqualTo(original);
        }
    }

    @Test
    void default_optional_fields_stay_omitted() {
        String build = new BuildRequest(
                        null,
                        null,
                        null,
                        0,
                        null,
                        false,
                        false,
                        0,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        TestSelection.DEFAULT,
                        List.of(),
                        false,
                        null,
                        Map.of(),
                        null,
                        null,
                        null)
                .encode();
        assertThat(build)
                .doesNotContain(
                        "\"ephemeralActions\"",
                        "\"testOnly\"",
                        "\"dirtyHint\"",
                        "\"allSuites\"",
                        "\"modules\"",
                        "\"keepGoing\"",
                        "\"workspaceTarget\"",
                        "\"graalHomes\"",
                        "\"m2Dir\"");

        String single = new SingleBuildRequest(
                        null, null, null, 0, null, false, false, false, false, null, TestSelection.DEFAULT)
                .encode();
        assertThat(single).doesNotContain("\"allSuites\"", "\"suites\"", "\"includeTags\"");

        String provision = new ProvisionRequest(null, null, false, false, null, null).encode();
        assertThat(provision).doesNotContain("\"tool\"", "\"version\"");
    }

    @Test
    void request_records_have_no_handwritten_object_encoder() throws Exception {
        Path dir = RepoRoot.dir(RequestJsonTest.class, SRC);
        for (Class<?> type : requestRecords()) {
            String source = Files.readString(dir.resolve(type.getSimpleName() + ".java"), StandardCharsets.UTF_8);
            assertThat(source)
                    .as(type.getSimpleName())
                    .contains("RequestJson.request(")
                    .doesNotContain("\"{\\\"type\\\"");
        }
    }

    private static List<Class<?>> requestRecords() throws Exception {
        Path dir = RepoRoot.dir(RequestJsonTest.class, SRC);
        List<Class<?>> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : new TreeSet<>(files.toList())) {
                String fileName = path.getFileName().toString();
                if (!fileName.endsWith("Request.java") && !fileName.equals("TimelineEvent.java")) continue;
                String name = fileName.replace(".java", "");
                Class<?> type = Class.forName("cc.jumpkick.wire.protocol." + name);
                if (type.isRecord()) records.add(type);
            }
        }
        return records;
    }

    private static Object defaultInstance(Class<?> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        @Nullable Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
            arguments[i] = defaultValue(components[i].getType(), components[i].getGenericType());
        }
        try {
            return type.getDeclaredConstructor(parameterTypes).newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("cannot build default " + type.getSimpleName(), e.getCause());
        }
    }

    private static @Nullable Object defaultValue(Class<?> type, Type generic) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == List.class) return List.of();
        if (type == Map.class) return Map.of();
        return null;
    }
}
