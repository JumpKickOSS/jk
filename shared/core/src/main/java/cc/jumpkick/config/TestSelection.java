// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.layout.TestSuites;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Which test suites and JUnit tags a {@code jk test} / build-with-tests run should use.
 *
 * <p>{@link #DEFAULT} runs only the {@code test} suite with no tag filters. {@link #gate} is the
 * share-the-commit bar: requested suites (default {@code test} + {@code integration}) with
 * {@code integration} optional when it is not on disk.
 */
public record TestSelection(
        /** Explicit suite names; empty means default suite only (unless {@link #allSuites}). */
        List<String> suites,
        /** When true, run every discovered suite (ignores {@link #suites} for selection). */
        boolean allSuites,
        List<String> includeTags,
        List<String> excludeTags,
        /**
         * When true, {@link #includeTags}/{@link #excludeTags} are final — some layer (module
         * {@code [test]} baseline, a profile key, or a CLI flag) explicitly resolved them, so the
         * engine must not fold per-module {@code [test]} tags back in even when both lists are
         * empty. An explicitly cleared filter ({@code exclude-tags = []} in a profile, or
         * {@code --exclude-tags ""}) is only representable through this flag.
         */
        boolean tagsResolved,
        /**
         * {@code --gate} / {@code --pre-merge}: {@link TestSuites#INTEGRATION} in {@link #suites}
         * is optional (skip if the directory is absent) instead of an unknown-suite error.
         */
        boolean gate,
        /** {@code --scripts-only}: run gate scripts, skip JUnit. */
        boolean scriptsOnly,
        /** {@code --no-scripts}: skip gate scripts even when {@link #gate} is set. */
        boolean noScripts) {

    public static final TestSelection DEFAULT =
            new TestSelection(List.of(), false, List.of(), List.of(), false, false, false, false);

    public TestSelection {
        suites = normalizeNames(suites);
        includeTags = normalizeNames(includeTags);
        excludeTags = normalizeNames(excludeTags);
    }

    public static TestSelection of(
            List<String> suites, boolean allSuites, List<String> includeTags, List<String> excludeTags) {
        return of(suites, allSuites, includeTags, excludeTags, false, false);
    }

    public static TestSelection of(
            List<String> suites,
            boolean allSuites,
            List<String> includeTags,
            List<String> excludeTags,
            boolean tagsResolved) {
        return of(suites, allSuites, includeTags, excludeTags, tagsResolved, false);
    }

    public static TestSelection of(
            List<String> suites,
            boolean allSuites,
            List<String> includeTags,
            List<String> excludeTags,
            boolean tagsResolved,
            boolean gate) {
        return of(suites, allSuites, includeTags, excludeTags, tagsResolved, gate, false, false);
    }

    public static TestSelection of(
            List<String> suites,
            boolean allSuites,
            List<String> includeTags,
            List<String> excludeTags,
            boolean tagsResolved,
            boolean gate,
            boolean scriptsOnly,
            boolean noScripts) {
        return new TestSelection(
                suites == null ? List.of() : suites,
                allSuites,
                includeTags == null ? List.of() : includeTags,
                excludeTags == null ? List.of() : excludeTags,
                tagsResolved,
                gate,
                scriptsOnly,
                noScripts);
    }

    /** Gate scripts run with {@code --gate} unless {@link #noScripts}, or with {@link #scriptsOnly}. */
    public boolean runGateScripts() {
        return !noScripts && (scriptsOnly || gate);
    }

    /** Resolve concrete suite names for a module (validates unknown names). */
    public Resolved resolve(List<String> discovered) {
        Objects.requireNonNull(discovered, "discovered");
        if (allSuites) {
            if (discovered.isEmpty()) {
                // No sources anywhere — still "default" so compile-test no-ops cleanly.
                return new Resolved(List.of(TestSuites.DEFAULT), includeTags, excludeTags);
            }
            return new Resolved(List.copyOf(discovered), includeTags, excludeTags);
        }
        List<String> want = suites.isEmpty() ? (gate ? TestSuites.GATE : List.of(TestSuites.DEFAULT)) : suites;
        List<String> missing = new ArrayList<>();
        LinkedHashSet<String> known = new LinkedHashSet<>(discovered);
        // Default suite may be requested even when empty (no-op compile).
        known.add(TestSuites.DEFAULT);
        List<String> ordered = new ArrayList<>();
        for (String s : want) {
            if (known.contains(s) || s.equals(TestSuites.DEFAULT)) {
                if (!ordered.contains(s)) ordered.add(s);
            } else if (gate && TestSuites.INTEGRATION.equals(s)) {
                // --gate includes integration when the directory exists; skip otherwise.
            } else {
                missing.add(s);
            }
        }
        return new Resolved(
                List.copyOf(ordered), includeTags, excludeTags, List.copyOf(missing), List.copyOf(discovered));
    }

    /** Stamp / wire identity fragment (stable). */
    public String identityToken() {
        return "suites="
                + (allSuites ? "*" : String.join(",", suites.isEmpty() ? List.of(TestSuites.DEFAULT) : suites))
                + ";+tag="
                + String.join(",", includeTags)
                + ";-tag="
                + String.join(",", excludeTags);
    }

    public TestSelection withExcludeTags(List<String> more) {
        if (more == null || more.isEmpty()) return this;
        LinkedHashSet<String> merged = new LinkedHashSet<>(excludeTags);
        merged.addAll(normalizeNames(more));
        return new TestSelection(
                suites, allSuites, includeTags, List.copyOf(merged), tagsResolved, gate, scriptsOnly, noScripts);
    }

    public TestSelection withIncludeTags(List<String> more) {
        if (more == null || more.isEmpty()) return this;
        LinkedHashSet<String> merged = new LinkedHashSet<>(includeTags);
        merged.addAll(normalizeNames(more));
        return new TestSelection(
                suites, allSuites, List.copyOf(merged), excludeTags, tagsResolved, gate, scriptsOnly, noScripts);
    }

    private static List<String> normalizeNames(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return List.copyOf(out);
    }

    /**
     * Concrete suite list after discovery. {@link #missing} non-empty means the user asked for a
     * suite that is not present (caller should fail with a clear error).
     */
    public record Resolved(
            List<String> suites,
            List<String> includeTags,
            List<String> excludeTags,
            List<String> missing,
            List<String> discovered) {

        public Resolved(List<String> suites, List<String> includeTags, List<String> excludeTags) {
            this(suites, includeTags, excludeTags, List.of(), List.of());
        }

        public boolean ok() {
            return missing == null || missing.isEmpty();
        }

        public String missingMessage() {
            if (ok()) return "";
            if (missing.contains(TestSuites.GUARD)) {
                return "the guard suite is not a test suite: guard tests run in the guard lanes, not under jk test"
                        + " (use `jk guard`)";
            }
            String known =
                    discovered == null || discovered.isEmpty() ? TestSuites.DEFAULT : String.join(", ", discovered);
            return "unknown test suite"
                    + (missing.size() == 1 ? "" : "s")
                    + " '"
                    + String.join("', '", missing)
                    + "' (available: "
                    + known
                    + "; use --all for every suite)";
        }
    }
}
