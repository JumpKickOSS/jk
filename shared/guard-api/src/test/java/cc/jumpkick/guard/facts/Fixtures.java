// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small, fully specified facts for the format and index tests. */
final class Fixtures {
    private Fixtures() {}

    static final int ACC_PUBLIC = 0x0001;
    static final int ACC_STATIC = 0x0008;
    static final int ACC_FINAL = 0x0010;
    static final int ACC_INTERFACE = 0x0200;

    static AnnotationFacts tag(String value) {
        return new AnnotationFacts("Lorg/junit/jupiter/api/Tag;", true, Map.of("value", List.of(value)));
    }

    static AnnotationFacts test() {
        return new AnnotationFacts("Lorg/junit/jupiter/api/Test;", true, Map.of());
    }

    static AnnotationFacts nullMarked() {
        return new AnnotationFacts("Lorg/jspecify/annotations/NullMarked;", false, Map.of());
    }

    static MethodFacts method(String name, String desc) {
        return new MethodFacts(name, desc, ACC_PUBLIC, List.of(), List.of(), List.of(), List.of(), 0, 0);
    }

    static MethodFacts method(String name, String desc, List<AnnotationFacts> annotations) {
        return new MethodFacts(name, desc, ACC_PUBLIC, annotations, List.of(), List.of(), List.of(), 0, 0);
    }

    static MethodFacts method(String name, String desc, List<CallSite> calls, List<FieldRef> refs) {
        return new MethodFacts(name, desc, ACC_PUBLIC, List.of(), List.of(), calls, refs, 2, 11);
    }

    static ClassFacts cls(String internal, List<MethodFacts> methods) {
        return new ClassFacts(
                internal, ACC_PUBLIC, "java/lang/Object", List.of(), null, List.of(), List.of(), methods, Set.of());
    }

    static ClassFacts cls(String internal, List<FieldFacts> fields, List<MethodFacts> methods, Set<String> typeRefs) {
        return new ClassFacts(
                internal,
                ACC_PUBLIC,
                "java/lang/Object",
                List.of(),
                internal.substring(internal.lastIndexOf('/') + 1) + ".java",
                List.of(),
                fields,
                methods,
                typeRefs);
    }

    static ClassFacts packageInfo(String pkgInternal) {
        return new ClassFacts(
                pkgInternal + "/package-info",
                ACC_INTERFACE,
                "java/lang/Object",
                List.of(),
                "package-info.java",
                List.of(nullMarked()),
                List.of(),
                List.of(),
                Set.of());
    }

    /** A hashing class: a constant, a method calling MessageDigest with a literal, a field read. */
    static ClassFacts hashing() {
        FieldFacts algo = new FieldFacts(
                "ALGORITHM", "Ljava/lang/String;", ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "SHA-256", List.of());
        FieldFacts counter = new FieldFacts("calls", "I", ACC_STATIC, null, List.of());
        CallSite digest = new CallSite(
                "java/security/MessageDigest",
                "getInstance",
                "(Ljava/lang/String;)Ljava/security/MessageDigest;",
                31,
                "SHA-256",
                1);
        FieldRef read = new FieldRef("a/b/Hashing", "calls", "I", 30, false, 1);
        FieldRef write = new FieldRef("a/b/Hashing", "calls", "I", 32, true, 1);
        MethodFacts newDigest =
                method("newDigest", "()Ljava/security/MessageDigest;", List.of(digest), List.of(read, write));
        return cls(
                "a/b/Hashing",
                List.of(algo, counter),
                List.of(method("<init>", "()V"), newDigest),
                Set.of("java/lang/Object", "java/security/MessageDigest", "a/c/Util"));
    }

    /** A class in another package of the same index that the hashing class refers to. */
    static ClassFacts util() {
        return cls("a/c/Util", List.of(), List.of(method("<init>", "()V")), Set.of("java/lang/Object"));
    }

    static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of("a/b/Hashing.class", "1200:1"), "seed");
    }
}
