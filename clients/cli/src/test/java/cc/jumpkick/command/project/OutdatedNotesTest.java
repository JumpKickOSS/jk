// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.OutdatedReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The lines {@code jk outdated} prints under its table. */
class OutdatedNotesTest {

    @Test
    void a_moved_coordinate_gets_one_line_naming_where_it_went() {
        var moved = new OutdatedReport.Row(
                "a",
                "org.testcontainers:postgresql",
                "",
                "test",
                "1.21.4",
                "1.21.4",
                "2.0.5",
                "",
                "org.testcontainers:testcontainers-postgresql");
        var again = new OutdatedReport.Row(
                "b",
                "org.testcontainers:postgresql",
                "",
                "test",
                "1.21.3",
                "1.21.3",
                "2.0.5",
                "",
                "org.testcontainers:testcontainers-postgresql");
        var plain = new OutdatedReport.Row("a", "g:a", "", "main", "1.0", "1.0", "2.0", "");

        assertThat(OutdatedCommand.movedNotes(List.of(moved, again, plain)))
                .containsExactly("Moved: org.testcontainers:postgresql → org.testcontainers:testcontainers-postgresql"
                        + " (Latest is org.testcontainers:testcontainers-postgresql's; change the coordinate in"
                        + " jk.toml — `jk update` keeps coordinates)");
        assertThat(OutdatedCommand.toJson(List.of(moved)))
                .contains("\"relocated\":\"org.testcontainers:testcontainers-postgresql\"");
    }

    @Test
    void the_footer_says_an_update_writes_the_newest_on_the_major_not_compatible() {
        assertThat(OutdatedCommand.NEXT)
                .contains("moves each exact pin to the newest stable on its major")
                .doesNotContain("pins to Compatible");
    }
}
