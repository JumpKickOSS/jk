// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Compiled test classes as the affected-tests ranker and the suite filter see them: imported
 * production types, JUnit tags as JUnit resolves them (nested, composed, inherited), naming heuristic. Read from the guard lane's test facts index when it is current, else by
 * one ASM pass over the class files.
 */
public final class TestClassIndex {

    private TestClassIndex() {}

    /**
     * One compiled test class: {@code tags} every JUnit tag on it, its fields and its methods, and
     * {@code classTags} those on the class itself, which every test in it inherits.
     */
    public record Entry(
            String className, Set<String> imports, Set<String> tags, Set<String> classTags, String nameMatchSimple) {}

    public static List<Entry> scan(Path testClassesDir, Set<String> productionFqcs) throws IOException {
        if (testClassesDir == null || !Files.isDirectory(testClassesDir)) return List.of();
        Optional<FactsIndex> facts = currentFacts(testClassesDir);
        if (facts.isPresent()) return fromFacts(facts.get(), productionFqcs);
        return readClassFiles(testClassesDir, productionFqcs);
    }

    /**
     * The guard lane's {@code test-guard.idx} beside {@code target/test-classes}, when its stamps
     * still match the class files: the same facts read once, not a second ASM pass. Absent or stale
     * (no guards, or classes recompiled since), the class files are read directly.
     */
    static Optional<FactsIndex> currentFacts(Path testClassesDir) {
        Path buildDir = testClassesDir.toAbsolutePath().normalize().getParent();
        if (buildDir == null || (buildDir = buildDir.getParent()) == null) return Optional.empty();
        Path idx = FactsIndexing.indexPath(buildDir, "test");
        if (!Files.isRegularFile(idx)) return Optional.empty();
        try {
            if (FactsIndexing.freshDigest(testClassesDir, idx).isEmpty()) return Optional.empty();
            return Optional.of(FactsFormat.read(idx));
        } catch (IOException | RuntimeException stale) {
            return Optional.empty();
        }
    }

    /**
     * One class file as read, before its tags are resolved: its direct {@code @Tag}s and the other
     * annotations on it and on its members, by binary name, so a composed annotation can be followed.
     */
    private record Raw(
            String name,
            @Nullable String superName,
            Set<String> classTags,
            Set<String> classAnnotations,
            Set<String> memberTags,
            Set<String> memberAnnotations,
            Set<String> refs) {}

    /** The entries as the class files would yield them, from the facts index. */
    static List<Entry> fromFacts(FactsIndex facts, Set<String> productionFqcs) {
        List<Raw> raws = new ArrayList<>();
        for (ClassFacts c : facts.classList()) {
            if (c.isPackageInfo()) continue;
            Set<String> refs = new LinkedHashSet<>();
            for (String ref : c.typeRefs()) refs.add(Descriptors.binaryName(ref));
            Set<String> classTags = new LinkedHashSet<>();
            Set<String> classAnnotations = new LinkedHashSet<>();
            annotationsOf(c.annotations(), classTags, classAnnotations);
            Set<String> memberTags = new LinkedHashSet<>();
            Set<String> memberAnnotations = new LinkedHashSet<>();
            for (FieldFacts f : c.fields()) annotationsOf(f.annotations(), memberTags, memberAnnotations);
            for (MethodFacts m : c.methods()) annotationsOf(m.annotations(), memberTags, memberAnnotations);
            raws.add(new Raw(
                    c.binaryName(),
                    c.superName() == null ? null : Descriptors.binaryName(c.superName()),
                    classTags,
                    classAnnotations,
                    memberTags,
                    memberAnnotations,
                    refs));
        }
        return resolve(raws, productionFqcs);
    }

    private static void annotationsOf(List<AnnotationFacts> annotations, Set<String> tags, Set<String> others) {
        for (AnnotationFacts a : annotations) {
            if (a.desc().equals(TAG)) tags.addAll(a.value());
            else others.add(a.typeName());
        }
    }

    private static List<Entry> readClassFiles(Path testClassesDir, Set<String> productionFqcs) throws IOException {
        List<Raw> raws = new ArrayList<>();
        PathUtil.forEachRegularFile(testClassesDir, (p, attrs) -> {
            if (!p.toString().endsWith(".class")) return;
            if (p.getFileName().toString().equals("package-info.class")) return;
            byte[] bytes = Files.readAllBytes(p);
            Collector c = new Collector();
            new ClassReader(bytes).accept(c, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (c.className == null) return;
            raws.add(new Raw(
                    c.className,
                    c.superName,
                    c.classTags,
                    c.classAnnotations,
                    c.memberTags,
                    c.memberAnnotations,
                    c.refs));
        });
        return resolve(raws, productionFqcs);
    }

    /**
     * Each top-level class's entry with its tags as JUnit reads them: a composed annotation counts
     * as the tags it is meta-annotated with, a class inherits its superclass's tags and test
     * methods, and a top-level class holds every tag its nested classes' tests carry. Types outside
     * the tree (a library's composed annotation or base class) are not followed.
     */
    private static List<Entry> resolve(List<Raw> raws, Set<String> productionFqcs) {
        Map<String, Raw> byName = new LinkedHashMap<>();
        for (Raw r : raws) byName.put(r.name(), r);
        Map<String, List<Raw>> nested = new LinkedHashMap<>();
        for (Raw r : raws) {
            int dollar = r.name().indexOf('$');
            if (dollar > 0)
                nested.computeIfAbsent(r.name().substring(0, dollar), k -> new ArrayList<>())
                        .add(r);
        }
        List<Entry> out = new ArrayList<>();
        for (Raw r : raws) {
            if (r.name().indexOf('$') >= 0) continue;
            Set<String> classTags = classTags(r, byName);
            Set<String> tags = new LinkedHashSet<>(classTags);
            tags.addAll(memberTags(r, byName));
            Set<String> refs = new LinkedHashSet<>(r.refs());
            for (Raw inner : nested.getOrDefault(r.name(), List.of())) {
                tags.addAll(classTags(inner, byName));
                tags.addAll(memberTags(inner, byName));
                refs.addAll(inner.refs());
            }
            Set<String> imports = new LinkedHashSet<>();
            for (String ref : refs) {
                if (productionFqcs.contains(ref)) imports.add(ref);
            }
            out.add(new Entry(
                    r.name(), Set.copyOf(imports), Set.copyOf(tags), Set.copyOf(classTags), nameMatchSimple(r.name())));
        }
        return List.copyOf(out);
    }

    /** The tags every test of {@code r} carries: on it, through its composed annotations, and its superclasses'. */
    private static Set<String> classTags(Raw r, Map<String, Raw> byName) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Raw at = r; at != null && seen.add(at.name()); at = superOf(at, byName)) {
            out.addAll(at.classTags());
            for (String a : at.classAnnotations()) out.addAll(metaTags(a, byName, new LinkedHashSet<>()));
        }
        return out;
    }

    /** The tags on {@code r}'s members and its superclasses' members, composed ones followed. */
    private static Set<String> memberTags(Raw r, Map<String, Raw> byName) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Raw at = r; at != null && seen.add(at.name()); at = superOf(at, byName)) {
            out.addAll(at.memberTags());
            for (String a : at.memberAnnotations()) out.addAll(metaTags(a, byName, new LinkedHashSet<>()));
        }
        return out;
    }

    private static @Nullable Raw superOf(Raw r, Map<String, Raw> byName) {
        return r.superName() == null ? null : byName.get(r.superName());
    }

    /** The tags annotation type {@code type} stands for: those it is meta-annotated with, followed through its own annotations. */
    private static Set<String> metaTags(String type, Map<String, Raw> byName, Set<String> seen) {
        Raw annotation = byName.get(type);
        if (annotation == null || !seen.add(type)) return Set.of();
        Set<String> out = new LinkedHashSet<>(annotation.classTags());
        for (String a : annotation.classAnnotations()) out.addAll(metaTags(a, byName, seen));
        return out;
    }

    private static final String TAG = "Lorg/junit/jupiter/api/Tag;";

    /** Production simple name this test class name-matches, or empty. */
    public static String nameMatchSimple(String testFqc) {
        int dot = testFqc.lastIndexOf('.');
        String simple = dot < 0 ? testFqc : testFqc.substring(dot + 1);
        for (String suffix : List.of("TestCase", "Tests", "Test", "ITCase", "IT")) {
            if (simple.length() > suffix.length() && simple.endsWith(suffix)) {
                return simple.substring(0, simple.length() - suffix.length());
            }
        }
        if (simple.endsWith("Kt") && simple.length() > 2) {
            return simple.substring(0, simple.length() - 2);
        }
        return "";
    }

    public static Map<String, Set<String>> productionFqcsBySimple(Set<String> productionFqcs) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String f : productionFqcs) {
            int dot = f.lastIndexOf('.');
            String simple = dot < 0 ? f : f.substring(dot + 1);
            if (simple.endsWith("Kt") && simple.length() > 2) {
                simple = simple.substring(0, simple.length() - 2);
            }
            out.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(f);
        }
        return out;
    }

    private static final class Collector extends ClassVisitor {
        @Nullable
        String className;

        @Nullable
        String superName;

        final Set<String> refs = new LinkedHashSet<>();
        /** The tags on the class itself. */
        final Set<String> classTags = new LinkedHashSet<>();
        /** The class's other annotations, by binary name. */
        final Set<String> classAnnotations = new LinkedHashSet<>();
        /** The tags on its fields and methods. */
        final Set<String> memberTags = new LinkedHashSet<>();
        /** Its fields' and methods' other annotations, by binary name. */
        final Set<String> memberAnnotations = new LinkedHashSet<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = Type.getObjectType(name).getClassName();
            this.superName =
                    superName == null ? null : Type.getObjectType(superName).getClassName();
            addInternal(superName);
            if (interfaces != null) {
                for (String i : interfaces) addInternal(i);
            }
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor, true);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addDesc(descriptor);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                    return annotation(desc, false);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            addDesc(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                    return annotation(desc, false);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addInternal(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String n, String desc) {
                    addInternal(owner);
                    addDesc(desc);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String n, String desc, boolean isInterface) {
                    addInternal(owner);
                    addDesc(desc);
                }
            };
        }

        /** {@code classLevel}: the annotation sits on the class itself, so its tags are inherited by every test. */
        private @Nullable AnnotationVisitor annotation(String descriptor, boolean classLevel) {
            addDesc(descriptor);
            Set<String> tags = classLevel ? classTags : memberTags;
            if (TAG.equals(descriptor)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if (value != null) tags.add(value.toString());
                    }
                };
            }
            if ("Lorg/junit/jupiter/api/Tags;".equals(descriptor)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return this;
                    }

                    @Override
                    public @Nullable AnnotationVisitor visitAnnotation(String name, String desc) {
                        return annotation(desc, classLevel);
                    }
                };
            }
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                (classLevel ? classAnnotations : memberAnnotations)
                        .add(Type.getType(descriptor).getClassName());
            }
            return null;
        }

        private void addInternal(String internal) {
            if (internal == null || internal.isBlank() || internal.startsWith("[")) return;
            refs.add(Type.getObjectType(internal).getClassName());
        }

        private void addDesc(String desc) {
            if (desc == null) return;
            if (desc.startsWith("(") || desc.contains(";")) {
                for (Type t : typesOf(desc)) {
                    if (t.getSort() == Type.OBJECT) refs.add(t.getClassName());
                    else if (t.getSort() == Type.ARRAY && t.getElementType().getSort() == Type.OBJECT) {
                        refs.add(t.getElementType().getClassName());
                    }
                }
            } else if (desc.startsWith("L") && desc.endsWith(";")) {
                refs.add(Type.getType(desc).getClassName());
            }
        }

        private static Type[] typesOf(String desc) {
            try {
                if (desc.startsWith("(")) {
                    Type mt = Type.getMethodType(desc);
                    Type[] args = mt.getArgumentTypes();
                    Type[] all = new Type[args.length + 1];
                    System.arraycopy(args, 0, all, 0, args.length);
                    all[args.length] = mt.getReturnType();
                    return all;
                }
                return new Type[] {Type.getType(desc)};
            } catch (IllegalArgumentException e) {
                return new Type[0];
            }
        }
    }
}
