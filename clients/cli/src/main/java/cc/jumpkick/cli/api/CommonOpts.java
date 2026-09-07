// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared CLI option definitions so help text stays consistent across commands.
 *
 * <p>Build-family verbs ({@code build}, {@code test}, {@code native}, {@code assemble}, …) should
 * compose these rather than re-declare the same flags with drifting help text.
 */
public final class CommonOpts {

    private CommonOpts() {}

    /**
     * Cache-tier root override (action index + cache CAS + format stamps). Default is {@code
     * $JK_CACHE_DIR}, else {@code $JK_HOME/cache} ({@code ~/.jk/cache}). Does <em>not</em> move the
     * artifact store ({@code JK_STORE_DIR}). Engine-hosted commands pass the resolved path on the
     * wire so the resident engine uses the same tree.
     */
    public static Opt cacheDir() {
        // Keep ≤ ~36 chars: build-family help pads the flag column wide (78-col budget).
        return Opt.value("<dir>", "Override cache-tier root (not store)", "--cache-dir");
    }

    /** Hidden variant for internal / rarely-needed commands. */
    public static Opt cacheDirHidden() {
        return cacheDir().hide();
    }

    /**
     * Workspace module selection shared by every build-family command ({@code -m}/{@code
     * --modules}, {@code --affected}, {@code --affected-since}). Resolved engine-side via
     * {@code projectInfo}.
     *
     * <p>Semantics: {@code build}/{@code test} treat the selection as the work list (siblings are
     * not rebuilt — pair with {@code --affected-since} to catch dependents); {@code native} builds
     * the selection's prereq closure but native-compiles only the selection. A working directory
     * that is a workspace member with no {@code -m} is the same as {@code -m <that-module>}.
     *
     * <p>{@code --affected} is the working-tree cone; {@code --affected-since} is {@code ref...HEAD}.
     * They are mutually exclusive. {@code --aff} is ambiguous. {@code jk test} lists ranked classes
     * for either flag and does not run them — pass command-specific opts for that help.
     */
    public static List<Opt> moduleSelection() {
        return moduleSelection(
                Opt.flag("WIP working-tree change set", "--affected"),
                Opt.value("<git-ref>", "Modules changed since this git ref", "--affected-since"));
    }

    /** Like {@link #moduleSelection()} with a command-specific {@code --affected} help string. */
    public static List<Opt> moduleSelection(Opt affected) {
        return moduleSelection(
                affected, Opt.value("<git-ref>", "Modules changed since this git ref", "--affected-since"));
    }

    /** Like {@link #moduleSelection()} with command-specific {@code --affected} / {@code --affected-since} help. */
    public static List<Opt> moduleSelection(Opt affected, Opt affectedSince) {
        return List.of(
                Opt.value("<sel>", "Only selected modules (paths/globs)", "-m", "--modules"), affected, affectedSince);
    }

    /**
     * The canonical name of {@code --jdks-dir} — the key {@link Invocation} files its value under,
     * and the one spelling of the flag. Wire-side it is {@code jdksDir}
     * ({@code cc.jumpkick.wire.protocol.ProtoJobs.JDKS_DIR}); the two are different vocabularies
     * (a CLI flag and a JSON field) and each has exactly one owner.
     */
    public static final String JDKS_DIR = "jdks-dir";

    /**
     * JDK install-root override, shared by the 24 verbs that accept it. Default is
     * {@code $JK_JDKS_DIR}, else the IntelliJ shared root ({@code ~/.jdks}, or
     * {@code ~/Library/Java/JavaVirtualMachines} on macOS) so the IDE and jk share managed
     * runtimes — {@code JK_HOME} does <em>not</em> relocate it (see {@code JkDirs.jdksDir}).
     *
     * <p>Hidden: it exists for tests and for a power user who keeps runtimes elsewhere, and every
     * one of the 24 declarations hid it. Round 3 found six different help strings behind that
     * {@code hide()}, one of which ({@code jk jdk update}'s "the jk JDK directory") named the wrong
     * default — invisible text drifts because nothing renders it.
     */
    public static Opt jdksDir() {
        return Opt.value("<dir>", "Override the JDK install root.", "--" + JDKS_DIR)
                .hide();
    }

    /** The {@code --jdks-dir} override as a path, or {@code null} when the user gave none. */
    public static @Nullable Path jdksDirValue(Invocation in) {
        return in.value(JDKS_DIR).map(Path::of).orElse(null);
    }

    /** Skip compiling and running tests — shared by build / native / install-style verbs. */
    public static Opt skipTests() {
        return Opt.flag("Skip compiling and running tests.", "--skip-tests");
    }

    /**
     * {@code --guard}: the guard lanes (tree, fixtures), the integration suite and the root's guard
     * scripts — on every verb that builds through the test stage, so the share-the-commit bar is one
     * flag wherever a build is asked for.
     */
    public static Opt guard() {
        return Opt.flag("Guards + integration: share the commit", "--guard");
    }

    /** {@code --continue}: finish the graph and report every failure, not just the first. */
    public static Opt keepGoing() {
        return Opt.flag("Keep going; report all failures", "--continue");
    }

    /**
     * Whether this run keeps going: the flag when given, else {@code [engine] continue} /
     * {@code JK_CONTINUE}, whose own default is on under {@code CI} and off at a prompt.
     *
     * <p>Resolved client-side and sent, never re-derived in the engine: a resident daemon's
     * environment is the one that started it, not the one that asked.
     */
    public static boolean keepGoingValue(Invocation in) {
        return in.isSet("continue") || JkEngineConfig.resolve().keepGoing();
    }
}
