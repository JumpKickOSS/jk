// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which test suites and JUnit tags a {@code jk test} / build-with-tests run should use.
 *
 * <p>{@link #DEFAULT} runs only the {@code test} suite with no tag filters. {@link #guard} is the
 * share-the-commit bar: requested suites (default {@code test} + {@code integration}) with
 * {@code integration} optional when it is not on disk.
 */
public record TestSelection(
        /** Explicit suite names; empty means default suite only (unless {@link #allSuites}). */
        List<String> suites,
        /** When true, run every discovered suite (ignores {@link #suites} for selection). */
        boolean allSuites,
        /** {@code --include-tags}: a one-run filter on the selected suites' tests; empty means none. */
        List<String> includeTags,
        /** {@code --exclude-tags}: a one-run filter on the selected suites' tests; empty means none. */
        List<String> excludeTags,
        /**
         * {@code --guard}: each of {@link #suites} runs in the modules that have it; a module without
         * one ({@code e2e} where there is no {@code src/e2e}) runs the rest, never an unknown-suite
         * error.
         */
        boolean guard,
        /** {@code --scripts-only}: run guard scripts, skip JUnit. */
        boolean scriptsOnly,
        /** {@code --no-scripts}: skip guard scripts even when {@link #guard} is set. */
        boolean noScripts,
        /**
         * {@code --class}: run only test classes matching these names — a fully qualified name, a
         * simple name (matched in any package), or either with {@code *} wildcards. Empty means every
         * class the suites and tags select. A pattern qualified with its module ({@code
         * server/engine:EngineIT}) holds in that module alone, and a module no qualified pattern
         * names runs what the unqualified ones select ({@link #forModule}).
         */
        List<String> classes,
        /**
         * {@code --exclusive}: absolute directories of the modules whose suites run; their
         * prerequisites compile but run no tests. Empty means every module in the run tests.
         */
        List<String> onlyModules) {

    /** What splits a qualified {@code --class} pattern's module from its class: {@code server/engine:EngineIT}. */
    public static final char MODULE_SEPARATOR = ':';

    public static final TestSelection DEFAULT =
            new TestSelection(List.of(), false, List.of(), List.of(), false, false, false, List.of(), List.of());

    public TestSelection {
        suites = normalizeNames(suites);
        includeTags = normalizeNames(includeTags);
        excludeTags = normalizeNames(excludeTags);
        classes = normalizeNames(classes);
        onlyModules = normalizeNames(onlyModules);
    }

    public static TestSelection of(
            List<String> suites, boolean allSuites, List<String> includeTags, List<String> excludeTags) {
        return of(suites, allSuites, includeTags, excludeTags, false, false, false);
    }

    public static TestSelection of(
            List<String> suites,
            boolean allSuites,
            List<String> includeTags,
            List<String> excludeTags,
            boolean guard,
            boolean scriptsOnly,
            boolean noScripts) {
        return of(suites, allSuites, includeTags, excludeTags, guard, scriptsOnly, noScripts, List.of());
    }

    public static TestSelection of(
            List<String> suites,
            boolean allSuites,
            List<String> includeTags,
            List<String> excludeTags,
            boolean guard,
            boolean scriptsOnly,
            boolean noScripts,
            List<String> classes) {
        return new TestSelection(
                suites == null ? List.of() : suites,
                allSuites,
                includeTags == null ? List.of() : includeTags,
                excludeTags == null ? List.of() : excludeTags,
                guard,
                scriptsOnly,
                noScripts,
                classes == null ? List.of() : classes,
                List.of());
    }

    /**
     * The suites {@code --guard} runs in the workspace whose root is {@code root}: its {@code [test]
     * guard-suites} ({@link TestSuites#DEFAULT} when that list is empty), else {@link
     * TestSuites#GUARD_SUITES}. A name that is no suite name is refused.
     */
    public static List<String> guardSuites(Path root) {
        var scan = TomlScan.scan(ManifestPaths.manifestIn(root), "test.guard-suites");
        if (!scan.hasKey("test.guard-suites")) return TestSuites.GUARD_SUITES;
        List<String> suites = new ArrayList<>(scan.stringArray("test.guard-suites"));
        if (suites.isEmpty()) suites.add(TestSuites.DEFAULT);
        for (String name : suites) {
            if (!TestSuites.isSuiteName(name)) {
                throw new IllegalArgumentException("unknown test suite '" + name + "' in [test] guard-suites");
            }
        }
        return List.copyOf(suites);
    }

    /**
     * The module half of a qualified {@code --class} pattern ({@code server/engine} of {@code
     * server/engine:EngineIT}), or null when the pattern is unqualified. A class name never holds a
     * {@code :}, so the last one splits the two, whatever the module path holds.
     */
    public static @Nullable String moduleOf(String pattern) {
        int at = pattern.lastIndexOf(MODULE_SEPARATOR);
        return at < 0 ? null : pattern.substring(0, at);
    }

    /** The class half of a {@code --class} pattern: the pattern itself when it is unqualified. */
    public static String classOf(String pattern) {
        return pattern.substring(pattern.lastIndexOf(MODULE_SEPARATOR) + 1);
    }

    /** {@code pattern}'s class half qualified with {@code module}. */
    public static String qualify(String module, String pattern) {
        return module + MODULE_SEPARATOR + classOf(pattern);
    }

    /** The {@code --class} patterns that hold in every module: those no module qualifies. */
    public List<String> unqualifiedClasses() {
        return classes.stream().filter(p -> moduleOf(p) == null).toList();
    }

    /**
     * Whether a qualified {@code --class} pattern names {@code moduleDir}. A relative module path is
     * read from {@code base}; the CLI sends each one already resolved to the module's directory.
     */
    public boolean namesModule(Path moduleDir, Path base) {
        for (String pattern : classes) {
            String module = moduleOf(pattern);
            if (module != null && sameDir(base.resolve(module), moduleDir)) return true;
        }
        return false;
    }

    /**
     * This selection as {@code moduleDir} runs it: the unqualified {@code --class} patterns, and the
     * class half of each pattern qualified with this module. A module no qualified pattern names
     * runs what the unqualified ones select, its whole suites when there are none. The result names
     * no module, so the suite's key is the one a plain {@code --class} run of the same classes has.
     */
    public TestSelection forModule(Path moduleDir, Path base) {
        if (classes.stream().allMatch(p -> moduleOf(p) == null)) return this;
        List<String> local = new ArrayList<>();
        for (String pattern : classes) {
            String module = moduleOf(pattern);
            if (module == null) local.add(pattern);
            else if (sameDir(base.resolve(module), moduleDir)) local.add(classOf(pattern));
        }
        return withClasses(local);
    }

    private static boolean sameDir(Path a, Path b) {
        Path x = a.toAbsolutePath().normalize();
        Path y = b.toAbsolutePath().normalize();
        if (x.equals(y)) return true;
        try {
            return x.toRealPath().equals(y.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** True when {@code --exclusive} limits the run's suites to {@link #onlyModules}. */
    public boolean exclusive() {
        return !onlyModules.isEmpty();
    }

    /** Guard scripts run with {@code --guard} unless {@link #noScripts}, or with {@link #scriptsOnly}. */
    public boolean runGuardScripts() {
        return !noScripts && (scriptsOnly || guard);
    }

    /** Resolve concrete suite names for a module (validates unknown names). */
    public Resolved resolve(List<String> discovered) {
        return resolve(discovered, Set.of());
    }

    /**
     * {@link #resolve(List)} for a module whose {@code explicit} suites run only when named: {@link
     * #allSuites} leaves them out.
     */
    public Resolved resolve(List<String> discovered, Set<String> explicit) {
        Objects.requireNonNull(discovered, "discovered");
        if (allSuites) {
            List<String> every =
                    discovered.stream().filter(s -> !explicit.contains(s)).toList();
            if (every.isEmpty()) {
                // No sources anywhere — still "default" so compile-test no-ops cleanly.
                return new Resolved(List.of(TestSuites.DEFAULT), includeTags, excludeTags);
            }
            return new Resolved(every, includeTags, excludeTags);
        }
        List<String> want = wanted();
        List<String> missing = new ArrayList<>();
        LinkedHashSet<String> known = new LinkedHashSet<>(discovered);
        // Default suite may be requested even when empty (no-op compile).
        known.add(TestSuites.DEFAULT);
        List<String> ordered = new ArrayList<>();
        for (String s : want) {
            if (known.contains(s) || s.equals(TestSuites.DEFAULT)) {
                if (!ordered.contains(s)) ordered.add(s);
            } else if (!guard) {
                missing.add(s);
            }
        }
        return new Resolved(
                List.copyOf(ordered), includeTags, excludeTags, List.copyOf(missing), List.copyOf(discovered));
    }

    /** Stamp / wire identity fragment (stable): the suites {@link #resolve} would want, not the raw list. */
    public String identityToken() {
        return "suites="
                + (allSuites ? "*" : String.join(",", wanted()))
                + ";+tag="
                + String.join(",", includeTags)
                + ";-tag="
                + String.join(",", excludeTags)
                + (classes.isEmpty() ? "" : ";class=" + String.join(",", classes));
    }

    /** Whether this selection runs {@code suite}: it is named, or {@link #allSuites} and it is not {@code explicit}. */
    public boolean runs(String suite, boolean explicit) {
        return allSuites ? !explicit : wanted().contains(suite);
    }

    /**
     * The suites asked for: the named ones, else the guard's list under {@link #guard}, else the
     * default suite; empty under {@link #allSuites}, which names none.
     */
    public List<String> wantedSuites() {
        return allSuites ? List.of() : wanted();
    }

    /** Whether this selection runs the default suite alone: plain {@code jk test}, the fast tier's scope. */
    public boolean defaultSuiteAlone() {
        return !allSuites && wanted().equals(List.of(TestSuites.DEFAULT));
    }

    /** The suites asked for: the named ones, else the guard's list under {@link #guard}, else the default suite. */
    private List<String> wanted() {
        if (!suites.isEmpty()) return suites;
        return guard ? TestSuites.GUARD_SUITES : List.of(TestSuites.DEFAULT);
    }

    public TestSelection withClasses(List<String> names) {
        return new TestSelection(
                suites,
                allSuites,
                includeTags,
                excludeTags,
                guard,
                scriptsOnly,
                noScripts,
                names == null ? List.of() : names,
                onlyModules);
    }

    public TestSelection withOnlyModules(List<String> moduleDirs) {
        return new TestSelection(
                suites,
                allSuites,
                includeTags,
                excludeTags,
                guard,
                scriptsOnly,
                noScripts,
                classes,
                moduleDirs == null ? List.of() : moduleDirs);
    }

    public TestSelection withExcludeTags(List<String> more) {
        if (more == null || more.isEmpty()) return this;
        LinkedHashSet<String> merged = new LinkedHashSet<>(excludeTags);
        merged.addAll(normalizeNames(more));
        return new TestSelection(
                suites,
                allSuites,
                includeTags,
                List.copyOf(merged),
                guard,
                scriptsOnly,
                noScripts,
                classes,
                onlyModules);
    }

    public TestSelection withIncludeTags(List<String> more) {
        if (more == null || more.isEmpty()) return this;
        LinkedHashSet<String> merged = new LinkedHashSet<>(includeTags);
        merged.addAll(normalizeNames(more));
        return new TestSelection(
                suites,
                allSuites,
                List.copyOf(merged),
                excludeTags,
                guard,
                scriptsOnly,
                noScripts,
                classes,
                onlyModules);
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
                return "guard tests do not run under jk test; use jk guard";
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
