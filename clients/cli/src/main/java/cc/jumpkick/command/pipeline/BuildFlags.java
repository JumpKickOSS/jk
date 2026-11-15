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
import cc.jumpkick.config.KeyAuditMode;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
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
        opts.add(Opt.value("<name>", "Build profile", "-p", "--profile"));
        if (tests != WorkspaceTarget.Tests.NOT_RUN) {
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
                profileChoice(in),
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

    /** The profile the engine applies: {@code -p}, or null for none. */
    static @Nullable String profileChoice(Invocation in) {
        return in.value("profile").orElse(null);
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

    /** These flags with the test run skipped: a verb that packages without testing ({@code jk javadoc}). */
    BuildFlags withSkipTests() {
        return new BuildFlags(
                global,
                profile,
                workers,
                true,
                testSelection,
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
                .withVerifySample(verifySample)
                .withKeyAudit(KeyAuditMode.parse(System.getenv(KeyAuditMode.ENV))));
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
     * The CLI's suites, tags and classes → {@link TestSelection}. Suites choose tests: the default
     * suite, {@code --suite} names, {@code --guard}'s list ({@code [test] guard-suites} at the
     * workspace root, else {@code test} + {@code integration}; {@code --suite} wins over it), or
     * {@code --all}. {@code --include-tags} / {@code --exclude-tags} narrow one run of them; no
     * manifest or profile adds tags. Throws if {@code --all} is combined with {@code --suite} or
     * {@code --guard}.
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
        if (all && !suites.isEmpty()) {
            throw new IllegalArgumentException("--all and --suite cannot be combined");
        }
        if (all && guard) {
            throw new IllegalArgumentException("--all and --guard cannot be combined");
        }
        Path wd = GlobalOptions.from(in).workingDir();
        // Guard suites are a workspace fact: from a member directory the root's manifest answers,
        // exactly as when invoked at the root.
        Path root = WorkspaceScan.owningRoot(wd).orElse(wd);
        if (scriptsOnly && !BuildLogicToml.hasStem(root, "guard")) {
            throw new IllegalArgumentException(BuildLogicToml.NO_GUARD_SCRIPTS);
        }
        List<String> include = in.values("include-tags");
        List<String> exclude = in.values("exclude-tags");
        boolean applyGuard = guard && suites.isEmpty();
        if (applyGuard) suites = new ArrayList<>(TestSelection.guardSuites(root));
        List<String> classes = in.values("class");
        for (String pattern : classes) {
            if (pattern.trim().endsWith("#")) {
                throw new IllegalArgumentException("--class " + pattern.trim()
                        + " names no method after '#'; write Class#method or the class alone");
            }
        }
        return TestSelection.of(suites, all, include, exclude, applyGuard, scriptsOnly, noScripts, classes);
    }
}
