// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.wire.protocol.ProjectInfo;
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
}
