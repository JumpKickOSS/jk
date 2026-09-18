// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.JUnitUniqueIds;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Thread-safe per-launch accumulator of JUnit method results. After workers join, {@link
 * #publish} folds the entries into the {@link RunResults} sink of the request that ran the launch,
 * keyed by module path; the journal drains that sink into {@code jk-results.md} when the request's
 * record is written. Companion to {@link XmlTestReport} (JUnit XML under {@code
 * target/reports/test-results/}).
 *
 * <p>A failure's message and stack are clipped at {@link #MAX_MESSAGE_CHARS} and {@link
 * #MAX_STACK_CHARS} — the report shows a few dozen lines and the cause chain, which the clip keeps
 * — so a suite whose every failure carries the same forty-kilobyte trace costs kilobytes, not
 * megabytes. A publish with no request open is dropped: nothing would drain it.
 */
public final class MarkdownTestReport {

    /** Characters of a failure message kept; the report renders one line of it. */
    static final int MAX_MESSAGE_CHARS = 4_096;

    /** Stack lines kept from the top of a failure trace before the clip keeps only its cause headers. */
    static final int MAX_STACK_LINES = 64;

    /** Characters of a failure stack kept after the line clip. */
    static final int MAX_STACK_CHARS = 16_384;

    private static final String CAUSED_BY = "Caused by: ";

    public record Entry(
            String className,
            String displayName,
            long durationMs,
            @Nullable String failureMessage,
            @Nullable String failureStack,
            @Nullable String skipReason) {
        public boolean isFail() {
            return failureMessage != null || failureStack != null;
        }

        public boolean isSkip() {
            return skipReason != null;
        }

        public boolean isPass() {
            return !isFail() && !isSkip();
        }
    }

    /** One module's tests, published after that module's workers join. */
    public record ModuleRun(String scopeKey, String label, List<Entry> entries) {
        public ModuleRun {
            entries = entries == null ? List.of() : List.copyOf(entries);
            if (scopeKey == null) scopeKey = "";
            if (label == null) label = "";
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Record a finished test (passed, failed, or aborted). {@code throwableJson} is the raw nested
     * JSON object from the protocol event's {@code throwable} field — {@code null} for a passing
     * test.
     */
    public synchronized void recordFinished(
            String uniqueId, String display, long durationMs, @Nullable String throwableJson) {
        String className = classNameFrom(uniqueId);
        String failureMessage = null, failureStack = null;
        if (throwableJson != null) {
            failureMessage = Jsonl.str(throwableJson, "message");
            failureStack = Jsonl.str(throwableJson, "stack");
            if ((failureMessage == null || failureMessage.isBlank()) && failureStack == null) {
                failureMessage = Jsonl.str(throwableJson, "class");
            }
        }
        entries.add(new Entry(
                className,
                display,
                durationMs,
                clip(failureMessage, MAX_MESSAGE_CHARS),
                boundedStack(failureStack),
                null));
    }

    /** {@code text} cut at {@code max} characters with an ellipsis; {@code null} stays {@code null}. */
    static @Nullable String clip(@Nullable String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }

    /**
     * The first {@link #MAX_STACK_LINES} lines of {@code stack}, then every {@code Caused by:}
     * header past them so the innermost cause survives, the whole cut at {@link #MAX_STACK_CHARS}.
     */
    static @Nullable String boundedStack(@Nullable String stack) {
        if (stack == null || stack.length() <= MAX_STACK_CHARS) {
            return stack;
        }
        String[] lines = stack.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int kept = Math.min(lines.length, MAX_STACK_LINES);
        for (int i = 0; i < kept; i++) sb.append(lines[i]).append('\n');
        int elided = 0;
        for (int i = kept; i < lines.length; i++) {
            if (lines[i].strip().startsWith(CAUSED_BY)) {
                if (elided > 0) sb.append("\t… ").append(elided).append(" lines\n");
                elided = 0;
                sb.append(lines[i]).append('\n');
            } else {
                elided++;
            }
        }
        if (elided > 0) sb.append("\t… ").append(elided).append(" lines\n");
        String out = sb.toString();
        return out.length() <= MAX_STACK_CHARS ? out : out.substring(0, MAX_STACK_CHARS) + "…";
    }

    /**
     * Record a skipped test. {@code reason} is the skip reason from the protocol event, may be null.
     */
    public synchronized void recordSkipped(String uniqueId, String display, @Nullable String reason) {
        String className = classNameFrom(uniqueId);
        entries.add(new Entry(className, display, 0, null, null, reason != null ? reason : ""));
    }

    /**
     * Fold this launch's entries into the request's sink under {@code scopeKey} (module path).
     * No-op when nothing was recorded. Concurrent launches of the same key merge.
     */
    public synchronized void publish(String scopeKey, String label) {
        publish(scopeKey, label, entries);
    }

    /**
     * Fold {@code entries} recorded elsewhere (a surefire report) into the request's sink under
     * {@code scopeKey}. Dropped when no request is open on this thread.
     */
    public static void publish(String scopeKey, String label, List<Entry> entries) {
        if (entries.isEmpty()) return;
        RunResults sink = RunResults.ambient();
        if (sink == null) return;
        String k = scopeKey == null || scopeKey.isBlank() ? "_" : scopeKey;
        sink.publishTests(k, label == null ? "" : label, List.copyOf(entries));
    }

    /**
     * FQCN from a JUnit Platform uniqueId via the shared {@link JUnitUniqueIds} walk; the raw id
     * when the {@code [class:…]} segment is absent.
     */
    static String classNameFrom(String uniqueId) {
        String cls = JUnitUniqueIds.classOf(uniqueId);
        return cls.isEmpty() ? uniqueId : cls;
    }
}
