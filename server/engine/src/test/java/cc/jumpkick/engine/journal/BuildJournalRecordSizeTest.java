// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Readers of a record's head never load its tail, and a record's diagnostics stay within budget. */
class BuildJournalRecordSizeTest {

    @TempDir
    Path dir;

    private static BuildRecord record(long requestId, List<BuildRecord.Module> modules, List<BuildRecord.Diag> diags) {
        return new BuildRecord(
                null,
                0L,
                BuildRecord.SCHEMA,
                "build",
                "/proj",
                "g:a",
                "p-1",
                1_700_000_000_000L,
                1_700_000_001_000L,
                1_000,
                false,
                false,
                1,
                "9.9-test",
                null,
                modules,
                List.of(),
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                requestId,
                null,
                List.of());
    }

    private static List<BuildRecord.Diag> diags(int n) {
        List<BuildRecord.Diag> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new BuildRecord.Diag(
                    "error", "/proj", "compile", "javac", i + " " + "x".repeat(1_000), null, null));
        }
        return out;
    }

    private static List<String> messages(List<BuildRecord.Diag> diags) {
        return diags.stream().map(BuildRecord.Diag::message).toList();
    }

    private static Path recordFile(BuildJournal j, String locator) {
        return requireNonNull(j.runDir(locator).orElseThrow()).resolve(ProjectBuilds.RECORD);
    }

    /**
     * The tail is replaced by four MiB that do not parse: every head reader still answers, so none
     * of them parsed past the head, while a whole read of the record fails.
     */
    @Test
    void head_readers_answer_from_a_record_whose_multi_mib_tail_does_not_parse() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        List<BuildRecord.Module> modules = List.of(
                new BuildRecord.Module("g:a", "/proj/a", true, 0, 10, List.of()),
                new BuildRecord.Module("g:b", "/proj/b", false, 1, 10, List.of()));
        String locator = requireNonNull(j.append(record(9L, modules, List.of()), BuildJournal.Snapshot.NONE));
        Path file = recordFile(j, locator);
        String json = Files.readString(file, StandardCharsets.UTF_8);
        int tail = Json.tailStart(json);
        assertThat(tail).isPositive();
        Files.writeString(
                file,
                json.substring(0, tail) + ",\n  \"modules\": [" + "x".repeat(4 * 1024 * 1024),
                StandardCharsets.UTF_8);
        assertThat(Files.size(file)).isGreaterThan(4L * 1024 * 1024);

        BuildJournal cold = new BuildJournal(dir);
        BuildJournal.Head head = cold.heads(1).getFirst();
        assertThat(head.record().requestId()).isEqualTo(9L);
        assertThat(head.record().projectId()).isEqualTo("p-1");
        assertThat(head.record().modules()).isEmpty();
        assertThat(head.moduleCount()).isEqualTo(2);
        assertThat(head.failedModules()).isEqualTo(1);
        String id = requireNonNull(head.record().id());
        assertThat(cold.list()).extracting(BuildRecord::id).containsExactly(id);
        assertThat(cold.runDir(id)).contains(file.getParent());

        List<Integer> seen = new ArrayList<>();
        cold.rawRecords(5, raw -> {
            seen.add(raw.length());
            return false;
        });
        assertThat(seen).singleElement().satisfies(n -> assertThat(n).isLessThan(ProjectBuilds.RECORD_HEAD_BYTES));

        assertThat(cold.get(id)).as("a whole read parses the tail").isEmpty();
    }

    @Test
    void diagnostics_past_the_budget_spill_beside_the_record_and_a_whole_read_finds_them() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        List<BuildRecord.Diag> all = diags(4_000);
        String locator = requireNonNull(j.append(record(3L, List.of(), all), BuildJournal.Snapshot.NONE));
        Path file = recordFile(j, locator);
        Path spill = requireNonNull(file.getParent()).resolve(BuildJournal.DIAGNOSTICS_SPILL);

        assertThat(Files.size(file)).isLessThan(BuildJournal.DIAGNOSTICS_BUDGET + 64 * 1024);
        assertThat(spill).isRegularFile();
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(BuildJournal.scanString(json, "diagnosticsSpill")).isEqualTo(BuildJournal.DIAGNOSTICS_SPILL);

        BuildRecord whole = j.get(locator).orElseThrow();
        assertThat(messages(whole.diagnostics())).isEqualTo(messages(all));
        assertThat(j.get(requireNonNull(whole.id())).orElseThrow().diagnostics())
                .hasSize(all.size());
    }

    @Test
    void every_raw_read_of_a_spilled_record_carries_every_diagnostic() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        List<BuildRecord.Diag> all = diags(4_000);
        String locator = requireNonNull(j.append(record(5L, List.of(), all), BuildJournal.Snapshot.NONE));
        String id = requireNonNull(j.get(locator).orElseThrow().id());
        assertThat(requireNonNull(recordFile(j, locator).getParent()).resolve(BuildJournal.DIAGNOSTICS_SPILL))
                .isRegularFile();

        List<String> raws = new ArrayList<>(j.rawRecords(5));
        raws.add(j.rawFinishedRecordByRequestId(5L).orElseThrow());
        raws.add(j.rawRecord(id).orElseThrow());
        raws.add(j.rawRecord(locator).orElseThrow());
        assertThat(raws).hasSize(4).allSatisfy(raw -> {
            assertThat(BuildJournal.scanString(raw, "diagnosticsSpill")).isNull();
            assertThat(messages(Json.read(raw).diagnostics())).isEqualTo(messages(all));
        });
    }

    @Test
    void a_raw_read_of_an_unspilled_record_is_its_file_as_written() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        String locator = requireNonNull(j.append(record(6L, List.of(), diags(3)), BuildJournal.Snapshot.NONE));
        String json =
                Files.readString(recordFile(j, locator), StandardCharsets.UTF_8).strip();

        assertThat(j.rawRecord(locator)).contains(json);
        assertThat(j.rawRecords(5)).containsExactly(json);
    }

    @Test
    void a_rewrite_within_budget_drops_the_spill_the_earlier_record_left() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        String locator = requireNonNull(j.append(record(4L, List.of(), diags(4_000)), BuildJournal.Snapshot.NONE));
        Path spill = requireNonNull(recordFile(j, locator).getParent()).resolve(BuildJournal.DIAGNOSTICS_SPILL);
        assertThat(spill).isRegularFile();

        List<BuildRecord.Diag> few = diags(3);
        assertThat(j.complete(locator, record(4L, List.of(), few), BuildJournal.Snapshot.NONE))
                .isTrue();

        assertThat(spill).doesNotExist();
        assertThat(messages(j.get(locator).orElseThrow().diagnostics())).isEqualTo(messages(few));
    }
}
