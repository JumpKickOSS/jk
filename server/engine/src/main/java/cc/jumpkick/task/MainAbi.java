// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What a javac main compile that ran changed, for {@code --affected} ranking: the module's class
 * ABI before it, and the sources it compiled.
 *
 * @param before each class's ABI fingerprint from the index the compile started from
 * @param compiled the sources the compile recompiled; empty for a cache hit or a no-op
 */
public record MainAbi(Map<String, ClassAbi.Fingerprint> before, List<Path> compiled) {

    public MainAbi {
        before = Map.copyOf(before);
        compiled = List.copyOf(compiled);
    }
}
