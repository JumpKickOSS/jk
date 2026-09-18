// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.audit;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.BuildBlock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Aggregated {@code jk audit} findings from a {@link Lockfile} and OSV responses. */
public final class AuditReport {

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        /** No label the feed gave us could be classified. Treated as gating — see {@link #atLeast}. */
        UNKNOWN;

        /**
         * Classify a feed-supplied severity <em>label</em>.
         *
         * <p>Only a label is accepted. A CVSS vector ({@code CVSS:3.1/AV:N/...}) carries no label, so
         * it maps to {@link #UNKNOWN} rather than being pattern-matched: substring matching over a
         * vector is how every advisory silently became {@code UNKNOWN}. {@code MODERATE} is the
         * GitHub spelling of {@link #MEDIUM} and is accepted as an alias.
         */
        public static Severity parse(@Nullable String raw) {
            if (raw == null) return UNKNOWN;
            String token = raw.trim().toUpperCase(Locale.ROOT);
            return switch (token) {
                case "CRITICAL" -> CRITICAL;
                case "HIGH" -> HIGH;
                case "MEDIUM", "MODERATE" -> MEDIUM;
                case "LOW" -> LOW;
                default -> UNKNOWN;
            };
        }

        /**
         * True if this severity is at least as severe as {@code threshold}.
         *
         * <p>{@link #UNKNOWN} <strong>fails closed</strong>: an advisory we could not classify is
         * reported at every threshold rather than silently dropped. A security tool that hides what
         * it does not understand is worse than one that errs loudly.
         */
        public boolean atLeast(Severity threshold) {
            if (this == UNKNOWN) return true;
            return ordinal() <= threshold.ordinal();
        }
    }

    /**
     * The {@code [audit] ignore} entry that names a finding, as it stands on the day of the audit.
     * {@code expired} is true once that day is past {@code until}; an expired entry ignores nothing
     * and only records why the finding was accepted.
     */
    public record Ignore(String reason, @Nullable LocalDate until, boolean expired) {
        public Ignore {
            Objects.requireNonNull(reason, "reason");
        }

        /** {@code entry} judged on {@code today}. */
        public static Ignore of(BuildBlock.AuditIgnore entry, LocalDate today) {
            return new Ignore(entry.reason(), entry.until(), entry.expiredOn(today));
        }
    }

    /**
     * One advisory against one locked package. {@code fixedIn} is the first version above the
     * locked one that OSV lists as fixed, or {@code null} when OSV names none; {@code ignore} is the
     * manifest entry naming this advisory, or {@code null} when there is none.
     */
    public record Finding(
            String module,
            String version,
            String vulnId,
            String summary,
            Severity severity,
            @Nullable String fixedIn,
            @Nullable Ignore ignore) {
        public Finding {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(vulnId, "vulnId");
            Objects.requireNonNull(severity, "severity");
            if (summary == null) summary = "";
            if (fixedIn != null && fixedIn.isBlank()) fixedIn = null;
        }

        /** A finding as the worker reports it: nothing in the manifest has judged it yet. */
        public Finding(
                String module,
                String version,
                String vulnId,
                String summary,
                Severity severity,
                @Nullable String fixedIn) {
            this(module, version, vulnId, summary, severity, fixedIn, null);
        }

        /** True when an unexpired {@code [audit] ignore} entry covers this finding. */
        public boolean ignored() {
            return ignore != null && !ignore.expired();
        }

        /** True when the entry covering this finding has run past its {@code until} date. */
        public boolean ignoreExpired() {
            return ignore != null && ignore.expired();
        }

        public Finding withIgnore(@Nullable Ignore ignore) {
            return new Finding(module, version, vulnId, summary, severity, fixedIn, ignore);
        }

        /** This finding as {@code ignores} judges it on {@code today}; the first entry naming the advisory wins. */
        public Finding under(List<BuildBlock.AuditIgnore> ignores, LocalDate today) {
            for (BuildBlock.AuditIgnore entry : ignores) {
                if (entry.id().equalsIgnoreCase(vulnId)) return withIgnore(Ignore.of(entry, today));
            }
            return withIgnore(null);
        }
    }

    private final List<Finding> findings;

    public AuditReport(List<Finding> findings) {
        this.findings = List.copyOf(findings);
    }

    public List<Finding> findings() {
        return findings;
    }

    /** Findings at or above {@code threshold}, ignored or not. */
    public List<Finding> filterAtLeast(Severity threshold) {
        List<Finding> filtered = new ArrayList<>();
        for (Finding f : findings) {
            if (f.severity().atLeast(threshold)) filtered.add(f);
        }
        return filtered;
    }

    /** The findings that fail the gate: at or above {@code threshold} and not ignored. */
    public List<Finding> blocking(Severity threshold) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : filterAtLeast(threshold)) {
            if (!f.ignored()) out.add(f);
        }
        return out;
    }

    /** The findings an unexpired {@code [audit] ignore} entry covers. */
    public List<Finding> ignored() {
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.ignored()) out.add(f);
        }
        return out;
    }

    /** Count of findings grouped by severity, descending. */
    public Map<Severity, Integer> bySeverity() {
        Map<Severity, Integer> counts = new LinkedHashMap<>();
        for (Severity s : Severity.values()) counts.put(s, 0);
        for (Finding f : findings) {
            counts.merge(f.severity(), 1, Integer::sum);
        }
        return counts;
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    /** Render as Markdown for human consumption. */
    public String renderMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# jk audit\n\n");
        if (findings.isEmpty()) {
            sb.append("No known vulnerabilities in the locked dependencies.\n");
            return sb.toString();
        }
        sb.append(findings.size())
                .append(" finding")
                .append(findings.size() == 1 ? "" : "s")
                .append(" by severity: ");
        Map<Severity, Integer> counts = bySeverity();
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() == 0) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        int ignored = ignored().size();
        if (ignored > 0) sb.append(" (").append(ignored).append(" ignored)");
        sb.append("\n\n");
        for (Finding f : findings) {
            sb.append("- **")
                    .append(f.severity())
                    .append("** ")
                    .append(f.module())
                    .append(':')
                    .append(f.version())
                    .append(" — [")
                    .append(f.vulnId())
                    .append("](https://osv.dev/vulnerability/")
                    .append(f.vulnId())
                    .append(')');
            if (!f.summary().isEmpty()) {
                sb.append(" — ").append(f.summary());
            }
            if (f.fixedIn() != null) {
                sb.append(" — fixed in ").append(f.fixedIn());
            }
            Ignore ignore = f.ignore();
            if (ignore != null) {
                sb.append(ignore.expired() ? " — ignore expired" : " — ignored")
                        .append(" (")
                        .append(ignore.reason());
                if (ignore.until() != null) sb.append(", until ").append(ignore.until());
                sb.append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
