// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The line a build prints under its result when the tree decided its kind: a release build names the
 * release and its tag, and a tree at the release tag with changes says why it built a snapshot.
 */
public final class ReleaseLine {

    private ReleaseLine() {}

    /** Print {@code info}'s release line, if it has one, for a human reader. */
    public static void print(@Nullable ProjectInfo info, GlobalOptions global) {
        if (info == null || info.error() != null || info.releaseLine().isEmpty() || global.outputIsJson()) return;
        if (info.releaseBuild()) {
            CliOutput.out(Theme.colorize(Glyphs.CHECK, Theme.active().success()) + " " + info.releaseLine());
        } else {
            CliOutput.err(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " " + info.releaseLine());
        }
    }

    /**
     * Print, for {@code jk explain}, which modules a release leaves out: the members whose {@code
     * [publish] skip} keeps them out of {@code jk publish} and {@code jk install}. Nothing when none
     * does, since then every module with a jar ships.
     */
    public static void printSkipped(@Nullable ProjectInfo info, GlobalOptions global) {
        String line = skippedLine(info);
        if (line != null && !global.outputIsJson()) CliOutput.out(Glyphs.BULLET + " " + line);
    }

    /** {@link #printSkipped}'s line, or null when it prints none. */
    static @Nullable String skippedLine(@Nullable ProjectInfo info) {
        if (info == null || info.error() != null) return null;
        List<String> skipped = info.publishSkipped();
        if (skipped.isEmpty() && info.publishSkip()) skipped = List.of(info.name());
        if (skipped.isEmpty()) return null;
        return "jk publish and jk install leave out " + skipped.size() + (skipped.size() == 1 ? " module" : " modules")
                + " ([publish] skip): " + String.join(", ", skipped) + "; every other module with a jar ships";
    }
}
