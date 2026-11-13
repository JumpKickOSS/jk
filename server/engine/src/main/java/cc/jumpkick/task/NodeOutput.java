// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What {@code node-build} left for the packaging after it.
 *
 * @param out the build's output directory once written or restored; {@code null} when the module
 *     has no build command
 * @param exports {@code [node] exports}: each name to the output it names
 */
public record NodeOutput(@Nullable Path out, Map<String, Path> exports) {

    public NodeOutput {
        exports = Collections.unmodifiableMap(new LinkedHashMap<>(exports));
    }
}
