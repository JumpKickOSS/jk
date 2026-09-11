// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.ide;

import java.nio.file.Path;
import java.util.List;

/**
 * What one {@link IdeGenerator} produced: the project files, in emit order, and the IDE JDK tables
 * it rewrote. Presentation-free — the CLI renders it as chrome, the MCP tool returns it as a list.
 *
 * @param target the IDE generated for
 * @param files every project file written (or, for a preview, that would be written)
 * @param sdkTables the {@code jdk.table.xml} files the IntelliJ SDK registrar touched
 */
public record IdeGeneration(IdeTarget target, List<Path> files, List<Path> sdkTables) {

    public IdeGeneration {
        files = List.copyOf(files);
        sdkTables = List.copyOf(sdkTables);
    }
}
