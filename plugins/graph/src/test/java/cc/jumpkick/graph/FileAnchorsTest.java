// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code callers file:<path>}: a file's anchors with the other places that name each, on the
 * petclinic fixture of {@link AnchorEditsTest}; one line of it; and, after a hand edit, the places
 * that edit left under the old name first.
 */
class FileAnchorsTest {

    static String callers(RenameFixture ws, String address) throws IOException {
        GraphService.Request r = new GraphService.Request(
                "usages",
                List.of(address),
                "bfs",
                3,
                2000,
                List.of(),
                false,
                8,
                null,
                false,
                false,
                null,
                List.of(),
                null,
                Map.of());
        return GraphService.run(ws.workspace(), r, GraphBuild.Progress.NONE).text();
    }

    @Test
    void aSchemaFileListsItsTablesAndColumnsWithTheOtherSchemaAndTheEntity(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, AnchorEditsTest.petclinic());

        assertThat(callers(ws, "file:" + AnchorEditsTest.H2)).isEqualTo("""
                        src/main/resources/db/h2/schema.sql: 4 anchors, each with the other places that name it:
                          L1 table:owners (defined here): src/main/resources/db/mysql/schema.sql:1,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:8
                          L2 column:owners.id (defined here): src/main/resources/db/mysql/schema.sql:2
                          L3 column:owners.address (defined here): src/main/resources/db/mysql/schema.sql:3,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:12
                          L4 column:owners.telephone (defined here): src/main/resources/db/mysql/schema.sql:4,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:15""");
        assertThat(callers(ws, "file:./" + AnchorEditsTest.H2 + ":4")).isEqualTo("""
                        src/main/resources/db/h2/schema.sql:4: 1 anchor, each with the other places that name it:
                          L4 column:owners.telephone (defined here): src/main/resources/db/mysql/schema.sql:4,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:15""");
        assertThat(callers(ws, "file:" + dir.resolve(AnchorEditsTest.PROPERTIES)))
                .as("an absolute path; a key with the variable that sets it")
                .contains("L2 config:petclinic.visits.max-per-day (defined here): ")
                .contains("docker-compose.yml:6 as PETCLINIC_VISITS_MAXPERDAY (inferred)");
        assertThat(callers(ws, "file:README.md"))
                .isEqualTo("No anchor in README.md: no route, SQL table or column, config key or environment variable"
                        + " is named there.");
        assertThat(callers(ws, "file:nope.sql")).isEqualTo("No file nope.sql in the graph.");
    }

    @Test
    void afterAHandEditThePlacesItLeftUnderTheOldNameComeFirst(@TempDir Path dir) throws IOException {
        RenameFixture ws = new RenameFixture(dir, AnchorEditsTest.petclinic());
        AnchorEditsTest.refresh(ws);
        AnchorEditsTest.edit(ws, AnchorEditsTest.H2, "  telephone  VARCHAR(20)", "  phone      VARCHAR(20)");

        assertThat(callers(ws, "file:" + AnchorEditsTest.H2))
                .startsWith("""
                        Your last edit of it changed these names; these places still use the old one:
                          column:owners.telephone, now owners.phone: src/main/resources/db/mysql/schema.sql:4,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:15; prose: README.md:3
                        src/main/resources/db/h2/schema.sql: 4 anchors""")
                .contains("  L4 column:owners.phone (defined here): no other place names it");
    }
}
