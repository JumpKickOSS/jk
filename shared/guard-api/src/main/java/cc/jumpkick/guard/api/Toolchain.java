// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The build environment the manifests declare.
 *
 * @param javaRelease Java release per module ({@code ""} for the root), where declared
 * @param kotlin the root's Kotlin selector as written, or {@code null}
 * @param repositories repository names any manifest declares
 */
public record Toolchain(
        Map<String, Integer> javaRelease, @Nullable String kotlin, List<String> repositories) implements ModelSite {

    public Toolchain {
        javaRelease = Map.copyOf(javaRelease);
        repositories = List.copyOf(repositories);
    }

    @Override
    public String key() {
        return "toolchain";
    }
}
