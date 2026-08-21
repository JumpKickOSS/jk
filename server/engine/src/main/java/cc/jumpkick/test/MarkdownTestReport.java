// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.jsonl.Jsonl;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-launch accumulator of JUnit method results. After workers join, {@link
 * #publish} folds the entries into a process-wide store keyed by module path; the journal drains
 * that store into {@code jk-results.md}. Companion to {@link XmlTestReport} (JUnit XML under
 * {@code target/reports/test-results/}).
 */
public final class MarkdownTestReport {

    public record Entry(
            String className,
            String displayName,
            long durationMs,
            String failureMessage,
            String failureStack,
            String skipReason) {
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

    private static final ConcurrentHashMap<String, ModuleRun> PUBLISHED = new ConcurrentHashMap<>();

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Record a finished test (passed, failed, or aborted). {@code throwableJson} is the raw nested
     * JSON object from the protocol event's {@code throwable} field — {@code null} for a passing
     * test.
     */
    public synchronized void recordFinished(String uniqueId, String display, long durationMs, String throwableJson) {
        String className = classNameFrom(uniqueId);
        String failureMessage = null, failureStack = null;
        if (throwableJson != null) {
            failureMessage = Jsonl.str(throwableJson, "message");
            failureStack = Jsonl.str(throwableJson, "stack");
            if ((failureMessage == null || failureMessage.isBlank()) && failureStack == null) {
                failureMessage = Jsonl.str(throwableJson, "class");
            }
        }
        entries.add(new Entry(className, display, durationMs, failureMessage, failureStack, null));
    }

    /**
     * Record a skipped test. {@code reason} is the skip reason from the protocol event, may be null.
     */
    public synchronized void recordSkipped(String uniqueId, String display, String reason) {
        String className = classNameFrom(uniqueId);
        entries.add(new Entry(className, display, 0, null, null, reason != null ? reason : ""));
    }

    /**
     * Fold this launch's entries into the process-wide store under {@code scopeKey} (module path).
     * No-op when nothing was recorded. Concurrent launches of the same key merge.
     */
    public synchronized void publish(String scopeKey, String label) {
        if (entries.isEmpty()) return;
        String k = scopeKey == null || scopeKey.isBlank() ? "_" : scopeKey;
        String lab = label == null ? "" : label;
        ModuleRun add = new ModuleRun(k, lab, List.copyOf(entries));
        PUBLISHED.merge(k, add, (a, b) -> {
            List<Entry> merged =
                    new ArrayList<>(a.entries().size() + b.entries().size());
            merged.addAll(a.entries());
            merged.addAll(b.entries());
            String keep = !a.label().isBlank() ? a.label() : b.label();
            return new ModuleRun(k, keep, merged);
        });
    }

    /**
     * Drain every published run whose scope is {@code projectDir} or a path under it. Used at
     * journal-write so a concurrent build of a different checkout is not stolen.
     */
    public static List<ModuleRun> takeUnder(Path projectDir) {
        if (projectDir == null) return List.of();
        Path root;
        try {
            root = projectDir.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ModuleRun> out = new ArrayList<>();
        for (String key : List.copyOf(PUBLISHED.keySet())) {
            Path p;
            try {
                p = Path.of(key).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue;
            }
            if (!p.equals(root) && !p.startsWith(root)) continue;
            ModuleRun run = PUBLISHED.remove(key);
            if (run != null && !run.entries().isEmpty()) out.add(run);
        }
        return out;
    }

    /** Extract the FQCN from a JUnit Platform uniqueId. */
    static String classNameFrom(String uniqueId) {
        int s = uniqueId.indexOf("[class:");
        if (s < 0) return uniqueId;
        int e = uniqueId.indexOf(']', s);
        if (e < 0) return uniqueId;
        return uniqueId.substring(s + 7, e);
    }
}
