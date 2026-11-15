// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A class shaped like Kotlin's output for the facts digest: a {@code @Metadata}-style annotation with
 * primitive-array values, and many type refs.
 */
@Versioned.Meta(
        mv = {2, 1, 0},
        flags = {true, false},
        d1 = {"a", "b"},
        k = 1)
@SuppressWarnings("unused")
public final class Versioned {

    /** Kotlin's {@code @Metadata}: {@code mv} and friends are primitive arrays. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Meta {
        int[] mv();

        boolean[] flags() default {};

        String[] d1() default {};

        int k();
    }

    private List<String> names = List.of();
    private Map<String, Integer> counts = Map.of();
    private Optional<URI> home = Optional.empty();
    private BigDecimal amount = BigDecimal.ZERO;

    Duration timeout(UUID id, StringBuilder sb) {
        sb.append(id);
        return Duration.ofSeconds(names.size() + counts.size() + amount.intValue() + (home.isPresent() ? 1 : 0));
    }
}
