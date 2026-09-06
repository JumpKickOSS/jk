// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.List;

/**
 * @param parameterAnnotations one list per parameter, in order
 * @param branches conditional jumps plus switch arms plus exception handlers — the cyclomatic count
 *     less one
 * @param firstLine the first {@code LineNumberTable} entry, or 0 without debug info
 */
public record MethodFacts(
        String name,
        String desc,
        int access,
        List<AnnotationFacts> annotations,
        List<List<AnnotationFacts>> parameterAnnotations,
        List<CallSite> calls,
        List<FieldRef> fieldRefs,
        int branches,
        int firstLine) {

    public MethodFacts {
        annotations = List.copyOf(annotations);
        parameterAnnotations = List.copyOf(parameterAnnotations);
        calls = List.copyOf(calls);
        fieldRefs = List.copyOf(fieldRefs);
    }

    /** {@code name(desc)}: the member half of a bytecode fingerprint. */
    public String member() {
        return name + desc;
    }

    /** Parameter count from the descriptor. */
    public int parameterCount() {
        int n = 0;
        int i = 1;
        while (desc.charAt(i) != ')') {
            while (desc.charAt(i) == '[') i++;
            if (desc.charAt(i) == 'L') i = desc.indexOf(';', i);
            i++;
            n++;
        }
        return n;
    }
}
