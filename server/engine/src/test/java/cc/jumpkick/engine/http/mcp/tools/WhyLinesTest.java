// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.WhyReport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WhyLinesTest {

    @Test
    void a_pin_and_one_path_fit_in_five_lines() {
        WhyReport report = new WhyReport(
                null,
                List.of("com.google.guava:guava"),
                List.of("33.4.0"),
                List.of(""),
                List.of("org.springframework.boot:spring-boot-dependencies:4.0.0"),
                List.of("0"),
                List.of("app@1.0.0>com.google.guava:guava@33.4.0"),
                List.of("\t"),
                List.of(""),
                List.of());
        String text = WhyLines.of(report, "guava");
        assertThat(text.lines().count()).isLessThanOrEqualTo(WhyLines.MAX_LINES);
        assertThat(text).contains("com.google.guava:guava 33.4.0 · pinned by ");
        assertThat(text).contains("app@1.0.0 > com.google.guava:guava@33.4.0");
    }

    @Test
    void more_than_five_matches_are_capped() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 8; i++) names.add("g:a" + i);
        WhyReport report = new WhyReport(
                null, names, names, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(WhyLines.of(report, "a").lines().count()).isLessThanOrEqualTo(WhyLines.MAX_LINES);
    }

    @Test
    void an_exclusion_is_one_line() {
        WhyReport report = new WhyReport(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("com.acme:gone\tjk.toml:app\tcom.acme:parent@1.0"));
        assertThat(WhyLines.of(report, "gone"))
                .contains("com.acme:gone excluded under com.acme:parent@1.0 (by jk.toml:app)");
    }
}
