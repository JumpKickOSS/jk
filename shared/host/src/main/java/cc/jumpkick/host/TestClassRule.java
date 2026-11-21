// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether the JUnit Platform would find tests in a class, judged from class-file facts without
 * loading it: a concrete class that, a supertype or a nested class of which wears a test
 * annotation (Jupiter's, JUnit 4's, TestNG's, or a project annotation composed of one) on itself
 * or a member, or that extends a specification base (Spock's, Kotest's, JUnit 3's {@code
 * TestCase}). The test runner, the engine's test index and the guard facts all ask this one rule.
 * Names are binary names ({@code a.b.Outer$Inner}).
 */
public final class TestClassRule {

    private TestClassRule() {}

    /**
     * One class file as the rule reads it: {@code supertypes} its superclass, then its interfaces;
     * {@code classAnnotations} the annotation types on the class itself; {@code memberAnnotations}
     * those on its fields and methods.
     */
    public record Type(
            String name,
            int access,
            List<String> supertypes,
            Collection<String> classAnnotations,
            Collection<String> memberAnnotations) {}

    /** The types beyond the one judged: supertypes and annotations, by binary name. */
    @FunctionalInterface
    public interface Types {
        /** The type named {@code binaryName}, or {@code null} when it cannot be read. */
        @Nullable
        Type get(String binaryName);
    }

    /** Annotations that make a class a test class when it, a member or a composed annotation wears one. */
    public static final Set<String> TEST_ANNOTATIONS = Set.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate",
            "org.junit.jupiter.api.ClassTemplate",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.params.ParameterizedClass",
            "org.junit.platform.commons.annotation.Testable",
            "org.junit.platform.suite.api.Suite",
            "org.junit.Test",
            "org.junit.runner.RunWith",
            "org.testng.annotations.Test");

    /** Supertypes whose concrete subclasses are specifications with no test annotation of their own. */
    public static final Set<String> TEST_BASES = Set.of("spock.lang.Specification", "junit.framework.TestCase");

    /** Kotest's spec styles all live under this package. */
    public static final String KOTEST_SPECS = "io.kotest.core.spec.";

    /** Namespaces that define no test annotation or test base beyond the lists here. */
    private static final List<String> PLATFORM_PACKAGES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "kotlin.",
            "groovy.",
            "scala.",
            "org.junit.",
            "org.jetbrains.annotations.",
            "org.jspecify.");

    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_ANNOTATION = 0x2000;

    /**
     * True when the Platform would find tests in {@code type}: it is concrete, and it, a supertype
     * or one of {@code nested} (the classes declared inside it, at any depth) declares tests. A type
     * {@link Types} cannot read outside the platform namespaces counts as declaring tests when
     * {@code unknownDeclares}: a caller that must never drop a test guesses yes, one that must never
     * judge a helper guesses no.
     */
    public static boolean runsTests(Type type, Collection<Type> nested, Types types, boolean unknownDeclares) {
        if ((type.access() & (ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION)) != 0) return false;
        if (declaresTests(type, types, unknownDeclares)) return true;
        for (Type inner : nested) {
            if (declaresTests(inner, types, unknownDeclares)) return true;
        }
        return false;
    }

    /** True when {@code type} or a supertype wears a test annotation, on itself or a member, or extends a specification base. */
    static boolean declaresTests(Type type, Types types, boolean unknownDeclares) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<Type> todo = new ArrayDeque<>(List.of(type));
        while (!todo.isEmpty()) {
            Type at = todo.poll();
            if (!seen.add(at.name())) continue;
            for (String a : at.classAnnotations()) {
                if (testAnnotation(a, types, unknownDeclares, new HashSet<>())) return true;
            }
            for (String a : at.memberAnnotations()) {
                if (testAnnotation(a, types, unknownDeclares, new HashSet<>())) return true;
            }
            for (String s : at.supertypes()) {
                if (TEST_BASES.contains(s) || s.startsWith(KOTEST_SPECS)) return true;
                if (platformType(s)) continue;
                Type sup = types.get(s);
                if (sup != null) todo.add(sup);
                else if (unknownDeclares) return true;
            }
        }
        return false;
    }

    /** True when {@code annotation} is a test annotation or an annotation type composed of one. */
    private static boolean testAnnotation(String annotation, Types types, boolean unknownDeclares, Set<String> seen) {
        if (TEST_ANNOTATIONS.contains(annotation)) return true;
        if (platformType(annotation) || !seen.add(annotation)) return false;
        Type type = types.get(annotation);
        if (type == null) return unknownDeclares;
        if ((type.access() & ACC_ANNOTATION) == 0) return false;
        for (String a : type.classAnnotations()) {
            if (testAnnotation(a, types, unknownDeclares, seen)) return true;
        }
        return false;
    }

    /** A type from a namespace that defines no test annotation or test base beyond the lists here. */
    public static boolean platformType(String binaryName) {
        for (String prefix : PLATFORM_PACKAGES) {
            if (binaryName.startsWith(prefix)) return true;
        }
        return false;
    }
}
