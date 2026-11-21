// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.journal.JobDelta;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.layout.AnchorWatch;
import cc.jumpkick.layout.GraphLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two live sessions editing the same code, or code that meets at an anchor: the modules each works
 * in, and the one line each verdict carries.
 */
class SessionOverlapTest {

    private static final long T = 1_700_000_000_000L;
    private static final String CLAUDE = "claude-code 3f9a";
    private static final String CODEX = "codex 91be";
    private static final String PRICING = "core/src/main/java/dev/hotel/Pricing.java";
    private static final String QUOTE_TEST = "core/src/test/java/dev/hotel/QuoteTest.java";
    private static final String ROUTES = "web/src/main/java/dev/hotel/Routes.java";
    private static final String QUOTE = "core/src/main/java/dev/hotel/Quote.java";
    private static final String PROPERTIES = "core/src/main/resources/application.properties";

    private final AtomicLong now = new AtomicLong(T);

    /** Two worktrees of one project, and an unrelated one. */
    private final SessionLog log = new SessionLog(
            null,
            now::get,
            EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
            SessionExpiry.DEFAULT.toMillis(),
            new ProjectIdMemo(dir -> dir.startsWith("/wt/") ? "hotel" : "other"));

    @Test
    void each_of_two_sessions_editing_one_type_hears_of_it_once_in_its_next_verdict() {
        String claude = finish(CLAUDE, "/wt/a", T, PRICING, ROUTES);
        assertThat(log.overlap(claude)).as("nobody else edits Pricing yet").isNull();

        String codex = finish(CODEX, "/wt/b", T + 1_000, PRICING, QUOTE_TEST);
        assertThat(log.overlap(codex)).isEqualTo("W claude-code 3f9a is also editing Pricing");
        assertThat(log.overlap(codex)).as("told once").isNull();

        assertThat(log.overlap(claude))
                .as("the other session hears of it in its next verdict")
                .isEqualTo("W codex 91be is also editing Pricing");
        assertThat(log.overlap(claude)).isNull();

        finish(CLAUDE, "/wt/a", T + 2_000, QUOTE_TEST);
        assertThat(log.overlap(claude))
                .as("a newly shared file is news")
                .isEqualTo("W codex 91be is also editing QuoteTest");
    }

    @Test
    void no_line_when_the_files_differ_or_the_project_does() {
        String claude = finish(CLAUDE, "/wt/a", T, PRICING);
        String codex = finish(CODEX, "/wt/b", T + 1_000, ROUTES);
        String other = finish("cursor 77aa", "/elsewhere", T + 2_000, PRICING);

        assertThat(log.overlap(claude)).isNull();
        assertThat(log.overlap(codex)).isNull();
        assertThat(log.overlap(other))
                .as("another project's Pricing is not this one's")
                .isNull();
        assertThat(log.overlap(null)).isNull();
    }

    @Test
    void two_files_that_meet_at_an_anchor_one_of_them_defines_are_an_overlap(@TempDir Path tmp) throws IOException {
        Path a = checkout(tmp.resolve("hotel-a"));
        Path b = checkout(tmp.resolve("hotel-b"));
        SessionLog log = hotel();
        String claude = finish(log, CLAUDE, a.toString(), T, PROPERTIES);
        String codex = finish(log, CODEX, b.toString(), T + 1_000, PRICING);

        assertThat(log.overlap(codex))
                .as("the properties file defines the key Pricing reads, in another worktree")
                .isEqualTo("W claude-code 3f9a is also editing config:hotel.rate.weekend");
        assertThat(log.overlap(codex)).as("told once").isNull();
        assertThat(log.overlap(claude)).isEqualTo("W codex 91be is also editing config:hotel.rate.weekend");

        finish(log, CLAUDE, a.toString(), T + 2_000, PRICING);
        assertThat(log.overlap(codex))
                .as("a file both edit is named as itself, its anchors not again")
                .isEqualTo("W claude-code 3f9a is also editing Pricing");
    }

    @Test
    void two_files_that_only_read_one_key_do_not_meet(@TempDir Path tmp) throws IOException {
        Path a = checkout(tmp.resolve("hotel-a"));
        SessionLog log = hotel();
        String claude = finish(log, CLAUDE, a.toString(), T, PRICING);
        String codex = finish(log, CODEX, a.toString(), T + 1_000, QUOTE);

        assertThat(log.overlap(claude)).isNull();
        assertThat(log.overlap(codex)).isNull();
    }

    @Test
    void a_session_that_ended_overlaps_nobody() {
        String claude = finish(CLAUDE, "/wt/a", T, PRICING);
        String codex = finish(CODEX, "/wt/b", T + 1_000, PRICING);
        log.end("mcp", CODEX);

        assertThat(log.overlap(claude)).isNull();
        assertThat(log.overlap(codex)).isNull();
    }

    @Test
    void an_episode_names_the_modules_its_runs_edited() {
        String claude = finish(CLAUDE, "/wt/a", T, PRICING, "README.md");
        finish(CLAUDE, "/wt/a", T + 1_000, ROUTES, QUOTE_TEST, "src/main/java/Root.java");

        Map<String, Object> row = log.sessions(10).getFirst();
        assertThat(row.get("current")).isInstanceOfSatisfying(Map.class, ep -> assertThat(ep.get("modules"))
                .as("README.md is in no module; the project's own sources are named after it")
                .isEqualTo(List.of("core", "web", "a")));
        assertThat(claude).endsWith(".1");
    }

    @Test
    void a_file_row_reads_without_its_mark_and_a_file_as_its_type() {
        assertThat(JobDelta.path("core/src/main/java/A.java (new)")).isEqualTo("core/src/main/java/A.java");
        assertThat(JobDelta.path("db/V1.sql (gone)")).isEqualTo("db/V1.sql");
        assertThat(EditedFiles.symbol(PRICING)).isEqualTo("Pricing");
        assertThat(EditedFiles.symbol("core/src/main/resources/application.yml"))
                .isEqualTo("application.yml");
        assertThat(EditedFiles.moduleDir("server/engine/src/main/java/X.java")).isEqualTo("server/engine");
        assertThat(EditedFiles.moduleDir("docs/README.md")).isNull();
    }

    @Test
    void a_long_list_of_types_is_cut_to_one_line() {
        List<String> names = List.of(
                "PricingRules", "QuoteService", "WeekendUplift", "RoundingPolicy", "SeasonCalendar", "TaxTable");
        String line = Overlaps.fit("W claude-code 3f9a is also editing ", names);
        assertThat(line).hasSizeLessThanOrEqualTo(Overlaps.WIDTH).endsWith(" more)");
    }

    /** A log over checkouts whose directory names {@code hotel} as one project. */
    private SessionLog hotel() {
        return new SessionLog(
                null,
                now::get,
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                SessionExpiry.DEFAULT.toMillis(),
                new ProjectIdMemo(dir -> dir.contains("hotel") ? "hotel" : "other"));
    }

    /**
     * A checkout whose graph lists the anchors: the properties file defines the weekend rate,
     * Pricing and Quote read it.
     */
    private static Path checkout(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("jk.toml"), "[project]\nname = \"hotel\"\n");
        String key = "config:hotel.rate.weekend";
        Map<String, Map<String, Boolean>> anchors =
                Map.of(PROPERTIES, Map.of(key, true), PRICING, Map.of(key, false), QUOTE, Map.of(key, false));
        List<AnchorWatch.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Boolean>> e : anchors.entrySet()) {
            Path file = root.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getKey());
            entries.add(
                    new AnchorWatch.Entry(Hashing.sha256Hex(file), StatIdentity.of(file), e.getKey(), e.getValue()));
        }
        AnchorWatch.write(GraphLayout.dir(root), entries);
        return root;
    }

    private String finish(String label, String dir, long at, String... edited) {
        return finish(log, label, dir, at, edited);
    }

    /** Place and finish one green run of {@code label}'s session in {@code dir} that saw {@code edited} changed. */
    private String finish(SessionLog log, String label, String dir, long at, String... edited) {
        now.set(at);
        String episode = Objects.requireNonNull(log.placeRun("mcp", label, "test", dir, at));
        log.runFinished(new FinishedRun(
                episode, "test", dir, 1, at, at + 500, 500, true, false, "core", List.of(), null, List.of(edited)));
        return episode;
    }
}
