// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Path;

/**
 * The fail wedge of a command that edits {@code jk.toml} and found none in {@code dir}. A
 * directory built in place from its {@code pom.xml} gets the message that names both remedies
 * ({@link ManifestPaths#noManifestToEdit}); any other directory the plain absence.
 */
final class ManifestEditRefusal {

    private ManifestEditRefusal() {}

    /** Print the refusal for {@code chip} and answer the exit code the caller returns. */
    static int print(String chip, Path dir) {
        String shown = PathDisplay.styledRaw(dir);
        CommandWedge.printFail(
                chip, ManifestPaths.isShadowed(dir) ? ManifestPaths.noManifestToEdit(shown) : "no jk.toml in " + shown);
        return Exit.CONFIG;
    }
}
