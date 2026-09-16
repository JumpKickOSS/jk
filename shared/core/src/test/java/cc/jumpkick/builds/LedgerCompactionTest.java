// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The project ledger is bounded by the project's shape: runs from several checkouts of one project
 * fold into one row set, the row families are capped, and reading a large ledger is a few
 * megabytes of maps rather than a parse tree.
 */
class LedgerCompactionTest {

    private static final int MODULES = 40;
    private static final int CLASSES_PER_MODULE = 35;
    private static final String[] TASKS = {
        "parse-build", "resolve-deps", "compile-java", "copy-resources", "compile-test", "run-tests",
        "package-jar", "guard", "write-stamp", "generate-sources", "compile-kotlin", "javadoc"
    };

    @Test
    void runs_from_three_checkouts_of_one_project_fold_into_one_row_set(@TempDir Path builds) throws Exception {
        Path home = builds.resolve("projects").resolve("demo");
        for (int run = 1; run <= 3; run++) {
            Path dir = Files.createDirectories(home.resolve(ProjectBuilds.RUNS).resolve(Integer.toString(run)));
            Files.writeString(dir.resolve(ProjectBuilds.METRICS), runMetrics(run * 100), StandardCharsets.UTF_8);
        }
        MetricsHarvest.get().runOnce(builds);

        String ledger = Files.readString(home.resolve(ProjectBuilds.PROJECT_METRICS), StandardCharsets.UTF_8);
        List<String> meanRows = section(ledger, "mean");
        assertThat(meanRows).hasSize(MODULES * (TASKS.length + CLASSES_PER_MODULE) + 3);
        assertThat(meanRows.stream().filter(l -> l.startsWith("module.server/m1.task.run-tests.wall-ms")))
                .as("one row per module task, however many checkouts ran it")
                .hasSize(1);
        assertThat(section(ledger, "count"))
                .contains("module.server/m1.task.run-tests.wall-ms = 3")
                .noneMatch(l -> l.contains(".test-class."));
        assertThat(section(ledger, "last"))
                .as("per-class walls keep mean and last")
                .anyMatch(l -> l.startsWith("module.server/m1.test-class.com.example.m1.T0Test.wall-ms"));
        assertThat(ledger).doesNotContain("/wt/");
        assertThat(Files.size(home.resolve(ProjectBuilds.PROJECT_METRICS)))
                .as("a forty-module, fourteen-hundred-class project ledger")
                .isLessThan(300 * 1024);
    }

    @Test
    void reading_the_ledger_for_a_checkout_expands_rows_to_that_checkout_and_stays_cheap(@TempDir Path builds)
            throws Exception {
        Path home = builds.resolve("projects").resolve("demo");
        Path dir = Files.createDirectories(home.resolve(ProjectBuilds.RUNS).resolve("1"));
        Files.writeString(dir.resolve(ProjectBuilds.METRICS), runMetrics(100), StandardCharsets.UTF_8);
        MetricsHarvest.get().runOnce(builds);
        Path checkout = Files.createDirectories(builds.resolve("wt").resolve("c"));
        Files.writeString(checkout.resolve("jk.toml"), "id = \"demo\"\n");

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        AggregatedMetrics.load(builds, null, checkout); // classes and regexes warm
        long before = threads.getCurrentThreadAllocatedBytes();
        AggregatedMetrics agg = AggregatedMetrics.load(builds, null, checkout);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(allocated).as("bytes allocated reading the ledger").isLessThan(10L << 20);
        assertThat(agg.taskWallMs(checkout.resolve("server/m1").toString(), "run-tests"))
                .hasValue(100 + 5);
        assertThat(agg.testClassWallMs(checkout.resolve("server/m1").toString(), "com.example.m1.T0Test"))
                .hasValue(100);
        assertThat(agg.taskWallMs(checkout.toString(), "guard")).hasValue(100 + 7);
        assertThat(agg.fresh()).isTrue();
    }

    @Test
    void row_families_are_capped_keeping_the_best_sampled_rows() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Double> last = new LinkedHashMap<>();
        for (int i = 0; i < MetricsHarvest.MAX_TEST_CLASS_ROWS + 50; i++) {
            String key = "module.m.test-class.com.example.C" + String.format(Locale.ROOT, "%05d", i) + "Test.wall-ms";
            counts.put(key, i < 50 ? 1L : 9L);
            last.put(key, 1.0);
        }
        counts.put("task.compile-java.wall-ms", 4L);
        List<String> kept = MetricsHarvest.keptRows(Map.of(), last, counts);
        assertThat(kept).hasSize(MetricsHarvest.MAX_TEST_CLASS_ROWS + 1);
        assertThat(kept).contains("task.compile-java.wall-ms");
        assertThat(kept).doesNotContain("module.m.test-class.com.example.C00000Test.wall-ms");
        assertThat(kept).contains("module.m.test-class.com.example.C00050Test.wall-ms");
    }

    /** What the journal writes for one run of a checkout: root tasks, module tasks, class walls. */
    private static String runMetrics(int base) {
        StringBuilder sb = new StringBuilder("# run metrics\nworkspace.wall-ms = 9000\n");
        sb.append("invocation.build.wall-ms = 9000\n");
        sb.append("module._.task.guard.wall-ms = ").append(base + 7).append('\n');
        for (int m = 1; m <= MODULES; m++) {
            String mod = "server/m" + m;
            for (int t = 0; t < TASKS.length; t++) {
                sb.append("module.")
                        .append(mod)
                        .append(".task.")
                        .append(TASKS[t])
                        .append(".wall-ms = ")
                        .append(base + t)
                        .append('\n');
            }
            for (int c = 0; c < CLASSES_PER_MODULE; c++) {
                sb.append("module.")
                        .append(mod)
                        .append(".test-class.com.example.m")
                        .append(m)
                        .append(".T")
                        .append(c)
                        .append("Test.wall-ms = ")
                        .append(base)
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static List<String> section(String ledger, String name) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : ledger.split("\n")) {
            if (line.startsWith("[")) {
                in = line.equals("[" + name + "]");
                continue;
            }
            if (in && !line.isBlank()) out.add(line);
        }
        return out;
    }
}
