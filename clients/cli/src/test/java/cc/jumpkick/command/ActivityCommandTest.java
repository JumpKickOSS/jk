// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

class ActivityCommandTest {

    /** Compact history-entry JSONL (engine wire). */
    private static final String ENTRY =
            "{\"type\":\"history-entry\",\"id\":\"20260101T000000000-aaaa\",\"buildNumber\":31,"
                    + "\"kind\":\"build\",\"dir\":\"/proj\",\"coord\":\"com.example:app\","
                    + "\"startedAt\":1000,\"finishedAt\":3300,\"millis\":2300,"
                    + "\"success\":true,\"cancelled\":false,\"running\":false,"
                    + "\"moduleCount\":8,\"failedModules\":0}";

    private static final String FAIL =
            "{\"type\":\"history-entry\",\"id\":\"x\",\"buildNumber\":12,"
                    + "\"kind\":\"test\",\"dir\":\"/p\",\"coord\":\"g:n\","
                    + "\"startedAt\":1,\"finishedAt\":2,\"millis\":500,"
                    + "\"success\":false,\"cancelled\":false,\"running\":false,"
                    + "\"moduleCount\":1,\"failedModules\":1}";

    @Test
    void formats_success_line_with_build_number_and_coord() {
        String plain = strip(ActivityCommand.formatLine(ENTRY, 1000 + 3 * 3600_000L, false, Theme.active()));
        assertThat(plain).contains("#31");
        assertThat(plain).contains("Success");
        assertThat(plain).contains("com.example");
        assertThat(plain).contains("app");
        assertThat(plain).contains("build");
        assertThat(plain).contains("8 modules");
        assertThat(plain).contains("2.3s");
        assertThat(plain).containsPattern("\\d+h ago");
    }

    @Test
    void formats_failure() {
        String plain = strip(ActivityCommand.formatLine(FAIL, 10_000L, false, Theme.active()));
        assertThat(plain).contains("#12");
        assertThat(plain).contains("Failure");
        assertThat(plain).contains("test");
        assertThat(plain).contains("1 module");
    }

    @Test
    void single_pipeline_zero_modules_shows_one() {
        String entry =
                "{\"type\":\"history-entry\",\"buildNumber\":1,\"kind\":\"build\",\"dir\":\"/p\","
                        + "\"coord\":\"g:solo\",\"startedAt\":1,\"finishedAt\":2,\"millis\":100,"
                        + "\"success\":true,\"cancelled\":false,\"moduleCount\":0}";
        String plain = strip(ActivityCommand.formatLine(entry, 2, false, Theme.active()));
        assertThat(plain).contains("1 module");
        assertThat(plain).contains("#1");
    }

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
