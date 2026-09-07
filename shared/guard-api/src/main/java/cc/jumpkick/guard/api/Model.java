// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.util.List;

/** The build model: manifests, lock, tier table and toolchain, as the engine parsed them. */
public interface Model {

    /** Workspace-relative module paths; {@code ""} is the root when it has sources of its own. */
    List<String> modules();

    /** What {@code module} declares in {@code scope}. */
    List<Dependency> deps(String module, DepScope scope);

    /** The lock, or an empty one when the project has none yet. */
    Lock lock();

    Tiers tiers();

    Toolchain toolchain();
}
