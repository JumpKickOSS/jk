// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.wire.transcript.SessionStartLine;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every CLI JSONL record encodes a fully populated instance and decodes it back equal, and every
 * String component reaches the wire. The corpus is the package's own list; a record added without
 * being listed here is caught by {@link #every_record_in_the_package_is_listed}.
 */
class JsonlRecordRoundTripTest {
    private static final List<Class<?>> RECORDS = List.of(
            SessionStartLine.class,
            JobLine.class,
            PreflightLine.class,
            NoteLine.class,
            EtaLine.class,
            SessionFinishLine.class,
            PlanStartLine.class,
            TaskStartLine.class,
            ProgressLine.class,
            TickUpdateLine.class,
            LabelLine.class,
            OutputLine.class,
            WarnLine.class,
            ErrorLine.class,
            TestFailureErrorLine.class,
            TaskFinishLine.class,
            PlanFinishLine.class,
            WorkspaceProgressLine.class,
            WorkspaceStartLine.class,
            WorkspaceFinishLine.class,
            ModuleStartLine.class,
            ModuleFinishLine.class);

    @Test
    void every_record_round_trips_a_fully_populated_instance() throws Exception {
        for (Class<?> type : RECORDS) {
            Object original = populated(type);
            String line = (String) type.getMethod("encode").invoke(original);
            assertThat(line)
                    .as("%s opens with the envelope", type.getSimpleName())
                    .startsWith("{\"schema\":1,\"ts\":");
            for (RecordComponent c : type.getRecordComponents()) {
                if (c.getType() != String.class) continue;
                assertThat(line)
                        .as("%s.%s never reached the wire", type.getSimpleName(), c.getName())
                        .contains(String.valueOf(c.getAccessor().invoke(original)));
            }
            Object back = type.getDeclaredMethod("decode", String.class).invoke(null, line);
            assertThat(back)
                    .as("%s does not survive its own encode/decode pair", type.getSimpleName())
                    .isEqualTo(original);
        }
    }

    /** The CLI's own line records, and the header record the engine writes too from the wire module. */
    @Test
    void every_record_in_the_package_is_listed() throws Exception {
        List<String> records = new ArrayList<>();
        for (String pkg : List.of(
                "clients/cli/src/main/java/cc/jumpkick/cli/run/jsonl",
                "shared/wire/src/main/java/cc/jumpkick/wire/transcript")) {
            try (var files = Files.list(RepoRoot.dir(JsonlRecordRoundTripTest.class, pkg))) {
                files.map(f -> f.getFileName().toString())
                        .filter(n -> n.endsWith("Line.java"))
                        .map(n -> n.substring(0, n.length() - ".java".length()))
                        .forEach(records::add);
            }
        }
        assertThat(records)
                .containsExactlyInAnyOrderElementsOf(
                        RECORDS.stream().map(Class::getSimpleName).toList());
    }

    private static Object populated(Class<?> type) throws ReflectiveOperationException {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        int[] seq = {0};
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

    private static Object value(Class<?> type, Type generic, int[] seq) {
        int n = ++seq[0];
        if (type == String.class) return "v" + n;
        if (type == int.class) return n;
        if (type == long.class) return 1_000L + n;
        if (type == boolean.class) return n % 2 == 0;
        if (type == List.class) {
            Class<?> element = (Class<?>) ((ParameterizedType) generic).getActualTypeArguments()[0];
            return List.of(value(element, element, seq), value(element, element, seq));
        }
        throw new IllegalStateException("no synthetic value for " + type);
    }
}
