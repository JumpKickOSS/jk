// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An element and the annotations the class file carries for it. {@code method}/{@code field} are
 * set for member elements; {@code parameter} is the 0-based index for a parameter element.
 */
public record Annotated(
        ClassFacts cls,
        @Nullable MethodFacts method,
        @Nullable FieldFacts field,
        int parameter,
        List<AnnotationFacts> annotations) {

    public Annotated {
        annotations = List.copyOf(annotations);
    }

    /** Whether one of the annotations is {@code binaryTypeName}. */
    public boolean has(String binaryTypeName) {
        for (AnnotationFacts a : annotations) if (a.typeName().equals(binaryTypeName)) return true;
        return false;
    }

    /** The element as a site: the class for class and package elements, {@code Class#member} otherwise. */
    public Site site() {
        return new ClassSite(cls);
    }
}
