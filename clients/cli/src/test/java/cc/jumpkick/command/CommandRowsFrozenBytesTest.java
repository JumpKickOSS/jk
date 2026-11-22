// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.run.JsonlShape;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.project.AddCommand;
import cc.jumpkick.command.project.ExplainCommand;
import cc.jumpkick.command.system.AuthStatusCommand;
import cc.jumpkick.command.system.FlakyCommand;
import cc.jumpkick.command.system.LibraryListCommand;
import cc.jumpkick.command.system.TasksCommand;
import cc.jumpkick.command.toolchain.TrustCommand;
import cc.jumpkick.config.TestQuarantine;
import cc.jumpkick.config.TestQuarantineReport;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.wire.protocol.CatalogReadAck;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bytes of the command rows in {@code docs/user/machine-output.md}'s "Command rows" table,
 * spelled out. A reordered field, a renamed key or a dropped optional is a different line to every
 * script that parses them.
 */
class CommandRowsFrozenBytesTest {

    private static final String HEAD = "{\"schema\":1,\"ts\":7,\"type\":";

    @Test
    void dependency_added() {
        assertThat(JsonlShape.line(
                        7,
                        "dependency-added",
                        AddCommand.added("guava", "com.google.guava", "guava", "33.5.0-jre", null, "compile", null)))
                .isEqualTo(HEAD + "\"dependency-added\",\"library\":\"guava\",\"group\":\"com.google.guava\","
                        + "\"artifact\":\"guava\",\"version\":\"33.5.0-jre\",\"scope\":\"compile\"}");
        assertThat(JsonlShape.line(
                        7, "dependency-added", AddCommand.added("lib", "g", "a", "1", "linux", "test", "abc")))
                .isEqualTo(HEAD + "\"dependency-added\",\"library\":\"lib\",\"group\":\"g\",\"artifact\":\"a\","
                        + "\"version\":\"1\",\"scope\":\"test\",\"classifier\":\"linux\",\"sha256\":\"abc\"}");
    }

    @Test
    void explain_step() {
        var step = new TaskForecast.Task("compile-java", TaskForecast.Status.CACHED, "up to date", "0123abcd");
        assertThat(JsonlShape.line(7, "explain-step", ExplainCommand.stepFields("g:app", step)))
                .isEqualTo(HEAD + "\"explain-step\",\"module\":\"g:app\",\"step\":\"compile-java\","
                        + "\"status\":\"cached\",\"detail\":\"up to date\",\"key\":\"0123abcd\"}");
    }

    @Test
    void tasks_library_and_auth() {
        assertThat(JsonlShape.line(7, "task", TasksCommand.taskFields("g:app", "package-jar", "package", "Jar it")))
                .isEqualTo(HEAD + "\"task\",\"module\":\"g:app\",\"name\":\"package-jar\",\"stage\":\"package\","
                        + "\"description\":\"Jar it\"}");
        var entry = new CatalogReadAck.Entry("guava", "com.google.guava", "guava", "bundled", List.of());
        assertThat(JsonlShape.line(7, "library", LibraryListCommand.fields(entry)))
                .isEqualTo(HEAD + "\"library\",\"name\":\"guava\",\"coordinates\":\"com.google.guava:guava\","
                        + "\"layer\":\"bundled\"}");
        assertThat(JsonlShape.line(7, "auth", AuthStatusCommand.fields("github", "github.com", "store")))
                .isEqualTo(HEAD + "\"auth\",\"provider\":\"github\",\"host\":\"github.com\",\"authenticated\":true,"
                        + "\"source\":\"store\"}");
        assertThat(JsonlShape.line(7, "auth", AuthStatusCommand.fields("gitea", null, null)))
                .isEqualTo(HEAD + "\"auth\",\"provider\":\"gitea\",\"host\":null,\"authenticated\":false,"
                        + "\"source\":null}");
    }

    @Test
    void flaky() {
        var held = new TestQuarantine.Entry("a.FlakyTest", "app", "flaky", "2026-11-20", null);
        assertThat(JsonlShape.line(
                        7,
                        "flaky",
                        FlakyCommand.fields(new TestQuarantineReport.Row("a.FlakyTest", "app", held, "F.R.R"), 3)))
                .isEqualTo(
                        HEAD + "\"flaky\",\"class\":\"a.FlakyTest\",\"module\":\"app\",\"quarantined\":true,"
                                + "\"flakes\":2,\"runs\":3,\"history\":\"F.R.R\",\"since\":\"2026-11-20\",\"reason\":\"flaky\"}");
        assertThat(JsonlShape.line(
                        7, "flaky", FlakyCommand.fields(new TestQuarantineReport.Row("b.T", ".", null, "R"), 20)))
                .isEqualTo(HEAD + "\"flaky\",\"class\":\"b.T\",\"module\":\".\",\"quarantined\":false,"
                        + "\"flakes\":1,\"runs\":1,\"history\":\"R\"}");
    }

    @Test
    void trust_prints_rows_and_nothing_else_under_jsonl(@TempDir Path state) {
        String dir = state.toString();
        Capture.Streams add = Capture.both(() ->
                Jk.execute(new String[] {"trust", "add", "https://example.com/", "--state-dir", dir, "-O", "jsonl"}));
        assertThat(add.out())
                .matches("\\{\"schema\":1,\"ts\":\\d+,\"type\":\"trust\",\"action\":\"add\","
                        + "\"kind\":\"source\",\"value\":\"https://example.com/\",\"changed\":true}\\R");
        Capture.Streams list =
                Capture.both(() -> Jk.execute(new String[] {"trust", "list", "--state-dir", dir, "-O", "jsonl"}));
        assertThat(list.out())
                .matches("\\{\"schema\":1,\"ts\":\\d+,\"type\":\"trusted\",\"kind\":\"source\","
                        + "\"value\":\"https://example.com/\"}\\R");
    }

    @Test
    void trust() {
        assertThat(JsonlShape.line(7, "trust", TrustCommand.trustFields("add", "source", "https://x/", true)))
                .isEqualTo(HEAD + "\"trust\",\"action\":\"add\",\"kind\":\"source\",\"value\":\"https://x/\","
                        + "\"changed\":true}");
    }
}
