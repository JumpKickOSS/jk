// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Objects;

/**
 * Typed key for a value in a BuildPlan's shared state. Same name = same slot; type is only for
 * the cast on {@code get}/{@code require}.
 */
public record BuildPlanKey<T>(String name, Class<T> type) {

    public BuildPlanKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public static <T> BuildPlanKey<T> of(String name, Class<T> type) {
        return new BuildPlanKey<>(name, type);
    }
}
