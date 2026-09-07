// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** What the build produced for the scope: POMs, jars and a coverage report, when present. */
public interface Output {

    List<Path> poms();

    List<Path> jars();

    Optional<Path> coverage();
}
