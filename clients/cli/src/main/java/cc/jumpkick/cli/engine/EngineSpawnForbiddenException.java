// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;

/**
 * No engine serves this client, and the process may not start one ({@link EnginePaths#NO_SPAWN_ENV}):
 * a fast-tier test reached for an engine. Raised before anything is spawned or taken over.
 */
public final class EngineSpawnForbiddenException extends IOException {

    public EngineSpawnForbiddenException(Path socket) {
        super("no engine serves " + socket + " and " + EnginePaths.NO_SPAWN_ENV
                + " forbids starting one: a fast-tier test may not reach an engine. Tag the test"
                + " @Tag(\"integration\"), or give the code under test its data without one.");
    }
}
