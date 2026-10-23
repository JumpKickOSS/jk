// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TaskStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A workspace member's step events say which module they belong to, and a finished step says what it did. */
class JsonlModuleTagTest {

    @Test
    void every_line_names_the_module_and_a_finished_step_carries_its_label() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonlListener listener =
                new JsonlListener(new PrintStream(bytes, true, StandardCharsets.UTF_8), false, "com.example:lib");

        listener.stepStart("compile-java", null, 1);
        listener.label("compile-java", "cache hit 1a2b3c4d");
        listener.stepFinish("compile-java", null, TaskStatus.SUCCESS, Duration.ofMillis(5), Duration.ZERO);

        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(3).allMatch(l -> "com.example:lib".equals(Jsonl.str(l, "module")));
        assertThat(Jsonl.str(lines.get(2), "label")).isEqualTo("cache hit 1a2b3c4d");
    }

    @Test
    void a_lone_plan_names_no_module() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonlListener listener = new JsonlListener(new PrintStream(bytes, true, StandardCharsets.UTF_8), false);
        listener.stepStart("compile-java", null, 1);
        assertThat(Jsonl.str(bytes.toString(StandardCharsets.UTF_8).strip(), "module"))
                .isNull();
    }
}
