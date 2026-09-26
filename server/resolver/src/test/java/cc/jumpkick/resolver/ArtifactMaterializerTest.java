// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.DownloadSlots;
import cc.jumpkick.run.JkThreads;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The materialize fan-out is bounded: however many rows a lock has, only {@link
 * DownloadSlots#width()} of them are being assembled at once, and the rows still come back in
 * declaration order with one progress tick each.
 */
class ArtifactMaterializerTest {

    @Test
    void rows_in_flight_never_exceed_the_download_slots() throws Exception {
        int rows = DownloadSlots.width() * 4;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ArtifactMaterializer.RowAssembler assembler = (mod, tags, abort) -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                // Long enough for every submitted task to be running at once when nothing bounds them.
                Thread.sleep(20);
            } finally {
                inFlight.decrementAndGet();
            }
            return row(mod);
        };
        List<String> ticked = new CopyOnWriteArrayList<>();
        LockProgress progress = new LockProgress(
                new ResolveObserver() {
                    @Override
                    public void onTotal(int total) {}

                    @Override
                    public void onPackage(String module, String version) {
                        ticked.add(module);
                    }
                },
                (graphMs, graphPackages, materializeMs, materialized, totalMs) -> {});

        List<Map.Entry<String, Resolution.ResolvedModule>> ordered = new ArrayList<>();
        Map<String, EnumSet<Scope>> tags = new HashMap<>();
        for (int i = 0; i < rows; i++) {
            String key = "com.example:lib" + i + "@1.0";
            ordered.add(Map.entry(
                    key, new Resolution.ResolvedModule("com.example:lib" + i, "1.0", List.of(), Map.of(), List.of())));
            tags.put(key, EnumSet.of(Scope.MAIN));
        }

        List<Lockfile.Artifact> out = new ArtifactMaterializer(assembler, progress).materialize(ordered, tags);

        assertThat(peak.get())
                .as("rows being assembled at once")
                .isLessThanOrEqualTo(DownloadSlots.width())
                .isGreaterThan(1);
        assertThat(out).extracting(Lockfile.Artifact::name).containsExactly(names(rows));
        assertThat(ticked).hasSize(rows);
    }

    @Test
    void row_tasks_alive_at_once_never_exceed_the_download_slots() throws Exception {
        // Counted from submission until the thread unwinds. The window permit is returned first, so a
        // wave that finishes together overlaps the next one: two widths, not one task per row.
        int rows = DownloadSlots.width() * 4;
        AtomicInteger alive = new AtomicInteger();
        AtomicInteger peakAlive = new AtomicInteger();
        Executor counting = task -> {
            peakAlive.accumulateAndGet(alive.incrementAndGet(), Math::max);
            JkThreads.io().execute(() -> {
                try {
                    task.run();
                } finally {
                    alive.decrementAndGet();
                }
            });
        };
        ArtifactMaterializer.RowAssembler assembler = (mod, tags, abort) -> {
            Thread.sleep(10);
            return row(mod);
        };
        LockProgress progress = new LockProgress(
                new ResolveObserver() {
                    @Override
                    public void onTotal(int total) {}

                    @Override
                    public void onPackage(String module, String version) {}
                },
                (graphMs, graphPackages, materializeMs, materialized, totalMs) -> {});
        List<Map.Entry<String, Resolution.ResolvedModule>> ordered = new ArrayList<>();
        Map<String, EnumSet<Scope>> tags = new HashMap<>();
        for (int i = 0; i < rows; i++) {
            String key = "com.example:lib" + i + "@1.0";
            ordered.add(Map.entry(
                    key, new Resolution.ResolvedModule("com.example:lib" + i, "1.0", List.of(), Map.of(), List.of())));
            tags.put(key, EnumSet.of(Scope.MAIN));
        }

        List<Lockfile.Artifact> out =
                new ArtifactMaterializer(assembler, progress, counting).materialize(ordered, tags);

        assertThat(peakAlive.get())
                .as("a finishing wave releases its permits before those threads unwind, so the next"
                        + " wave can be submitted while the previous one is still counted")
                .isLessThanOrEqualTo(2 * DownloadSlots.width())
                .isGreaterThan(1);
        assertThat(out).extracting(Lockfile.Artifact::name).containsExactly(names(rows));
    }

    private static String[] names(int rows) {
        String[] names = new String[rows];
        for (int i = 0; i < rows; i++) names[i] = "com.example:lib" + i;
        return names;
    }

    private static Lockfile.Artifact row(Resolution.ResolvedModule mod) {
        return new Lockfile.Artifact(
                mod.module(),
                mod.version(),
                "test+http://127.0.0.1/",
                null,
                null,
                List.of(Scope.MAIN),
                List.of(),
                null);
    }
}
