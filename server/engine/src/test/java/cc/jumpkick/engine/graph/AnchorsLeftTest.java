// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lines a build's OK verdict ends with after a hand edit, from the rows the graph worker leaves:
 * petclinic's telephone column renamed in the H2 schema alone, and its visit limit key renamed in
 * application.properties alone, as the worker wrote them. Silent with nothing left, after a failed
 * run, and of prose unless {@code [graph] anchor-tail} asks for a count.
 */
class AnchorsLeftTest {

    static final String OWNER = "src/main/java/org/springframework/samples/petclinic/owner/Owner.java";
    static final String H2 = "src/main/resources/db/h2/schema.sql";
    static final String MYSQL = "src/main/resources/db/mysql/schema.sql";

    /** The telephone column renamed to phone in the H2 schema only. */
    static final String COLUMN = String.join(
            "",
            row(
                    "must",
                    "sql",
                    "column",
                    "owners.telephone",
                    "owners.phone",
                    "src/main/resources/db/hsqldb/schema.sql",
                    34,
                    "",
                    H2),
            row(
                    "must",
                    "sql",
                    "column",
                    "owners.telephone",
                    "owners.phone",
                    "src/main/resources/db/mysql/schema.sql",
                    37,
                    "",
                    H2),
            row(
                    "must",
                    "sql",
                    "column",
                    "owners.telephone",
                    "owners.phone",
                    "src/main/resources/db/postgres/schema.sql",
                    34,
                    "",
                    H2),
            row("must", "java", "column", "owners.telephone", "owners.phone", OWNER, 59, "", H2),
            row("may", "md", "column", "owners.telephone", "owners.phone", "README.md", 112, "", H2));

    /** petclinic.visits.max-per-day renamed in application.properties only. */
    static final String KEY = String.join(
            "",
            row(
                    "must",
                    "yml",
                    "config",
                    "petclinic.visits.max-per-day",
                    "petclinic.visits.daily-limit",
                    "docker-compose.yml",
                    29,
                    "PETCLINIC_VISITS_MAXPERDAY",
                    "src/main/resources/application.properties"),
            row(
                    "must",
                    "yml",
                    "config",
                    "petclinic.visits.max-per-day",
                    "petclinic.visits.daily-limit",
                    ".github/workflows/load-test.yml",
                    7,
                    "PETCLINIC_VISITS_MAXPERDAY",
                    "src/main/resources/application.properties"),
            row(
                    "must",
                    "java",
                    "config",
                    "petclinic.visits.max-per-day",
                    "petclinic.visits.daily-limit",
                    "src/main/java/org/springframework/samples/petclinic/system/VisitLimits.java",
                    11,
                    "",
                    "src/main/resources/application.properties"),
            row(
                    "may",
                    "md",
                    "config",
                    "petclinic.visits.max-per-day",
                    "petclinic.visits.daily-limit",
                    "docs/visits.md",
                    4,
                    "",
                    "src/main/resources/application.properties"),
            row(
                    "may",
                    "txt",
                    "config",
                    "petclinic.visits.max-per-day",
                    "petclinic.visits.daily-limit",
                    "NOTES.txt",
                    2,
                    "",
                    "src/main/resources/application.properties"));

    /**
     * As the worker wrote it on spring-petclinic itself, the telephone column renamed in MySQL's schema
     * alone: H2's and Postgres' schemas, Postgres' seed (ten INSERTs naming the column), the entity.
     */
    static final String MYSQL_ONLY = String.join(
            "",
            row("must", "sql", "column", "owners.telephone", "owners.phone", H2, 42, "", MYSQL),
            row(
                    "must",
                    "sql",
                    "column",
                    "owners.telephone",
                    "owners.phone",
                    "src/main/resources/db/postgres/schema.sql",
                    32,
                    "",
                    MYSQL),
            seed(25, 34),
            row("must", "java", "column", "owners.telephone", "owners.phone", OWNER, 65, "", MYSQL));

    static final String OK = "OK test spring-petclinic · 61 passed · 9.4s\n";

    private static String seed(int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int line = from; line <= to; line++) {
            s.append(row(
                    "must",
                    "sql",
                    "column",
                    "owners.telephone",
                    "owners.phone",
                    "src/main/resources/db/postgres/data.sql",
                    line,
                    "",
                    MYSQL));
        }
        return s.toString();
    }

    private static String row(
            String grade,
            String place,
            String kind,
            String old,
            String now,
            String rel,
            int line,
            String written,
            String edited) {
        return String.join("\t", grade, place, kind, old, now, rel, String.valueOf(line), written, edited) + "\n";
    }

    private static Path workspace(Path root, String rows, String graph) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "org.springframework.samples"
                name = "spring-petclinic"
                version = "3.5.0"
                java = 25
                """ + graph);
        Path dir = Files.createDirectories(root.resolve("target/graph/anchors"));
        Files.writeString(dir.resolve("../graph.bin"), "");
        Files.writeString(dir.resolve("stale"), rows);
        return root;
    }

    @Test
    void aColumnRenamedInOneSchemaNamesThePlacesThatMustChangeAndNothingOfTheProse(@TempDir Path root)
            throws IOException {
        assertThat(AnchorsLeft.after(workspace(root, COLUMN, "").toString(), OK))
                .isEqualTo("""
                        Your edit changed these names; these places still use the old one:
                          column:owners.telephone, now owners.phone: src/main/resources/db/hsqldb/schema.sql:34,\
                         src/main/resources/db/mysql/schema.sql:37, src/main/resources/db/postgres/schema.sql:34,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:59
                        """);
    }

    @Test
    void aFilesManyLinesAreOnePlace(@TempDir Path root) throws IOException {
        assertThat(AnchorsLeft.after(workspace(root, MYSQL_ONLY, "").toString(), "OK build spring-petclinic · 9.7s\n"))
                .isEqualTo("""
                        Your edit changed these names; these places still use the old one:
                          column:owners.telephone, now owners.phone: src/main/resources/db/h2/schema.sql:42,\
                         src/main/resources/db/postgres/schema.sql:32, src/main/resources/db/postgres/data.sql:25 and 9\
                         more lines, src/main/java/org/springframework/samples/petclinic/owner/Owner.java:65
                        """);
    }

    @Test
    void pastTheCapTheRestIsCountedWithTheCallThatListsThem(@TempDir Path root) throws IOException {
        assertThat(AnchorsLeft.after(workspace(root, COLUMN + KEY, "").toString(), OK))
                .isEqualTo("""
                        Your edit changed these names; these places still use the old one:
                          column:owners.telephone, now owners.phone: src/main/resources/db/hsqldb/schema.sql:34,\
                         src/main/resources/db/mysql/schema.sql:37, src/main/resources/db/postgres/schema.sql:34,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:59
                          config:petclinic.visits.max-per-day, now petclinic.visits.daily-limit:\
                         docker-compose.yml:29 as PETCLINIC_VISITS_MAXPERDAY
                          +2 more: callers(address="config:petclinic.visits.max-per-day")
                        """);
        assertThat(AnchorsLeft.more(AnchorsLeft.read(root.resolve("target/graph/anchors/stale"))))
                .as("places of two anchors: callers on each")
                .isEqualTo("callers(address=\"column:owners.telephone\"),"
                        + " callers(address=\"config:petclinic.visits.max-per-day\")");
    }

    @Test
    void theProseIsCountedOnlyWhenTheSettingAsks(@TempDir Path root) throws IOException {
        String dir =
                workspace(root, KEY, "\n[graph]\nanchor-tail = \"kinds\"\n").toString();
        assertThat(AnchorsLeft.after(dir, OK))
                .endsWith(
                        "  config:petclinic.visits.max-per-day, now petclinic.visits.daily-limit: docker-compose.yml:29"
                                + " as PETCLINIC_VISITS_MAXPERDAY, .github/workflows/load-test.yml:7 as"
                                + " PETCLINIC_VISITS_MAXPERDAY, src/main/java/org/springframework/samples/petclinic/system/"
                                + "VisitLimits.java:11\nDocs use the old name too: 1 markdown, 1 text.\n");
        Files.writeString(
                root.resolve("jk.toml"),
                Files.readString(root.resolve("jk.toml")).replace("kinds", "count"));
        assertThat(AnchorsLeft.after(dir, OK)).endsWith("\nDocs use the old name too: 2 places.\n");
    }

    @Test
    void onlyProseLeftIsSilentByDefaultAndACountWhenAsked(@TempDir Path root) throws IOException {
        String prose = COLUMN.lines()
                .filter(l -> l.startsWith("may"))
                .map(l -> l + "\n")
                .reduce("", String::concat);
        assertThat(AnchorsLeft.after(workspace(root, prose, "").toString(), OK)).isEmpty();
        Files.writeString(
                root.resolve("jk.toml"),
                Files.readString(root.resolve("jk.toml")) + "\n[graph]\nanchor-tail = \"count\"\n");
        assertThat(AnchorsLeft.after(root.toString(), OK))
                .isEqualTo("Your edit changed a name the docs still use: 1 place.\n");
    }

    @Test
    void aFailedRunAGuardNoGraphOrNoRecordSaysNothing(@TempDir Path root) throws IOException {
        String dir = workspace(root, COLUMN, "").toString();
        assertThat(AnchorsLeft.after(dir, "FAIL test spring-petclinic · 1 failed\n"))
                .isEmpty();
        assertThat(AnchorsLeft.after(dir, "OK guard spring-petclinic · 8.3s\n")).isEmpty();
        Files.delete(root.resolve("target/graph/anchors/stale"));
        assertThat(AnchorsLeft.after(dir, OK)).isEmpty();
        Files.delete(root.resolve("target/graph/graph.bin"));
        assertThat(AnchorsLeft.after(dir, OK)).isEmpty();
    }
}
