// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verdict after a rename's check, from the rows the graph worker leaves: the petclinic tasks'
 * uses that no compile or test reads (a compose file's and a workflow's variable, the SQL column, a
 * message key, the JPA pin), as the worker wrote them.
 */
class RenameLeftTest {

    /** petclinic-anchor-relaxed-env after also=c1 (the properties key): the two variables are left. */
    static final String RELAXED_ENV = """
            c2\tmust\tenvironment variable for petclinic.visits.max-per-day\t.github/workflows/load-test.yml\t7
            c3\tmust\tenvironment variable for petclinic.visits.max-per-day\tdocker-compose.yml\t29
            """;

    /** petclinic-rename-telephone after also=c1-c7: the judgment calls and the pin are left. */
    static final String TELEPHONE = """
            c8\tmay\tcomment\tsrc/main/java/org/acme/petclinic/owner/OwnerController.java\t14
            c9\tmay\tSQL column\tsrc/main/resources/db/schema.sql\t3
            c10\tmay\tmessage key\tsrc/main/resources/messages/messages.properties\t1
            c11\tmay\tmessage key\tsrc/main/resources/messages/messages_de.properties\t1
            h1\tmay\thazard\t\t0
            """;

    @Test
    void aUseThatMustChangeAndIsLeftMeansNotDoneAndNamesWhereItIs(@TempDir Path root) throws IOException {
        assertThat(RenameLeft.after(write(root, RELAXED_ENV).toString()))
                .isEqualTo("Not done: no compile or test reads these uses, which should change too: environment"
                        + " variable for petclinic.visits.max-per-day at .github/workflows/load-test.yml:7,"
                        + " docker-compose.yml:29 (c2,c3). Apply them with also=c2,c3, or edit them.");
    }

    @Test
    void judgmentCallsLeftAreNamedByKindWithTheirIdsAndTheUnpinnedHazard(@TempDir Path root) throws IOException {
        assertThat(RenameLeft.after(write(root, TELEPHONE).toString()))
                .isEqualTo("Done where a compile or test reads it. Left as they were, and no compile or test reads"
                        + " them: comment at src/main/java/org/acme/petclinic/owner/OwnerController.java:14 (c8);"
                        + " SQL column at src/main/resources/db/schema.sql:3 (c9); message key at"
                        + " src/main/resources/messages/messages.properties:1,"
                        + " src/main/resources/messages/messages_de.properties:1 (c10,c11); h1 hazard, not pinned."
                        + " also=<id> changes one the task wants changed.")
                .doesNotContain("task is done");
    }

    @Test
    void nothingLeftOrNoRecordSaysNothing(@TempDir Path root) throws IOException {
        assertThat(RenameLeft.after(root.toString())).isEmpty();
        assertThat(RenameLeft.after(write(root, "").toString())).isEmpty();
    }

    @Test
    void manyUsesOfAKindAreCountedAndTheirIdsKeptAsRanges(@TempDir Path root) throws IOException {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 9; i++)
            rows.append("c")
                    .append(i)
                    .append("\tmust\tenvironment variable\tscripts/run")
                    .append(i)
                    .append(".sh\t2\n");
        assertThat(RenameLeft.after(write(root, rows.toString()).toString()))
                .contains(
                        "environment variable at scripts/run1.sh:2, scripts/run2.sh:2, scripts/run3.sh:2, +6 more (c1-c9)")
                .contains("also=c1-c9");
    }

    private static Path write(Path root, String rows) throws IOException {
        Path file = RenameCheck.proof(root).resolveSibling("unchecked");
        Files.createDirectories(file.getParent());
        Files.writeString(file, rows);
        return root;
    }
}
