// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.io.IOException;

/** Resolves Giter8 {@code maven(group, artifact[, stable])} defaults. */
@FunctionalInterface
public interface MavenVersionLookup {

    String latest(String group, String artifact, boolean stable) throws IOException;
}
