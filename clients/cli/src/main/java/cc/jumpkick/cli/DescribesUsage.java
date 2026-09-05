// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Optional escape hatch a command's user object MAY implement to express help details not captured
 * in the {@link cc.jumpkick.model.command.CliCommand} defaults. No command uses this yet; it exists
 * as an extension point for future commands.
 */
public interface DescribesUsage {

    default String @Nullable [] longDescription() {
        return null;
    }

    default @Nullable List<CommandGroup> commandGroups() {
        return null;
    }
}
