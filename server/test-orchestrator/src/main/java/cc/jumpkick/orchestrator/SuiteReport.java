// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link ResultAggregator} hands each test it has read: the report files a suite writes. A
 * report keeps what it is given and judges nothing; the verdict stays with the aggregator.
 */
public interface SuiteReport {

    /** A report that keeps nothing. */
    SuiteReport NONE = new SuiteReport() {};

    /** One line the test JVM printed while {@code className} was running. */
    default void output(String className, String line) {}

    /**
     * A test finished (passed, failed or aborted): {@code xmlName} is its display name, {@code label}
     * its method or short name, {@code throwableJson} the event's raw {@code throwable} object,
     * {@code null} for a pass.
     */
    default void finished(
            String uniqueId, String xmlName, String label, long durationMs, @Nullable String throwableJson) {}

    /** A test was skipped or aborted, for {@code reason}, which may be {@code null}. */
    default void skipped(String uniqueId, String xmlName, String label, @Nullable String reason) {}

    /** A test of {@code className} failed, known by each of {@code names}, with its raw {@code throwable}. */
    default void failed(String className, List<String> names, String throwableJson) {}

    /** Every non-null report of {@code reports}, each told in turn. */
    static SuiteReport all(@Nullable SuiteReport... reports) {
        List<SuiteReport> kept = new ArrayList<>();
        for (SuiteReport r : reports) if (r != null) kept.add(r);
        if (kept.isEmpty()) return NONE;
        if (kept.size() == 1) return kept.get(0);
        return new SuiteReport() {
            @Override
            public void output(String className, String line) {
                for (SuiteReport r : kept) r.output(className, line);
            }

            @Override
            public void finished(
                    String uniqueId, String xmlName, String label, long durationMs, @Nullable String throwableJson) {
                for (SuiteReport r : kept) r.finished(uniqueId, xmlName, label, durationMs, throwableJson);
            }

            @Override
            public void skipped(String uniqueId, String xmlName, String label, @Nullable String reason) {
                for (SuiteReport r : kept) r.skipped(uniqueId, xmlName, label, reason);
            }

            @Override
            public void failed(String className, List<String> names, String throwableJson) {
                for (SuiteReport r : kept) r.failed(className, names, throwableJson);
            }
        };
    }
}
