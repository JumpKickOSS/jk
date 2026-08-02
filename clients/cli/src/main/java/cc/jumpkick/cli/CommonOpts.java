// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.model.command.Opt;
import java.util.List;

/**
 * Shared CLI option definitions so help text stays consistent across commands.
 *
 * <p>Build-family verbs ({@code build}, {@code test}, {@code native}, {@code assemble}, …) should
 * compose these rather than re-declare the same flags with drifting help text.
 */
public final class CommonOpts {

    private CommonOpts() {}

    /**
     * Download / action cache (CAS) override. Default is {@code $JK_CACHE_DIR}, else {@code
     * $JK_HOME/cache} ({@code ~/.cache/jk}). Engine-hosted commands pass the resolved path on the
     * wire so the resident engine uses the same tree — no need to wipe {@code ~/.cache/jk} for cold
     * resolve tests.
     */
    public static Opt cacheDir() {
        return Opt.value(
                "<dir>",
                "Override the download/action cache (CAS). Default: $JK_CACHE_DIR or $JK_HOME/cache (~/.cache/jk).",
                "--cache-dir");
    }

    /** Hidden variant for internal / rarely-needed commands. */
    public static Opt cacheDirHidden() {
        return cacheDir().hide();
    }

    /**
     * Workspace module selection shared by every build-family command ({@code -m}/{@code
     * --modules}, {@code --affected-since}). Resolved via {@code ModuleSelection.resolveOptional}.
     *
     * <p>Semantics: {@code build}/{@code test} treat the selection as the work list (siblings are
     * not rebuilt — pair with {@code --affected-since} to catch dependents); {@code native} builds
     * the selection's prereq closure but native-compiles only the selection.
     */
    public static List<Opt> moduleSelection() {
        return List.of(
                Opt.value(
                        "<sel>",
                        "Only selected modules (paths, project names, or Gradle :name; comma/globs/braces)."
                                + " Intersects with --affected-since. Siblings are not rebuilt.",
                        "-m",
                        "--modules"),
                Opt.value(
                        "<git-ref>",
                        "Only modules (and dependents) changed since this git ref. Intersects with --modules.",
                        "--affected-since"));
    }

    /** Skip compiling and running tests — shared by build / native / install-style verbs. */
    public static Opt skipTests() {
        return Opt.flag("Skip compiling and running tests.", "--skip-tests");
    }
}
