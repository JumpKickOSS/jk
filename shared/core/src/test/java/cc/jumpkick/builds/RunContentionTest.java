// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run's window and checkout are read from the head of its record, never the whole file. */
class RunContentionTest {

    /** A record whose scalar fields are followed by module rows well past the head the readers load. */
    private static Path largeRecord(Path run) throws Exception {
        StringBuilder json =
                new StringBuilder("{\n  \"schema\": 1,\n  \"kind\": \"test\",\n  \"dir\": \"/home/me/app\",\n"
                        + "  \"startedAt\": 1000,\n  \"finishedAt\": 6000,\n  \"modules\": [\n");
        while (json.length() < 4 * ProjectBuilds.RECORD_HEAD_BYTES) {
            json.append(
                    "    {\"coord\": \"com.acme:module\", \"dir\": \"/home/me/app/module\", \"startedAt\": 2000, \"finishedAt\": 3000},\n");
        }
        json.append("    {}\n  ]\n}\n");
        Files.createDirectories(run);
        Path record = run.resolve(ProjectBuilds.RECORD);
        Files.writeString(record, json);
        return record;
    }

    @Test
    void the_window_and_the_checkout_come_from_the_head_of_a_large_record(@TempDir Path tmp) throws Exception {
        Path run = tmp.resolve("runs").resolve("7");
        Path record = largeRecord(run);
        assertThat(Files.size(record)).isGreaterThan(4L * ProjectBuilds.RECORD_HEAD_BYTES);

        assertThat(RunContention.window(run)).containsExactly(1000L, 6000L);
        assertThat(ProjectBuilds.runCheckout(run)).isEqualTo(Path.of("/home/me/app"));
        assertThat(ProjectBuilds.recordHead(record)).hasSize(ProjectBuilds.RECORD_HEAD_BYTES);
    }

    @Test
    void a_record_without_a_window_or_with_an_empty_one_has_none(@TempDir Path tmp) throws Exception {
        Path run = Files.createDirectories(tmp.resolve("runs").resolve("1"));
        assertThat(RunContention.window(run)).as("no record").isNull();
        Files.writeString(run.resolve(ProjectBuilds.RECORD), "{\n  \"schema\": 1,\n  \"startedAt\": 1000\n}\n");
        assertThat(RunContention.window(run)).as("no finish").isNull();
        Files.writeString(run.resolve(ProjectBuilds.RECORD), "{\"startedAt\": 5000, \"finishedAt\": 5000}");
        assertThat(RunContention.window(run)).as("an empty window").isNull();
    }

    @Test
    void the_sorted_sweep_counts_what_comparing_every_pair_counts() {
        Random random = new Random(7);
        for (int round = 0; round < 50; round++) {
            Map<Path, long[]> windows = new LinkedHashMap<>();
            for (int i = 0; i < 40; i++) {
                long start = random.nextInt(1_000);
                windows.put(Path.of("run" + i), new long[] {start, start + 1 + random.nextInt(200)});
            }
            for (var a : windows.entrySet()) {
                int expected = 0;
                for (var b : windows.entrySet()) {
                    if (a != b && a.getValue()[0] < b.getValue()[1] && b.getValue()[0] < a.getValue()[1]) expected++;
                }
                assertThat(RunContention.overlaps(windows).get(a.getKey())).isEqualTo(expected);
            }
        }
    }
}
