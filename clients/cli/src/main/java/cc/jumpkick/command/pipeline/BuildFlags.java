// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ParallelTestsOpts;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.command.ModuleSelectors;
import cc.jumpkick.command.VariantSelection;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The options every build verb shares, declared once ({@link #options}) and parsed once ({@link
 * #from}). Which of them a verb takes follows from its goal's {@link WorkspaceTarget#tests()}
 * policy; a verb adds only options no other verb has.
 */
public record BuildFlags(
        GlobalOptions global,
        @Nullable String profile,
        /** Within-module test JVMs; {@code 0} = auto. Never negative. */
        int workers,
        /** {@code --skip-tests}, or {@code --scripts-only}, which is no JUnit to everything downstream. */
        boolean skipTests,
        TestSelection testSelection,
        @Nullable TestFailureMode testFailures,
        boolean skipNode,
        /** {@code --verify-cache}: every step runs and is held against the record its key names. */
        boolean verifyCache,
        /** {@code --verify-sample N}: modules a verifying build checks; {@code 0} = all. */
        int verifySample,
        boolean keepGoing,
        boolean parallelTests,
        @Nullable Path jdksDir,
        Path cache,
        @Nullable String modulesSpec,
        @Nullable String affectedSince,
        boolean affectedWip) {

    /** What {@code --affected} / {@code --affected-since} mean to a verb. */
    public enum Affected {
        /** The modules a change touched: the work list. */
        MODULES,
        /** A ranked list of the tests a change touched; nothing runs. */
        RANKED_TESTS
    }

    public static final String GUARD_SUITE_OVERRIDE_WARNING = "--guard ignored because --suite was set";
    public static final String SCRIPTS_FLAGS_CONFLICT = "--scripts-only and --no-scripts cannot be combined";

    /** The shared options of a verb whose goal runs tests as {@code tests} says, before its own. */
    public static List<Opt> options(WorkspaceTarget.Tests tests, Affected affected) {
        List<Opt> opts = new ArrayList<>();
        opts.add(Opt.value("<name>", "Build profile (default auto)", "-p", "--profile"));
        if (tests != WorkspaceTarget.Tests.NOT_RUN) {
            opts.add(Opt.flag("Skip profile tag filters", "-n", "--no-profile"));
            opts.add(Opt.value("<N>", "Test JVMs per module (0=auto)", "-w", "--workers"));
            if (tests == WorkspaceTarget.Tests.UNLESS_SKIPPED) opts.add(CommonOpts.skipTests());
            opts.add(Opt.value("<name>", "Test suite directory (repeatable)", "-s", "--suite")
                    .repeat());
            opts.add(Opt.flag("Run every test suite (tags included)", "-A", "--all"));
            opts.add(CommonOpts.guard());
            opts.add(Opt.flag("Guard scripts, no JUnit", "-P", "--scripts-only"));
            opts.add(Opt.flag("Skip guard scripts", "-b", "--no-scripts"));
            opts.add(Opt.value("<tags>", "JUnit tags to include (CSV)", "-I", "--include-tags")
                    .splitOn(","));
            opts.add(Opt.value("<tags>", "JUnit tags to exclude (CSV)", "-E", "--exclude-tags")
                    .splitOn(","));
            opts.addAll(ParallelTestsOpts.options());
            opts.add(CommonOpts.testFailures());
        }
        opts.add(CommonOpts.cacheDir());
        opts.add(CommonOpts.jdksDir());
        opts.add(CommonOpts.keepGoing());
        opts.add(CommonOpts.skipNode());
        opts.add(CommonOpts.verifyCache());
        opts.add(CommonOpts.verifySample());
        opts.addAll(
                affected == Affected.MODULES
                        ? CommonOpts.moduleSelection()
                        : CommonOpts.moduleSelection(
                                Opt.flag("Ranked WIP tests (does not run)", "-f", "--affected"),
                                Opt.value("<git-ref>", "Ranked tests since ref (no run)", "-e", "--affected-since")));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    /**
     * Parse the shared options of {@code in}. Options the verb does not declare read as unset.
     *
     * @throws IllegalArgumentException a combination or value the run cannot honour; the message
     *     is the one to print
     */
    public static BuildFlags from(Invocation in) {
        String affectedSince = in.value("affected-since").orElse(null);
        boolean affectedWip = in.isSet("affected");
        if (ModuleSelectors.bothSelectors(affectedWip, affectedSince)) {
            throw new IllegalArgumentException(ModuleSelectors.BOTH_MESSAGE);
        }
        TestSelection selection = testSelection(in);
        return new BuildFlags(
                GlobalOptions.from(in),
                in.value("profile").orElse(null),
                // A negative -w is not "auto in the other direction".
                in.value("workers")
                        .map(Integer::parseInt)
                        .map(w -> Math.max(0, w))
                        .orElse(0),
                in.isSet("skip-tests") || selection.scriptsOnly(),
                selection,
                CommonOpts.testFailuresValue(in, System::getenv),
                CommonOpts.skipNodeValue(in, System::getenv),
                CommonOpts.verifyCacheValue(in, System::getenv) || CommonOpts.verifySampleValue(in) > 0,
                CommonOpts.verifySampleValue(in),
                CommonOpts.keepGoingValue(in),
                ParallelTestsOpts.enabled(in),
                CommonOpts.jdksDirValue(in),
                in.value("cache-dir").map(CliPaths::abs).orElseGet(JkDirs::cache),
                CommonOpts.modulesSpec(in),
                affectedSince,
                affectedWip);
    }

    /** The concurrent module budget: {@code -j} / {@code JK_JOBS} / {@code [engine] jobs}. */
    public int jobs() {
        return global.jobsEffective();
    }

    /** {@code --redo} / {@code --force} / {@code --verify-cache}: price and run the full work. */
    public boolean rebuild() {
        return global.rebuild || global.force || verifyCache;
    }

    /** Any of {@code -m}, {@code --affected}, {@code --affected-since}. */
    public boolean selects() {
        return ModuleSelectors.anySelector(modulesSpec, affectedSince, affectedWip);
    }

    /** These flags with {@code -m} narrowed to {@code spec}: a member directory selects itself. */
    BuildFlags withModulesSpec(@Nullable String spec) {
        return new BuildFlags(
                global,
                profile,
                workers,
                skipTests,
                testSelection,
                testFailures,
                skipNode,
                verifyCache,
                verifySample,
                keepGoing,
                parallelTests,
                jdksDir,
                cache,
                spec,
                affectedSince,
                affectedWip);
    }

    /** These flags with the test selection replaced; {@code --exclusive} narrows it to the selection. */
    BuildFlags withTestSelection(TestSelection selection) {
        return new BuildFlags(
                global,
                profile,
                workers,
                skipTests,
                selection,
                testFailures,
                skipNode,
                verifyCache,
                verifySample,
                keepGoing,
                parallelTests,
                jdksDir,
                cache,
                modulesSpec,
                affectedSince,
                affectedWip);
    }

    /** These flags with module suites serialized: one debug listener takes one JVM at a time. */
    BuildFlags serialTests() {
        return new BuildFlags(
                global,
                profile,
                workers,
                skipTests,
                testSelection,
                testFailures,
                skipNode,
                verifyCache,
                verifySample,
                keepGoing,
                false,
                jdksDir,
                cache,
                modulesSpec,
                affectedSince,
                affectedWip);
    }

    /** Put the options the engine reads off the session onto it. */
    public void installSession() {
        SessionContext.install(SessionContext.current()
                .withProfile(profile)
                .withRequestedTestWorkers(workers)
                .withKeepGoing(keepGoing)
                .withParallelTests(parallelTests)
                .withTestSelection(testSelection)
                .withTestFailures(testFailures)
                .withSkipNode(skipNode)
                .withVerifyCache(verifyCache)
                .withVerifySample(verifySample));
    }

    /** {@code --guard} is one option identity. */
    static boolean guardRequested(Invocation in) {
        return in.isSet("guard");
    }

    public static void warnGateOverride(Invocation in, GlobalOptions global) {
        if (!guardRequested(in) || in.values("suite").isEmpty()) return;
        if (global.outputIsJson()) return;
        CliOutput.err(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " " + GUARD_SUITE_OVERRIDE_WARNING);
    }

    /**
     * CLI + {@code [test]} / profile tags → {@link TestSelection}. Throws if {@code --all} and
     * {@code --suite} / {@code --guard} are combined.
     *
     * <p>Precedence (each layer replaces the previous for a given list when it speaks):
     *
     * <ol>
     *   <li>{@code [test] include-tags} / {@code exclude-tags} — baseline
     *   <li>Active profile — replaces a list only when that key is present (empty list clears)
     *   <li>CLI {@code --include-tags} / {@code --exclude-tags} — replace that list for the run
     * </ol>
     *
     * Auto profile defers when CLI set any tag option so explicit CLI selection is not overridden
     * by profile filters. {@code --guard} select {@code test} +
     * {@code integration} (or {@code [test] guard-suites}); {@code --suite} wins over {@code --guard}.
     */
    public static TestSelection testSelection(Invocation in) {
        boolean all = in.isSet("all");
        boolean guard = guardRequested(in);
        boolean scriptsOnly = in.isSet("scripts-only");
        boolean noScripts = in.isSet("no-scripts");
        if (scriptsOnly && noScripts) {
            throw new IllegalArgumentException(SCRIPTS_FLAGS_CONFLICT);
        }
        List<String> suites = new ArrayList<>(in.values("suite"));
        boolean cliInclude = in.has("include-tags");
        boolean cliExclude = in.has("exclude-tags");
        if (all && !suites.isEmpty()) {
            throw new IllegalArgumentException("--all and --suite cannot be combined");
        }
        if (all && guard) {
            throw new IllegalArgumentException("--all and --guard cannot be combined");
        }
        Path wd = GlobalOptions.from(in).workingDir();
        // Tags, guard suites and profiles are workspace facts: from a member directory the root's
        // manifest is the baseline layer, exactly as when invoked at the root.
        Path root = WorkspaceScan.owningRoot(wd).orElse(wd);
        if (scriptsOnly && !BuildLogicToml.hasStem(root, "guard")) {
            throw new IllegalArgumentException(BuildLogicToml.NO_GUARD_SCRIPTS);
        }
        Path toml = ManifestPaths.manifestIn(root);
        String explicit = in.value("profile").orElse(null);
        boolean explicitProfile = explicit != null && !explicit.isBlank();
        String profileName = explicitProfile ? explicit : Profiles.autoSelect(System::getenv);
        List<String> scanKeys = new ArrayList<>();
        scanKeys.add("test.include-tags");
        scanKeys.add("test.exclude-tags");
        scanKeys.add("test.guard-suites");
        if (profileName != null && !profileName.isBlank()) {
            scanKeys.add("profiles." + profileName + ".include-tags");
            scanKeys.add("profiles." + profileName + ".exclude-tags");
        }
        var scan = TomlScan.scan(toml, scanKeys.toArray(String[]::new));
        List<String> include = new ArrayList<>(scan.stringArray("test.include-tags"));
        List<String> exclude = new ArrayList<>(scan.stringArray("test.exclude-tags"));
        // Only a layer that explicitly resolved the tag lists makes the selection final
        // (tagsResolved); otherwise the engine may still fold per-module [test] tags in for
        // workspace members (a root with no tags must not erase a module's own filters).
        boolean spoke = scan.hasKey("test.include-tags") || scan.hasKey("test.exclude-tags");
        boolean cliTags = cliInclude || cliExclude;
        boolean applyProfile = !in.isSet("no-profile") && profileName != null && (explicitProfile || !cliTags);
        if (applyProfile) {
            String incKey = "profiles." + profileName + ".include-tags";
            String excKey = "profiles." + profileName + ".exclude-tags";
            if (scan.hasKey(incKey)) {
                include = new ArrayList<>(scan.stringArray(incKey));
                spoke = true;
            }
            if (scan.hasKey(excKey)) {
                exclude = new ArrayList<>(scan.stringArray(excKey));
                spoke = true;
            }
        }
        if (cliInclude) {
            include = new ArrayList<>(in.values("include-tags"));
            // An explicit --include-tags overrides a baseline/profile exclude of the same tag:
            // JUnit include ∧ exclude of one tag selects nothing. An explicit --exclude-tags below
            // still wins over the include (it replaces the exclude list after this).
            List<String> included = include.stream().map(String::trim).toList();
            exclude.removeIf(e -> included.contains(e.trim()));
            spoke = true;
        }
        if (cliExclude) {
            // `--exclude-tags ""` is the explicit CLI clear: blank values normalize away, the
            // spoke flag keeps the empty result authoritative.
            exclude = new ArrayList<>(in.values("exclude-tags"));
            spoke = true;
        }
        // --all is every suite and no baseline/profile tag exclude; explicit CLI tag flags still
        // compose on top, so `--all --exclude-tags slow` widens but keeps slow out.
        if (all) {
            if (!cliInclude) include = new ArrayList<>();
            if (!cliExclude) exclude = new ArrayList<>();
            spoke = true;
        }
        boolean applyGuard = guard && suites.isEmpty();
        if (applyGuard) {
            if (scan.hasKey("test.guard-suites")) {
                suites = new ArrayList<>(scan.stringArray("test.guard-suites"));
                if (suites.isEmpty()) suites.add(TestSuites.DEFAULT);
                for (String name : suites) {
                    if (!TestSuites.isSuiteName(name)) {
                        throw new IllegalArgumentException("unknown test suite '" + name + "' in [test] guard-suites");
                    }
                }
            } else {
                suites = new ArrayList<>(TestSuites.GUARD_SUITES);
            }
        }
        List<String> classes = in.values("class");
        for (String pattern : classes) {
            if (pattern.trim().endsWith("#")) {
                throw new IllegalArgumentException("--class " + pattern.trim()
                        + " names no method after '#' — write Class#method, or the class alone");
            }
        }
        return TestSelection.of(suites, all, include, exclude, spoke, applyGuard, scriptsOnly, noScripts, classes);
    }
}
