// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.engine.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Client side of engine-hosted jk.toml edits ({@code EDIT_REQUEST}): client names the op; engine
 * parses/edits/writes.test.noEngine}.
 */
final class EngineEdits {

    private EngineEdits() {}

    /** Apply one edit; returns whether the file changed. Throws with a ready-to-print message. */
    static boolean apply(Path file, String op, List<String> args) throws IOException {

        return EngineClient.edit(EnginePaths.current(), file, op, args);
    }

    static String applyDetail(Path file, String op, List<String> args) throws IOException {
        return EngineClient.editDetail(EnginePaths.current(), file, op, args);
    }
}
