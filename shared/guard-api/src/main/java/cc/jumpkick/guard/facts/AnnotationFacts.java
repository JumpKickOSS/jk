// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One annotation as the class file carries it. {@code SOURCE}-retention annotations are not in the
 * class file and so never appear here — a rule that names one is a load error, not a silent miss.
 *
 * @param desc the descriptor, {@code Lorg/junit/jupiter/api/Tag;}
 * @param runtimeVisible {@code RUNTIME} retention; {@code false} is {@code CLASS}
 * @param values string-shaped attribute values (strings, enum constants as {@code NAME}, class
 *     literals as internal names); arrays keep their elements. A nested annotation (a repeatable's
 *     container such as {@code @Tags}) is recorded as its own fact on the same element, so {@code
 *     @Tags({@Tag("a"), @Tag("b")})} yields a {@code Tags} fact and two {@code Tag} facts
 */
public record AnnotationFacts(String desc, boolean runtimeVisible, Map<String, List<String>> values) {

    public AnnotationFacts {
        // Sorted by attribute name: Map.copyOf iterates in a per-JVM order.
        TreeMap<String, List<String>> sorted = new TreeMap<>();
        values.forEach((k, v) -> sorted.put(k, List.copyOf(v)));
        values = Collections.unmodifiableMap(sorted);
    }

    /** {@code org.junit.jupiter.api.Tag} for {@code Lorg/junit/jupiter/api/Tag;}. */
    public String typeName() {
        return Descriptors.typeName(desc);
    }

    public List<String> value() {
        return values.getOrDefault("value", List.of());
    }
}
