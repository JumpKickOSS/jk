// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
import java.util.Objects;

/**
 * Named optional-dep set: {@code deps} are short names of {@code optional = true} entries;
 * {@code features} activates other features transitively.
 */
public record Feature(String name, List<String> deps, List<String> features) {

    public Feature {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(deps, "deps");
        Objects.requireNonNull(features, "features");
        deps = List.copyOf(deps);
        features = List.copyOf(features);
    }

    public static Feature of(String name) {
        return new Feature(name, List.of(), List.of());
    }
}
