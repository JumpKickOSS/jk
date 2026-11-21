// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import cc.jumpkick.host.TestClassRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which classes of a facts index the JUnit Platform would find tests in, by {@link TestClassRule}:
 * a supertype or composed annotation is read from the indexes given, then from {@code outside},
 * and a type read from neither declares no test, so a helper is never judged a test class on a guess.
 */
public final class TestClassFacts {

    private final List<FactsIndex> indexes;
    private final Function<String, Optional<ClassFacts>> outside;
    private final Map<String, List<ClassFacts>> nested = new HashMap<>();
    private final Map<String, Boolean> judged = new HashMap<>();

    /**
     * {@code indexes} the classes judged first (a test index before the main one it compiles
     * against); {@code outside} a type none of them holds, by internal name.
     */
    public TestClassFacts(List<FactsIndex> indexes, Function<String, Optional<ClassFacts>> outside) {
        this.indexes = List.copyOf(indexes);
        this.outside = outside;
        for (FactsIndex index : this.indexes) {
            for (ClassFacts c : index.classList()) {
                String name = c.name();
                for (int i = name.indexOf('$'); i > 0; i = name.indexOf('$', i + 1)) {
                    nested.computeIfAbsent(name.substring(0, i), k -> new ArrayList<>())
                            .add(c);
                }
            }
        }
    }

    /** Over {@code indexes} alone. */
    public TestClassFacts(List<FactsIndex> indexes) {
        this(indexes, name -> Optional.empty());
    }

    /** True when the Platform would find tests in {@code c}: on it, its supertypes or its nested classes. */
    public synchronized boolean isTestClass(ClassFacts c) {
        Boolean known = judged.get(c.name());
        if (known != null) return known;
        List<TestClassRule.Type> inner = new ArrayList<>();
        for (ClassFacts n : nested.getOrDefault(c.name(), List.of())) inner.add(type(n));
        boolean tests = TestClassRule.runsTests(type(c), inner, this::type, false);
        judged.put(c.name(), tests);
        return tests;
    }

    private TestClassRule.@Nullable Type type(String binaryName) {
        String internal = Descriptors.internalName(binaryName);
        for (FactsIndex index : indexes) {
            Optional<ClassFacts> c = index.classNamed(internal);
            if (c.isPresent()) return type(c.get());
        }
        return outside.apply(internal).map(TestClassFacts::type).orElse(null);
    }

    private static TestClassRule.Type type(ClassFacts c) {
        List<String> supertypes = new ArrayList<>();
        String superName = c.superName();
        if (superName != null) supertypes.add(Descriptors.binaryName(superName));
        for (String i : c.interfaces()) supertypes.add(Descriptors.binaryName(i));
        Set<String> classAnnotations = new LinkedHashSet<>();
        for (AnnotationFacts a : c.annotations()) classAnnotations.add(a.typeName());
        Set<String> memberAnnotations = new LinkedHashSet<>();
        for (MethodFacts m : c.methods()) for (AnnotationFacts a : m.annotations()) memberAnnotations.add(a.typeName());
        for (FieldFacts f : c.fields()) for (AnnotationFacts a : f.annotations()) memberAnnotations.add(a.typeName());
        return new TestClassRule.Type(c.binaryName(), c.access(), supertypes, classAnnotations, memberAnnotations);
    }
}
