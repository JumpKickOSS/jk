// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A workspace's test classes as its quarantine sees them: every entry of {@value
 * TestQuarantine#FILE}, and every class with a history, each with that history. {@link #listed} are
 * the flaky and the quarantined.
 */
public record TestQuarantineReport(TestQuarantine.Policy policy, List<Row> rows) {

    public TestQuarantineReport {
        rows = List.copyOf(rows);
    }

    /**
     * One class of one module. {@code entry} is the quarantine entry that holds it, {@code null} for
     * a class that is only flaky; {@code history} is its outcomes, oldest first, empty when it has
     * none ({@link TestHistory}).
     */
    public record Row(String className, String module, TestQuarantine.@Nullable Entry entry, String history) {

        public boolean quarantined() {
            return entry != null;
        }

        /** Runs in the last {@code runs} of its history, since its last release, that passed on a re-run. */
        public int flakes(int runs) {
            return TestHistory.flakes(history, runs);
        }

        /** The runs of the last {@code runs} of its history, since its last release, that a count looks at. */
        public int counted(int runs) {
            return TestHistory.counted(history, runs).length();
        }
    }

    /** The report for the workspace at {@code root}, reading its histories under {@code stateDir}. */
    public static TestQuarantineReport of(Path stateDir, Path root) throws IOException {
        TestQuarantine.Policy policy = TestQuarantine.Policy.of(root);
        TestQuarantine quarantine = TestQuarantine.read(root);
        List<Row> rows = new ArrayList<>();
        List<TestQuarantine.Entry> shown = new ArrayList<>();
        for (TestHistory h : TestHistory.under(stateDir, root)) {
            String module = TestQuarantine.moduleKey(root, h.moduleDir());
            for (Map.Entry<String, String> c : h.classes().entrySet()) {
                TestQuarantine.Entry entry = quarantine.find(module, c.getKey());
                rows.add(new Row(c.getKey(), module, entry, c.getValue()));
                if (entry != null) shown.add(entry);
            }
        }
        for (TestQuarantine.Entry e : quarantine.entries()) {
            if (!shown.contains(e)) rows.add(new Row(e.className(), e.module(), e, ""));
        }
        rows.sort(Comparator.comparing(Row::module).thenComparing(Row::className));
        return new TestQuarantineReport(policy, rows);
    }

    /** The quarantined rows, and the others that passed on a re-run in the policy's window since their last release. */
    public List<Row> listed() {
        return rows.stream()
                .filter(r -> r.quarantined() || r.flakes(policy.runs()) > 0)
                .toList();
    }

    /** The rows of the class {@code name} names ({@link TestQuarantine#names}), flaky or not. */
    public List<Row> named(String name) {
        return rows.stream()
                .filter(r -> TestQuarantine.names(name, r.className()))
                .toList();
    }
}
