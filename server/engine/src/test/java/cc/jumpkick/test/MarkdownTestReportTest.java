// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownTestReportTest {

    @Test
    void publish_then_take_under_project_dir(@TempDir Path ws) {
        Path core = ws.resolve("core");
        Path other = ws.getParent().resolve("other-project");
        MarkdownTestReport a = new MarkdownTestReport();
        a.recordFinished("[class:com.acme.FooTest]", "bar()", 12, null);
        a.publish(core.toString(), "g:core");
        MarkdownTestReport b = new MarkdownTestReport();
        b.recordFinished("[class:com.other.ZedTest]", "zed()", 3, "{\"message\":\"boom\",\"stack\":\"at Z\"}");
        b.publish(other.toString(), "g:other");

        List<MarkdownTestReport.ModuleRun> mine = MarkdownTestReport.takeUnder(ws);
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().label()).isEqualTo("g:core");
        assertThat(mine.getFirst().entries()).hasSize(1);
        assertThat(mine.getFirst().entries().getFirst().className()).isEqualTo("com.acme.FooTest");
        assertThat(mine.getFirst().entries().getFirst().isPass()).isTrue();

        List<MarkdownTestReport.ModuleRun> leftover = MarkdownTestReport.takeUnder(other);
        assertThat(leftover).hasSize(1);
        assertThat(leftover.getFirst().entries().getFirst().isFail()).isTrue();
    }

    @Test
    void class_name_from_junit_unique_id() {
        assertThat(MarkdownTestReport.classNameFrom("[engine:junit-jupiter]/[class:com.acme.FooTest]/[method:bar()]"))
                .isEqualTo("com.acme.FooTest");
        assertThat(MarkdownTestReport.classNameFrom("not-a-unique-id")).isEqualTo("not-a-unique-id");
    }
}
