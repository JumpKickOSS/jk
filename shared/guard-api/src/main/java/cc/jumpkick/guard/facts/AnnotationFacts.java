// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.List;
import java.util.Map;

/**
 * One annotation as the class file carries it. {@code SOURCE}-retention annotations are not in the
 * class file and so never appear here — a rule that names one is a load error, not a silent miss.
 *
 * @param desc the descriptor, {@code Lorg/junit/jupiter/api/Tag;}
 * @param runtimeVisible {@code RUNTIME} retention; {@code false} is {@code CLASS}
 * @param values string-shaped attribute values (strings, enum constants as {@code NAME}, class
 *     literals as internal names); arrays keep their elements; nested annotations are not recorded
 */
public record AnnotationFacts(String desc, boolean runtimeVisible, Map<String, List<String>> values) {

    public AnnotationFacts {
        values = Map.copyOf(values);
    }

    /** {@code org.junit.jupiter.api.Tag} for {@code Lorg/junit/jupiter/api/Tag;}. */
    public String typeName() {
        return Descriptors.typeName(desc);
    }

    public List<String> value() {
        return values.getOrDefault("value", List.of());
    }
}
