// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.verbs.InertVerbHost;
import cc.jumpkick.engine.verbs.WorkspaceBuildVerb;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.layout.AnchorWatch;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lines a build's OK verdict ends with after a hand edit, from the rows the graph worker leaves:
 * petclinic's telephone column renamed in the H2 schema alone, and its visit limit key renamed in
 * application.properties alone, as the worker wrote them. By default the places that must change; nothing
 * with nothing left, after a failed run, with no graph, outside a workspace or with {@code anchor-tail = "off"};
 * and nothing of prose unless {@code anchor-tail} asks for a count.
 */
class AnchorsLeftTest {

    static final String OWNER = "src/main/java/org/springframework/samples/petclinic/owner/Owner.java";
    static final String H2 = "src/main/resources/db/h2/schema.sql";
    static final String MYSQL = "src/main/resources/db/mysql/schema.sql";

    /** The push's lines asked for by name, as a workspace gets them by default. */
    static final String SILENT = "\n[graph]\nanchor-tail = \"silent\"\n";

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
        assertThat(AnchorsLeft.after(workspace(root, COLUMN, SILENT).toString(), OK))
                .isEqualTo("""
                        Your edit changed these names; these places still use the old one:
                          column:owners.telephone, now owners.phone: src/main/resources/db/hsqldb/schema.sql:34,\
                         src/main/resources/db/mysql/schema.sql:37, src/main/resources/db/postgres/schema.sql:34,\
                         src/main/java/org/springframework/samples/petclinic/owner/Owner.java:59
                        """);
    }

    @Test
    void aFilesManyLinesAreOnePlace(@TempDir Path root) throws IOException {
        assertThat(AnchorsLeft.after(
                        workspace(root, MYSQL_ONLY, SILENT).toString(), "OK build spring-petclinic · 9.7s\n"))
                .isEqualTo("""
                        Your edit changed these names; these places still use the old one:
                          column:owners.telephone, now owners.phone: src/main/resources/db/h2/schema.sql:42,\
                         src/main/resources/db/postgres/schema.sql:32, src/main/resources/db/postgres/data.sql:25 and 9\
                         more lines, src/main/java/org/springframework/samples/petclinic/owner/Owner.java:65
                        """);
    }

    @Test
    void pastTheCapTheRestIsCountedWithTheCallThatListsThem(@TempDir Path root) throws IOException {
        assertThat(AnchorsLeft.after(workspace(root, COLUMN + KEY, SILENT).toString(), OK))
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
                .as("places of two anchors: each edited file, whose answer lists them")
                .isEqualTo("callers(address=\"file:src/main/resources/db/h2/schema.sql\"),"
                        + " callers(address=\"file:src/main/resources/application.properties\")");
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
        assertThat(AnchorsLeft.after(workspace(root, prose, SILENT).toString(), OK))
                .isEmpty();
        Files.writeString(
                root.resolve("jk.toml"),
                Files.readString(root.resolve("jk.toml")).replace("silent", "count"));
        assertThat(AnchorsLeft.after(root.toString(), OK))
                .isEqualTo("Your edit changed a name the docs still use: 1 place.\n");
    }

    /**
     * Every kind a workspace build journals as, after whose success it refreshes the graph, names the
     * places as a test does: a verdict's first word is that kind, so a gate, the call an agent finishes
     * with, reads {@code OK guard}. The kinds are what the build verb journals its jobs as (a native or
     * upload job needs a GraalVM or a {@code [publish]} to decode; their goals are walked by target), and
     * they must be every project-build kind jk runs itself. A sync builds nothing and carries nothing.
     */
    @Test
    void everyKindABuildJournalsAsNamesThePlacesAsATestDoes(@TempDir Path root) throws IOException {
        String dir = workspace(root, COLUMN, SILENT).toString();
        WorkspaceBuildVerb verb = new WorkspaceBuildVerb(new InertVerbHost());
        Set<String> journaled = new TreeSet<>();
        journaled.add(BuildHistoryKinds.GUARD);
        for (WorkspaceTarget target : WorkspaceTarget.values()) journaled.add(target.jobKind());
        for (String kind : verb.jobKinds()) {
            if (kind.equals("native") || kind.equals("upload")) continue;
            journaled.add(
                    verb.toJobRequest(verb.decodeJob(JobSpec.of(kind, dir))).verb());
        }
        assertThat(journaled).contains("guard", "test", "build", "install", "compile", "sync");
        Set<String> builds = new TreeSet<>(journaled);
        builds.removeIf(kind -> !BuildHistoryKinds.isBuildLike(kind));
        Set<String> ran = new TreeSet<>(BuildHistoryKinds.ALL);
        ran.removeIf(BuildHistoryKinds::isExternalTool);
        assertThat(builds).as("every kind jk builds the project under").isEqualTo(ran);
        for (String kind : builds) {
            String verdict = "OK " + kind + " spring-petclinic · 8.3s\n";
            assertThat(AnchorsLeft.after(dir, verdict))
                    .as(verdict.strip())
                    .startsWith("Your edit changed these names;");
        }
        assertThat(AnchorsLeft.after(dir, "OK sync spring-petclinic · 0.4s\n")).isEmpty();
    }

    @Test
    void aFailedRunNoGraphOrNoRecordSaysNothing(@TempDir Path root) throws IOException {
        String dir = workspace(root, COLUMN, "").toString();
        assertThat(AnchorsLeft.after(dir, "FAIL test spring-petclinic · 1 failed\n"))
                .isEmpty();
        assertThat(AnchorsLeft.after(dir, "FAIL guard spring-petclinic · 1 failed\n"))
                .isEmpty();
        Files.delete(root.resolve("target/graph/anchors/stale"));
        assertThat(AnchorsLeft.after(dir, OK)).isEmpty();
        Files.delete(root.resolve("target/graph/graph.bin"));
        assertThat(AnchorsLeft.after(dir, OK)).isEmpty();
    }

    /**
     * With no {@code [graph]} table at all, the trap's hasty edit (the column renamed in MySQL's schema
     * alone) gets the places: the default is {@code silent}, prose unsaid. A {@code [graph]} table that sets
     * only aliases is the same.
     */
    @Test
    void byDefaultTheVerdictNamesThePlacesAndNothingOfTheProse(@TempDir Path root) throws IOException {
        String dir = workspace(root, COLUMN, "").toString();
        String places = AnchorsLeft.after(dir, OK);
        assertThat(places)
                .isEqualTo(AnchorsLeft.after(workspace(root, COLUMN, SILENT).toString(), OK));
        assertThat(places).startsWith("Your edit changed these names;").doesNotContain("Docs");
        workspace(root, COLUMN, "\n[graph.aliases]\nOwner = [\"customer\"]\n");
        assertThat(AnchorsLeft.after(dir, OK)).isEqualTo(places);
    }

    /**
     * {@code anchor-tail = "off"} is the way out: the verdict is the plain one and the push does none of
     * its work. Here the refresh the build started is still running and the graph has no watch list, in
     * which state the default waits for that refresh; off answers at once, before the record is read.
     */
    @Test
    void offSaysNothingAndWaitsForNothing(@TempDir Path root) throws Exception {
        String dir = workspace(root, MYSQL_ONLY, "\n[graph]\nanchor-tail = \"off\"\n")
                .toString();
        String verdict = "OK build spring-petclinic · 9.7s\n";
        Path key = GraphRunner.rootOf(root);
        GraphRunner.holdSlot(key);
        try {
            long start = System.nanoTime();
            assertThat(verdict + AnchorsLeft.after(dir, verdict)).isEqualTo(verdict);
            assertThat((System.nanoTime() - start) / 1_000_000).as("no wait").isLessThan(2_000);
            Files.writeString(
                    root.resolve("jk.toml"),
                    Files.readString(root.resolve("jk.toml")).replace("anchor-tail = \"off\"\n", ""));
            Thread release = releaseAfter(key, 400);
            start = System.nanoTime();
            assertThat(AnchorsLeft.after(dir, verdict)).startsWith("Your edit changed these names;");
            assertThat((System.nanoTime() - start) / 1_000_000)
                    .as("the default waits for the refresh")
                    .isGreaterThanOrEqualTo(350);
            release.join();
        } finally {
            if (!GraphRunner.awaitRefresh(key, 0)) GraphRunner.releaseSlot(key);
        }
    }

    /**
     * A directory that is not a workspace (no jk.toml at its root) says nothing, even with a graph and a
     * record under it; nor does a root jk builds from its pom.xml, which has no {@code [graph]} to turn
     * the lines off with.
     */
    @Test
    void outsideAWorkspaceOrOnAPomItSaysNothing(@TempDir Path root) throws IOException {
        String dir = workspace(root, COLUMN, "").toString();
        Files.delete(root.resolve("jk.toml"));
        assertThat(AnchorsLeft.after(dir, OK)).isEmpty();
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        assertThat(AnchorsLeft.after(dir, OK)).isEmpty();
    }

    /**
     * An up-to-date run waits for no refresh when no file that held an anchor, or that the record
     * names, changed since the last one: the refresh the build started is still running here (its
     * slot held), and the record is read at once. A watched file edited since makes it wait, as does a
     * graph with no list (an older jk's).
     */
    @Test
    void theVerdictWaitsForTheRefreshOnlyWhenAWatchedFileChanged(@TempDir Path root) throws Exception {
        String dir = workspace(root, MYSQL_ONLY, SILENT).toString();
        Path schema = Files.createDirectories(root.resolve(MYSQL).getParent()).resolve("schema.sql");
        Files.writeString(schema, "CREATE TABLE owners (\n  phone VARCHAR(20)\n);\n");
        Path graph = root.resolve("target/graph");
        AnchorWatch.write(
                graph,
                List.of(new AnchorWatch.Entry(Hashing.sha256Hex(schema), StatIdentity.of(schema), MYSQL, Map.of())));
        Path key = GraphRunner.rootOf(root);
        GraphRunner.holdSlot(key);
        try {
            long start = System.nanoTime();
            assertThat(AnchorsLeft.after(dir, OK)).startsWith("Your edit changed these names;");
            assertThat((System.nanoTime() - start) / 1_000_000).as("no wait").isLessThan(2_000);

            Files.writeString(schema, "CREATE TABLE owners (\n  telephone VARCHAR(20)\n);\n");
            Thread release = releaseAfter(key, 400);
            start = System.nanoTime();
            AnchorsLeft.after(dir, OK);
            assertThat((System.nanoTime() - start) / 1_000_000)
                    .as("a watched file changed: waits for the refresh")
                    .isGreaterThanOrEqualTo(350);
            release.join();
        } finally {
            if (!GraphRunner.awaitRefresh(key, 0)) GraphRunner.releaseSlot(key);
        }

        AnchorWatch.write(graph, List.of());
        GraphRunner.holdSlot(key);
        try {
            long start = System.nanoTime();
            assertThat(AnchorsLeft.after(dir, OK)).startsWith("Your edit changed these names;");
            assertThat((System.nanoTime() - start) / 1_000_000)
                    .as("nothing anchored: no wait")
                    .isLessThan(2_000);
            Files.delete(AnchorWatch.file(graph));
            Thread release = releaseAfter(key, 400);
            start = System.nanoTime();
            AnchorsLeft.after(dir, OK);
            assertThat((System.nanoTime() - start) / 1_000_000)
                    .as("no list: waits, as before there was one")
                    .isGreaterThanOrEqualTo(350);
            release.join();
        } finally {
            if (!GraphRunner.awaitRefresh(key, 0)) GraphRunner.releaseSlot(key);
        }
    }

    /** The refresh in flight on {@code key} ends {@code ms} from now. */
    private static Thread releaseAfter(Path key, long ms) {
        return Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            GraphRunner.releaseSlot(key);
        });
    }
}
