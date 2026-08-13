// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.cli.tui.RichText;
import java.util.List;

/**
 * What one {@link IdeGenerator} produced, for the shared {@code IDE} wedge. Generators do not
 * print; {@link IdeChrome} owns the live chip and the accumulating detail tree.
 *
 * @param details newest-last facts for this generator (chrome prepends the group so the latest
 *     IDE's rows sit at the top of the live tree)
 */
public record IdeGeneration(List<RichText> details) {

    public IdeGeneration {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static IdeGeneration of(List<RichText> details) {
        return new IdeGeneration(details);
    }

    public static IdeGeneration of(RichText... details) {
        return new IdeGeneration(details == null ? List.of() : List.of(details));
    }
}
