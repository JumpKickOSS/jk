// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import cc.jumpkick.host.TestClassRule;
import cc.jumpkick.host.TestTagRule;
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
 * Which classes of a facts index the JUnit Platform would find tests in, by {@link TestClassRule},
 * and the tags that select them, by {@link TestTagRule}. A supertype or composed annotation is read
 * from the indexes given, then from {@code outside}; a type read from neither declares no test and
 * no tag, so a helper is never judged a test class on a guess.
 */
public final class TestClassFacts {

    private static final String TAG = "org.junit.jupiter.api.Tag";
    private static final String TAGS = "org.junit.jupiter.api.Tags";

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

    /**
     * The JUnit tags that select tests of {@code c}, by {@link TestTagRule}: on it, its members and
     * its supertypes, directly or through a composed annotation.
     */
    public Set<String> tags(ClassFacts c) {
        return TestTagRule.tags(
                tagged(c), name -> classNamed(name).map(TestClassFacts::tagged).orElse(null));
    }

    private TestClassRule.@Nullable Type type(String binaryName) {
        return classNamed(binaryName).map(TestClassFacts::type).orElse(null);
    }

    /** The class named {@code binaryName}, from the indexes, else from {@code outside}. */
    private Optional<ClassFacts> classNamed(String binaryName) {
        String internal = Descriptors.internalName(binaryName);
        for (FactsIndex index : indexes) {
            Optional<ClassFacts> c = index.classNamed(internal);
            if (c.isPresent()) return c;
        }
        return outside.apply(internal);
    }

    private static TestTagRule.Type tagged(ClassFacts c) {
        Set<String> classTags = new LinkedHashSet<>();
        Set<String> classAnnotations = new LinkedHashSet<>();
        annotationsOf(c.annotations(), classTags, classAnnotations);
        Set<String> memberTags = new LinkedHashSet<>();
        Set<String> memberAnnotations = new LinkedHashSet<>();
        for (MethodFacts m : c.methods()) annotationsOf(m.annotations(), memberTags, memberAnnotations);
        for (FieldFacts f : c.fields()) annotationsOf(f.annotations(), memberTags, memberAnnotations);
        return new TestTagRule.Type(
                c.binaryName(), supertypes(c), classTags, classAnnotations, memberTags, memberAnnotations);
    }

    /** {@code @Tag} values into {@code tags}, any other annotation type but a {@code @Tags} container into {@code others}. */
    private static void annotationsOf(List<AnnotationFacts> annotations, Set<String> tags, Set<String> others) {
        for (AnnotationFacts a : annotations) {
            // The extractor records a @Tags container's own @Tag values on the same element.
            if (a.typeName().equals(TAG)) tags.addAll(a.value());
            else if (!a.typeName().equals(TAGS)) others.add(a.typeName());
        }
    }

    private static List<String> supertypes(ClassFacts c) {
        List<String> supertypes = new ArrayList<>();
        String superName = c.superName();
        if (superName != null) supertypes.add(Descriptors.binaryName(superName));
        for (String i : c.interfaces()) supertypes.add(Descriptors.binaryName(i));
        return supertypes;
    }

    private static TestClassRule.Type type(ClassFacts c) {
        List<String> supertypes = supertypes(c);
        Set<String> classAnnotations = new LinkedHashSet<>();
        for (AnnotationFacts a : c.annotations()) classAnnotations.add(a.typeName());
        Set<String> memberAnnotations = new LinkedHashSet<>();
        for (MethodFacts m : c.methods()) for (AnnotationFacts a : m.annotations()) memberAnnotations.add(a.typeName());
        for (FieldFacts f : c.fields()) for (AnnotationFacts a : f.annotations()) memberAnnotations.add(a.typeName());
        return new TestClassRule.Type(c.binaryName(), c.access(), supertypes, classAnnotations, memberAnnotations);
    }
}
