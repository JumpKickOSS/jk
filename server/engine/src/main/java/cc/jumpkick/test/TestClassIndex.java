// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.guard.extract.FactsExtractor;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
 * production types, JUnit tags as JUnit resolves them (nested, composed, inherited), naming
 * heuristic. Read from the guard lane's test facts index when it is current, else from the class
 * files by the same extractor, so both name the same references.
 */
public final class TestClassIndex {

    private TestClassIndex() {}

    /**
     * One compiled test class: {@code tags} every JUnit tag on it, its fields and its methods, and
     * {@code classTags} those on the class itself, which every test in it inherits. {@code runsTests}
     * is false for a helper or fixture JUnit would find no test in; true when it would, or when a
     * type it depends on could not be read.
     */
    public record Entry(
            String className,
            Set<String> imports,
            Set<String> tags,
            Set<String> classTags,
            String nameMatchSimple,
            boolean runsTests) {}

    /**
     * The classes under {@code testClassesDir}, a type outside the tree (a sibling module's or
     * library's composed annotation, base class or interface) read from {@code classpath}, the one
     * the tests compiled against; empty reads the tree's own types alone.
     */
    public static List<Entry> scan(Path testClassesDir, Set<String> productionFqcs, List<Path> classpath)
            throws IOException {
        if (testClassesDir == null || !Files.isDirectory(testClassesDir)) return List.of();
        Optional<FactsIndex> facts = currentFacts(testClassesDir);
        if (facts.isPresent()) return fromFacts(facts.get(), productionFqcs, classpath);
        return readClassFiles(testClassesDir, productionFqcs, classpath);
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
     * annotations on it and on its members, by binary name, so a composed annotation can be followed;
     * {@code supertypes} its superclass, then its interfaces.
     */
    private record Raw(
            String name,
            int access,
            List<String> supertypes,
            Set<String> classTags,
            Set<String> classAnnotations,
            Set<String> memberTags,
            Set<String> memberAnnotations,
            Set<String> refs) {}

    /** The entries as the class files would yield them, from the facts index. */
    static List<Entry> fromFacts(FactsIndex facts, Set<String> productionFqcs, List<Path> classpath) {
        return fromClassFacts(facts.classList(), productionFqcs, classpath);
    }

    private static List<Entry> fromClassFacts(
            List<ClassFacts> classes, Set<String> productionFqcs, List<Path> classpath) {
        List<Raw> raws = new ArrayList<>();
        for (ClassFacts c : classes) {
            if (c.isPackageInfo()) continue;
            Set<String> refs = new LinkedHashSet<>();
            for (String ref : c.typeRefs()) refs.add(Descriptors.binaryName(ref));
            List<String> supertypes = new ArrayList<>();
            if (c.superName() != null) supertypes.add(Descriptors.binaryName(c.superName()));
            for (String i : c.interfaces()) supertypes.add(Descriptors.binaryName(i));
            Set<String> classTags = new LinkedHashSet<>();
            Set<String> classAnnotations = new LinkedHashSet<>();
            annotationsOf(c.annotations(), classTags, classAnnotations);
            Set<String> memberTags = new LinkedHashSet<>();
            Set<String> memberAnnotations = new LinkedHashSet<>();
            for (FieldFacts f : c.fields()) annotationsOf(f.annotations(), memberTags, memberAnnotations);
            for (MethodFacts m : c.methods()) annotationsOf(m.annotations(), memberTags, memberAnnotations);
            raws.add(new Raw(
                    c.binaryName(),
                    c.access(),
                    supertypes,
                    classTags,
                    classAnnotations,
                    memberTags,
                    memberAnnotations,
                    refs));
        }
        return resolve(raws, productionFqcs, classpath);
    }

    private static void annotationsOf(List<AnnotationFacts> annotations, Set<String> tags, Set<String> others) {
        for (AnnotationFacts a : annotations) {
            // A @Tags container's own @Tag values are facts of the same element.
            if (a.desc().equals(TAG)) tags.addAll(a.value());
            else if (!a.desc().equals(TAGS)) others.add(a.typeName());
        }
    }

    private static List<Entry> readClassFiles(Path testClassesDir, Set<String> productionFqcs, List<Path> classpath)
            throws IOException {
        List<ClassFacts> classes = new ArrayList<>();
        PathUtil.forEachRegularFile(testClassesDir, (p, attrs) -> {
            if (!p.toString().endsWith(".class")) return;
            if (p.getFileName().toString().equals("module-info.class")) return;
            classes.add(FactsExtractor.extract(Files.readAllBytes(p)));
        });
        return fromClassFacts(classes, productionFqcs, classpath);
    }

    /**
     * Each top-level class's entry with its tags as JUnit reads them: a composed annotation counts
     * as the tags it is meta-annotated with, a class inherits the tags and test methods of its
     * superclasses and interfaces, and a top-level class holds every tag its nested classes' tests
     * carry. A type outside the tree (a sibling module's or library's composed annotation, base
     * class or interface) is read from {@code classpath}, the one the tests compiled against.
     */
    private static List<Entry> resolve(List<Raw> raws, Set<String> productionFqcs, List<Path> classpath) {
        Map<String, Raw> byName = new LinkedHashMap<>();
        for (Raw r : raws) byName.put(r.name(), r);
        try (Types types = new Types(byName, classpath)) {
            return entries(raws, productionFqcs, types);
        }
    }

    private static List<Entry> entries(List<Raw> raws, Set<String> productionFqcs, Types types) {
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
            Set<String> classTags = classTags(r, types);
            Set<String> tags = new LinkedHashSet<>(classTags);
            tags.addAll(memberTags(r, types));
            Set<String> refs = new LinkedHashSet<>(r.refs());
            for (Raw inner : nested.getOrDefault(r.name(), List.of())) {
                tags.addAll(classTags(inner, types));
                tags.addAll(memberTags(inner, types));
                refs.addAll(inner.refs());
            }
            Set<String> imports = new LinkedHashSet<>();
            for (String ref : refs) {
                if (productionFqcs.contains(ref)) imports.add(ref);
            }
            out.add(new Entry(
                    r.name(),
                    Set.copyOf(imports),
                    Set.copyOf(tags),
                    Set.copyOf(classTags),
                    nameMatchSimple(r.name()),
                    runsTests(r, nested.getOrDefault(r.name(), List.of()), types)));
        }
        return List.copyOf(out);
    }

    /**
     * Whether the JUnit Platform would find tests in top-level class {@code r}: it is concrete, and
     * it, a supertype or a nested class declares a test method or test annotation, or extends a
     * specification base. The rule the test runner applies to a class discovery dropped.
     */
    private static boolean runsTests(Raw r, List<Raw> nested, Types types) {
        if ((r.access() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION)) != 0) return false;
        if (declaresTests(r, types)) return true;
        for (Raw inner : nested) {
            if (declaresTests(inner, types)) return true;
        }
        return false;
    }

    /**
     * True when {@code r} or a supertype wears a test annotation, on itself or a member, or extends
     * a specification base. A type that cannot be read counts as one that might, unless it is a
     * platform type, so a test is never dropped on a guess.
     */
    private static boolean declaresTests(Raw r, Types types) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<Raw> todo = new ArrayDeque<>(List.of(r));
        while (!todo.isEmpty()) {
            Raw at = todo.poll();
            if (!seen.add(at.name())) continue;
            for (String a : at.classAnnotations()) {
                if (testAnnotation(a, types, new HashSet<>())) return true;
            }
            for (String a : at.memberAnnotations()) {
                if (testAnnotation(a, types, new HashSet<>())) return true;
            }
            for (String s : at.supertypes()) {
                if (TEST_BASES.contains(s) || s.startsWith(KOTEST_SPECS)) return true;
                Raw sup = types.get(s);
                if (sup != null) todo.add(sup);
                else if (!platformType(s)) return true;
            }
        }
        return false;
    }

    /** True when annotation {@code type} is a test annotation or is composed of one. */
    private static boolean testAnnotation(String type, Types types, Set<String> seen) {
        if (TEST_ANNOTATIONS.contains(type)) return true;
        if (!seen.add(type)) return false;
        Raw annotation = types.get(type);
        if (annotation == null) return !platformType(type);
        for (String a : annotation.classAnnotations()) {
            if (testAnnotation(a, types, seen)) return true;
        }
        return false;
    }

    /** A type from a namespace that defines no test annotation or test base beyond the lists here. */
    private static boolean platformType(String name) {
        for (String prefix : PLATFORM_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Annotations that make a class a test class when it, a member or a composed annotation wears one. */
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
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
    private static final Set<String> TEST_BASES = Set.of("spock.lang.Specification", "junit.framework.TestCase");

    /** Kotest's spec styles all live under this package. */
    private static final String KOTEST_SPECS = "io.kotest.core.spec.";

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

    /**
     * The tags every test of {@code r} carries: on it and on its superclasses and interfaces, each
     * directly or through its composed annotations, as JUnit's {@code findRepeatableAnnotations} finds
     * {@code @Tag} on a class.
     */
    private static Set<String> classTags(Raw r, Types types) {
        Set<String> out = new LinkedHashSet<>();
        for (Raw at : hierarchy(r, types)) {
            out.addAll(at.classTags());
            for (String a : at.classAnnotations()) out.addAll(metaTags(a, types, new LinkedHashSet<>()));
        }
        return out;
    }

    /** The tags on the members of {@code r}, its superclasses and interfaces, composed ones followed. */
    private static Set<String> memberTags(Raw r, Types types) {
        Set<String> out = new LinkedHashSet<>();
        for (Raw at : hierarchy(r, types)) {
            out.addAll(at.memberTags());
            for (String a : at.memberAnnotations()) out.addAll(metaTags(a, types, new LinkedHashSet<>()));
        }
        return out;
    }

    /** {@code r}, then every superclass and interface of it that can be read, each once. */
    private static List<Raw> hierarchy(Raw r, Types types) {
        List<Raw> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<Raw> todo = new ArrayDeque<>(List.of(r));
        while (!todo.isEmpty()) {
            Raw at = todo.poll();
            if (!seen.add(at.name())) continue;
            out.add(at);
            for (String s : at.supertypes()) {
                Raw sup = types.get(s);
                if (sup != null) todo.add(sup);
            }
        }
        return out;
    }

    /** The tags annotation type {@code type} stands for: those it is meta-annotated with, followed through its own annotations. */
    private static Set<String> metaTags(String type, Types types, Set<String> seen) {
        if (!seen.add(type)) return Set.of();
        Raw annotation = types.get(type);
        if (annotation == null) return Set.of();
        Set<String> out = new LinkedHashSet<>(annotation.classTags());
        for (String a : annotation.classAnnotations()) out.addAll(metaTags(a, types, seen));
        return out;
    }

    private static final String TAG = "Lorg/junit/jupiter/api/Tag;";
    private static final String TAGS = "Lorg/junit/jupiter/api/Tags;";

    /**
     * The types tags resolve over: the tree's class files, then those of the classpath the tests
     * compiled against, each type read at most once a scan. A JDK type ({@code java.*}) is never
     * read: none carries a tag.
     */
    private static final class Types implements AutoCloseable {
        private final Map<String, Raw> tree;
        private final List<Path> classpath;
        private final Map<String, Optional<Raw>> outside = new HashMap<>();
        private final Map<Path, Optional<ArchiveTypes>> archives = new HashMap<>();
        private final Map<Path, ZipFile> open = new HashMap<>();

        Types(Map<String, Raw> tree, List<Path> classpath) {
            this.tree = tree;
            this.classpath = classpath;
        }

        @Nullable
        Raw get(String name) {
            Raw own = tree.get(name);
            if (own != null || name.startsWith("java.")) return own;
            Optional<Raw> known = outside.get(name);
            if (known == null) {
                known = Optional.ofNullable(find(name));
                outside.put(name, known);
            }
            return known.orElse(null);
        }

        private @Nullable Raw find(String name) {
            String resource = name.replace('.', '/') + ".class";
            for (Path entry : classpath) {
                Raw found = isArchive(entry) ? inArchive(entry, name, resource) : inDirectory(entry, resource);
                if (found != null) return found;
            }
            return null;
        }

        private static boolean isArchive(Path entry) {
            String file = entry.getFileName() == null ? "" : entry.getFileName().toString();
            return file.endsWith(".jar") || file.endsWith(".zip");
        }

        private static @Nullable Raw inDirectory(Path dir, String resource) {
            Path file = dir.resolve(resource);
            try {
                return Files.isRegularFile(file) ? external(Files.readAllBytes(file)) : null;
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        /**
         * {@code name} from archive {@code jar}, memoised across scans while the archive's path, size
         * and mtime hold: a type whose package the archive lacks is answered without opening it.
         */
        private @Nullable Raw inArchive(Path jar, String name, String resource) {
            ArchiveTypes types = archive(jar);
            if (types == null) return null;
            int slash = resource.lastIndexOf('/');
            if (!types.packages().contains(slash < 0 ? "" : resource.substring(0, slash))) return null;
            Optional<Raw> hit = types.read().get(name);
            if (hit == null) {
                hit = Optional.ofNullable(readArchive(jar, resource));
                types.read().put(name, hit);
            }
            return hit.orElse(null);
        }

        private @Nullable ArchiveTypes archive(Path jar) {
            Optional<ArchiveTypes> known = archives.get(jar);
            if (known != null) return known.orElse(null);
            ArchiveTypes types = null;
            try {
                BasicFileAttributes attrs = Files.readAttributes(jar, BasicFileAttributes.class);
                if (attrs.isRegularFile()) {
                    ArchiveStamp stamp = new ArchiveStamp(
                            attrs.size(), attrs.lastModifiedTime().toMillis());
                    types = ARCHIVE_TYPES.get(jar, stamp, () -> packagesOf(jar));
                }
            } catch (IOException | RuntimeException unreadable) {
                types = null;
            }
            archives.put(jar, Optional.ofNullable(types));
            return types;
        }

        private @Nullable ArchiveTypes packagesOf(Path jar) {
            ZipFile zip = zip(jar);
            if (zip == null) return null;
            Set<String> packages = new HashSet<>();
            zip.stream().forEach(e -> {
                String entry = e.getName();
                if (!entry.endsWith(".class")) return;
                int slash = entry.lastIndexOf('/');
                packages.add(slash < 0 ? "" : entry.substring(0, slash));
            });
            return new ArchiveTypes(Set.copyOf(packages), new ConcurrentHashMap<>());
        }

        private @Nullable Raw readArchive(Path jar, String resource) {
            ZipFile zip = zip(jar);
            if (zip == null) return null;
            try {
                ZipEntry entry = zip.getEntry(resource);
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return external(in.readAllBytes());
                }
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
        }

        /** {@code jar} opened once a scan; null when it cannot be read. */
        private @Nullable ZipFile zip(Path jar) {
            ZipFile zip = open.get(jar);
            if (zip != null) return zip;
            try {
                zip = new ZipFile(jar.toFile());
            } catch (IOException | RuntimeException unreadable) {
                return null;
            }
            open.put(jar, zip);
            return zip;
        }

        /** A class file outside the tree, read for its tags and supertypes alone. */
        private static @Nullable Raw external(byte[] bytes) {
            Collector c = new Collector();
            new ClassReader(bytes).accept(c, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return c.className == null ? null : c.raw(c.className);
        }

        @Override
        public void close() {
            for (ZipFile zip : open.values()) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Read-only: nothing is lost.
                }
            }
        }
    }

    /** An archive as last seen: its size and mtime. */
    private record ArchiveStamp(long size, long modifiedMillis) {}

    /**
     * What one archive holds for tag resolution: the packages of its classes, and the types of those
     * packages read so far by binary name ({@code Optional.empty()} for one it does not hold).
     */
    private record ArchiveTypes(Set<String> packages, ConcurrentHashMap<String, Optional<Raw>> read) {}

    /**
     * Each classpath archive's {@link ArchiveTypes}, by path, while its size and mtime hold: every
     * run-tests reads the same libraries' annotations. At most 512 archives; past that the memo
     * clears and the next scans read again.
     */
    private static final StampedMemo<Path, ArchiveStamp, @Nullable ArchiveTypes> ARCHIVE_TYPES =
            StampedMemo.bounded(512);

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

        int access;

        /** Its superclass, then its interfaces, by binary name. */
        final List<String> supertypes = new ArrayList<>();

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

        /** The class as read, named {@code name}; its references are not read. */
        Raw raw(String name) {
            return new Raw(
                    name,
                    access,
                    List.copyOf(supertypes),
                    classTags,
                    classAnnotations,
                    memberTags,
                    memberAnnotations,
                    Set.of());
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = Type.getObjectType(name).getClassName();
            this.access = access;
            if (superName != null) supertypes.add(Type.getObjectType(superName).getClassName());
            if (interfaces != null) {
                for (String i : interfaces) supertypes.add(Type.getObjectType(i).getClassName());
            }
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor, true);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
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
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                    return annotation(desc, false);
                }
            };
        }

        /** {@code classLevel}: the annotation sits on the class itself, so its tags are inherited by every test. */
        private @Nullable AnnotationVisitor annotation(String descriptor, boolean classLevel) {
            Set<String> tags = classLevel ? classTags : memberTags;
            if (TAG.equals(descriptor)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if (value != null) tags.add(value.toString());
                    }
                };
            }
            if (TAGS.equals(descriptor)) {
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
    }
}
