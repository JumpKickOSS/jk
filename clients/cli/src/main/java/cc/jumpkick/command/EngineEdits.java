// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Client side of engine-hosted jk.toml edits ({@code EDIT_REQUEST}): client names the op; engine
 * parses/edits/writes. In-process twin under {@code jk.test.noEngine}.
 */
final class EngineEdits {

    private EngineEdits() {}

    /** Apply one edit; returns whether the file changed. Throws with a ready-to-print message. */
    static boolean apply(Path file, String op, List<String> args) throws IOException {
        if (Boolean.getBoolean("jk.test.noEngine")
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"))) {
            var result = cc.jumpkick.cli.engine.InProcessEngine.require().edit(file, op, args);
            if (result[1] != null) throw new IOException(result[1]);
            return Boolean.parseBoolean(result[0]);
        }
        return cc.jumpkick.cli.engine.EngineClient.edit(cc.jumpkick.engine.EnginePaths.current(), file, op, args);
    }
}
