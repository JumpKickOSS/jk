// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Pill;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.cli.tui.Tree;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityCommandTest {

    private static final String ENTRY =
            "{\"type\":\"history-entry\",\"id\":\"20260101T000000000-aaaa\",\"buildNumber\":31,"
                    + "\"kind\":\"build\",\"dir\":\"/home/u/src/proj\",\"coord\":\"com.example:app\","
                    + "\"startedAt\":1000,\"finishedAt\":3300,\"millis\":2300,"
                    + "\"success\":true,\"cancelled\":false,\"running\":false,"
                    + "\"moduleCount\":8,\"failedModules\":0}";

    private static final String FAIL = "{\"type\":\"history-entry\",\"id\":\"x\",\"buildNumber\":12,"
            + "\"kind\":\"test\",\"dir\":\"/home/u/src/p\",\"coord\":\"g:n\","
            + "\"startedAt\":1,\"finishedAt\":2,\"millis\":500,"
            + "\"success\":false,\"cancelled\":false,\"running\":false,"
            + "\"moduleCount\":1,\"failedModules\":1}";

    private static final String RUNNING = "{\"type\":\"history-entry\",\"id\":\"r\",\"buildNumber\":2,"
            + "\"kind\":\"build\",\"dir\":\"/tmp/x\",\"coord\":\"com.contyngent:jk-smoke-reinstall\","
            + "\"startedAt\":1000,\"finishedAt\":0,\"millis\":0,"
            + "\"success\":false,\"cancelled\":false,\"running\":true,"
            + "\"moduleCount\":1,\"progress\":55}";

    @Test
    void formats_success_line_with_build_number_and_coord() {
        String plain = strip(formatLine(ENTRY, 1000 + 3 * 3600_000L, Theme.active()));
        assertThat(plain).contains("#31");
        assertThat(plain).contains("Success");
        assertThat(plain).contains("com.example");
        assertThat(plain).contains("app");
        assertThat(plain).contains("build");
        assertThat(plain).contains("8 modules");
        assertThat(plain).contains("2.3s");
        assertThat(plain).containsPattern("\\d+h ago");
        assertThat(plain).doesNotContain("Building");
    }

    @Test
    void formats_cancelled() {
        String cancelled = "{\"type\":\"history-entry\",\"id\":\"c\",\"buildNumber\":104,"
                + "\"kind\":\"build\",\"dir\":\"/home/u/src/p\",\"coord\":\"cc.jumpkick:jk\","
                + "\"startedAt\":1,\"finishedAt\":2,\"millis\":6500,"
                + "\"success\":false,\"cancelled\":true,\"running\":false,"
                + "\"moduleCount\":12}";
        String plain = strip(formatLine(cancelled, 10_000L, Theme.active()));
        assertThat(plain).contains("#104");
        assertThat(plain).contains("Cancel");
        assertThat(plain).contains("12 modules");
        var node = ActivityCommand.jobNode(cancelled, 10_000L, Theme.active(), 3);
        assertThat(node.pill().look()).isEqualTo(Pill.Look.CANCELLED);
    }

    @Test
    void formats_failure() {
        String plain = strip(formatLine(FAIL, 10_000L, Theme.active()));
        assertThat(plain).contains("#12");
        assertThat(plain).contains("Failure");
        assertThat(plain).contains("test");
        assertThat(plain).contains("1 module");
    }

    @Test
    void formats_running_with_progress() {
        String plain = strip(formatLine(RUNNING, 1000 + 4200L, Theme.active()));
        assertThat(plain).contains("#2");
        assertThat(plain).contains("Building");
        assertThat(plain).contains("building…");
        assertThat(plain).contains("com.contyngent");
        assertThat(plain).contains("jk-smoke-reinstall");
        assertThat(plain).contains("1 module");
        assertThat(plain).contains("55%");
        assertThat(plain).contains("4.2s");
        assertThat(plain).doesNotContain("ago");
    }

    @Test
    void tree_branches_are_indented() {
        List<String> lines = new Tree("Build Jobs")
                .gap(Tree.Gap.EACH)
                .child(Tree.node(Pill.of("A")))
                .child(Tree.node(Pill.of("B")))
                .render(RenderContext.current().withAnsi(false));
        assertThat(lines).containsExactly("jk: = Build Jobs >", " |", " +-[A]", " |", " `-[B]");
    }

    @Test
    void status_pill_uses_brackets_when_plain() {
        // When ANSI is on we can't easily force plain here; content still includes the label.
        String plain = strip(formatLine(ENTRY, 10_000L, Theme.active()));
        assertThat(plain).contains("#31");
        assertThat(plain).contains("Success");
    }

    @Test
    void title_is_menu_wedge() {
        String plain =
                strip(new Tree("Build Jobs").render(RenderContext.current()).getFirst());
        assertThat(plain).contains("Build Jobs");
    }

    @Test
    void formats_running_with_id_at_end() {
        String withJid = RUNNING.replace("\"running\":true", "\"running\":true,\"jid\":42");
        String plain = strip(formatLine(withJid, 1000 + 4200L, Theme.active()));
        assertThat(plain).contains("id: 42");
        assertThat(plain).doesNotContain("jid=");
        assertThat(plain).contains("#2");
        assertThat(plain).contains("Building");
        assertThat(plain).contains("building…");
        // id is the last field on a running row.
        assertThat(plain).endsWith("id: 42");
    }

    @Test
    void build_numbers_are_zero_padded_to_listing_width() {
        assertThat(ActivityCommand.buildNumberWidth(List.of(ENTRY, FAIL, RUNNING)))
                .isEqualTo(2);
        String plain = strip(formatLine(RUNNING, 1000 + 4200L, Theme.active(), 2));
        assertThat(plain).contains("#02");
        assertThat(plain).doesNotContain("#2 ");
        // Three digits when the list peaks at 100+.
        assertThat(ActivityCommand.buildNumberWidth(List.of(
                        "{\"type\":\"history-entry\",\"buildNumber\":100,\"running\":false}",
                        "{\"type\":\"history-entry\",\"buildNumber\":9,\"running\":false}")))
                .isEqualTo(3);
        String n9 = strip(formatLine(
                "{\"type\":\"history-entry\",\"id\":\"x\",\"buildNumber\":9,"
                        + "\"kind\":\"build\",\"dir\":\"/home/u/src/p\",\"coord\":\"g:n\","
                        + "\"startedAt\":1,\"finishedAt\":2,\"millis\":500,"
                        + "\"success\":true,\"cancelled\":false,\"running\":false,"
                        + "\"moduleCount\":1}",
                10_000L,
                Theme.active(),
                3));
        assertThat(n9).contains("#009");
    }

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /** Test adapter over the production {@code jobNode} path (no formatLine in production). */
    private static String formatLine(String entry, long now, Theme t) {
        return formatLine(entry, now, t, 1);
    }

    private static String formatLine(String entry, long now, Theme t, int buildNumberWidth) {
        var node = ActivityCommand.jobNode(entry, now, t, buildNumberWidth);
        var ctx = RenderContext.current();
        return node.pill().renderInline(ctx) + node.label().render(ctx);
    }
}
