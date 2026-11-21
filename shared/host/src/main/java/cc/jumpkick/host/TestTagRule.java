// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The JUnit tags a class carries, judged from class-file facts as JUnit's {@code
 * findRepeatableAnnotations} finds {@code @Tag}: on the class and on its superclasses and
 * interfaces, each directly or through a composed annotation meta-annotated with one. The engine's
 * test index and the guard facts both ask this one rule. Names are binary names.
 */
public final class TestTagRule {

    private TestTagRule() {}

    /**
     * One class file as the rule reads it: {@code supertypes} its superclass, then its interfaces;
     * {@code classTags} the {@code @Tag} values on the class itself and {@code classAnnotations} its
     * other annotation types; {@code memberTags} and {@code memberAnnotations} the same of its fields
     * and methods.
     */
    public record Type(
            String name,
            List<String> supertypes,
            Collection<String> classTags,
            Collection<String> classAnnotations,
            Collection<String> memberTags,
            Collection<String> memberAnnotations) {}

    /** The types beyond the one read: supertypes and annotations, by binary name. */
    @FunctionalInterface
    public interface Types {
        /** The type named {@code binaryName}, or {@code null} when it cannot be read. */
        @Nullable
        Type get(String binaryName);
    }

    /** The tags every test of {@code type} carries: those on it and its supertypes, composed ones followed. */
    public static Set<String> classTags(Type type, Types types) {
        Set<String> out = new LinkedHashSet<>();
        for (Type at : hierarchy(type, types)) {
            out.addAll(at.classTags());
            for (String a : at.classAnnotations()) out.addAll(metaTags(a, types, new LinkedHashSet<>()));
        }
        return out;
    }

    /** The tags on the members of {@code type} and its supertypes, composed ones followed. */
    public static Set<String> memberTags(Type type, Types types) {
        Set<String> out = new LinkedHashSet<>();
        for (Type at : hierarchy(type, types)) {
            out.addAll(at.memberTags());
            for (String a : at.memberAnnotations()) out.addAll(metaTags(a, types, new LinkedHashSet<>()));
        }
        return out;
    }

    /** {@link #classTags} and {@link #memberTags} together: every tag that selects a test of {@code type}. */
    public static Set<String> tags(Type type, Types types) {
        Set<String> out = classTags(type, types);
        out.addAll(memberTags(type, types));
        return out;
    }

    /** {@code type}, then every superclass and interface of it that can be read, each once. */
    private static List<Type> hierarchy(Type type, Types types) {
        List<Type> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<Type> todo = new ArrayDeque<>(List.of(type));
        while (!todo.isEmpty()) {
            Type at = todo.poll();
            if (!seen.add(at.name())) continue;
            out.add(at);
            for (String s : at.supertypes()) {
                if (TestClassRule.platformType(s)) continue;
                Type sup = types.get(s);
                if (sup != null) todo.add(sup);
            }
        }
        return out;
    }

    /** The tags annotation type {@code type} stands for: those it is meta-annotated with, followed through its own annotations. */
    private static Set<String> metaTags(String type, Types types, Set<String> seen) {
        if (TestClassRule.platformType(type) || !seen.add(type)) return Set.of();
        Type annotation = types.get(type);
        if (annotation == null) return Set.of();
        Set<String> out = new LinkedHashSet<>(annotation.classTags());
        for (String a : annotation.classAnnotations()) out.addAll(metaTags(a, types, seen));
        return out;
    }
}
