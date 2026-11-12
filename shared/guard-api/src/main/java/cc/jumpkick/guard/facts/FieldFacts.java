// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param constantValue the {@code ConstantValue} attribute of a {@code static final} field, as a
 *     string; the owner-vocabulary source. {@code null} when the field has none.
 * @param initializer for a static field the static initializer assigns, the last call made before
 *     the assignment as {@code owner#name} in internal form ({@code java/util/HashMap#<init>},
 *     {@code java/util/List#of}): what produced the value. {@code null} when the static initializer
 *     does not assign it, or assigns it without a call (a constant, {@code null}, an array).
 */
public record FieldFacts(
        String name,
        String desc,
        int access,
        @Nullable String constantValue,
        List<AnnotationFacts> annotations,
        @Nullable String initializer) {

    public FieldFacts {
        annotations = List.copyOf(annotations);
    }
}
