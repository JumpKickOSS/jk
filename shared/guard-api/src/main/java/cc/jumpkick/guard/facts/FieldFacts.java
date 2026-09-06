// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param constantValue the {@code ConstantValue} attribute of a {@code static final} field, as a
 *     string; the owner-vocabulary source. {@code null} when the field has none.
 */
public record FieldFacts(
        String name, String desc, int access, @Nullable String constantValue, List<AnnotationFacts> annotations) {

    public FieldFacts {
        annotations = List.copyOf(annotations);
    }
}
