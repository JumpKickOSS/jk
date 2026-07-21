// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import java.io.IOException;
import java.util.List;

/** Resolves declared dependencies into a flat module → version map. */
public interface Resolver {
    Resolution resolve(List<Dependency> roots) throws IOException, InterruptedException;
}
