// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipException;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The classpath entries whose code groovyc runs while it compiles. A global AST transform is
 * registered in an entry's {@code META-INF/services}; a local one is named by an annotation type
 * meta-annotated {@code @GroovyASTTransformationClass}, and groovyc loads it from the compile
 * classpath. A transform's body is the compile's behaviour, and so is the body of every class it
 * calls, so the entries that hold them key by content and not by their ABI.
 *
 * <p>What a transform reaches is read per entry, not per class: an entry that holds a transform or
 * one of its helpers brings in every entry that holds a class any of its classes names in its
 * constant pool, transitively. Reflection is not followed.
 */
final class GroovyTransforms {

    private static final String MARKER = "Lorg/codehaus/groovy/transform/GroovyASTTransformationClass;";

    /** Memo namespace of {@link #named}, keyed on the entry's content. */
    private static final String NAMED_NAMESPACE = "groovy-local-transforms.1:";

    /** Memo namespace of {@link #referenced}, keyed on the entry's content. */
    private static final String REFERENCED_NAMESPACE = "groovy-referenced.1:";

    /** Memo namespace of {@link #entries}, keyed on every entry's content in classpath order. */
    private static final String ENTRIES_NAMESPACE = "groovy-transform-entries.1:";

    /** What a memo holds for an empty answer. */
    private static final String NONE = "none";

    private GroovyTransforms() {}

    /**
     * The entries of {@code classpath} groovyc runs code from: each that holds a global transform,
     * the first that holds each local transform class, and every entry those reach. A class is
     * found in the first entry that has it, as a class loader finds it. Absolute and normalized.
     */
    static Set<Path> entries(List<Path> classpath) throws IOException {
        List<Path> abs = new ArrayList<>(classpath.size());
        StringBuilder identities = new StringBuilder();
        for (Path entry : classpath) {
            Path a = entry.toAbsolutePath().normalize();
            abs.add(a);
            identities.append(ClasspathFingerprint.entry(a)).append('\n');
        }
        String identity = ENTRIES_NAMESPACE + Hashing.sha256Hex(identities.toString());
        String hit = AbiMemo.get(identity);
        Set<Path> out = new LinkedHashSet<>();
        if (hit != null) {
            if (!hit.equals(NONE)) {
                for (String index : hit.split(",")) out.add(abs.get(Integer.parseInt(index)));
            }
            return out;
        }
        out.addAll(transformHolders(abs));
        reach(abs, out);
        List<String> indexes = new ArrayList<>();
        for (int i = 0; i < abs.size(); i++) {
            if (out.contains(abs.get(i))) indexes.add(Integer.toString(i));
        }
        AbiMemo.put(identity, indexes.isEmpty() ? NONE : String.join(",", indexes));
        return out;
    }

    /** The entries that hold a global transform, and the first that holds each local transform class. */
    private static Set<Path> transformHolders(List<Path> classpath) throws IOException {
        Set<Path> out = new LinkedHashSet<>();
        Set<String> local = new TreeSet<>();
        for (Path entry : classpath) {
            if (ClasspathAbi.hasGlobalTransform(entry)) out.add(entry);
            for (String binaryName : named(entry)) local.add(binaryName.replace('.', '/') + ".class");
        }
        out.addAll(firstHolders(classpath, local));
        return out;
    }

    /**
     * Adds to {@code found} every entry of {@code classpath} that holds a class an entry already in
     * it names, until nothing more is found.
     */
    private static void reach(List<Path> classpath, Set<Path> found) throws IOException {
        Set<String> asked = new HashSet<>();
        Deque<Path> pending = new ArrayDeque<>(found);
        while (!pending.isEmpty()) {
            Set<String> fresh = new HashSet<>();
            for (String file : referenced(pending.pop())) {
                if (!inJdkModule(file) && asked.add(file)) fresh.add(file);
            }
            for (Path holder : firstHolders(classpath, fresh)) {
                if (found.add(holder)) pending.add(holder);
            }
        }
    }

    /**
     * The packages of the JDK modules groovyc runs with, as {@code a/b}: a class loader takes a class
     * in one of them from its module, never from the classpath.
     */
    private static final Set<String> JDK_PACKAGES = Set.copyOf(jdkPackages());

    private static Set<String> jdkPackages() {
        Set<String> out = new HashSet<>();
        for (Module module : ModuleLayer.boot().modules()) {
            String name = module.getName();
            if (!name.startsWith("java.") && !name.startsWith("jdk.")) continue;
            for (String pkg : module.getPackages()) out.add(pkg.replace('.', '/'));
        }
        return out;
    }

    private static boolean inJdkModule(String file) {
        int slash = file.lastIndexOf('/');
        return slash > 0 && JDK_PACKAGES.contains(file.substring(0, slash));
    }

    /** For each of {@code files}, the first entry of {@code classpath} that has it. */
    private static Set<Path> firstHolders(List<Path> classpath, Set<String> files) throws IOException {
        Set<Path> out = new LinkedHashSet<>();
        Set<String> left = new HashSet<>(files);
        for (Path entry : classpath) {
            if (left.isEmpty()) break;
            Set<String> held = present(entry, left);
            if (held.isEmpty()) continue;
            out.add(entry);
            left.removeAll(held);
        }
        return out;
    }

    /** The transform classes the annotation types in {@code entry} name, as binary names. */
    static List<String> named(Path entry) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        BasicFileAttributes attrs = attributes(abs);
        if (attrs == null) return List.of();
        String identity = NAMED_NAMESPACE + ClasspathFingerprint.entry(abs);
        String hit = AbiMemo.get(identity);
        if (hit != null) return hit.equals(NONE) ? List.of() : List.of(hit.split(","));
        Set<String> found = new TreeSet<>();
        ClasspathAbi.forEachClass(abs, attrs, (name, bytes) -> collect(bytes, found));
        AbiMemo.put(identity, found.isEmpty() ? NONE : String.join(",", found));
        return List.copyOf(found);
    }

    /**
     * The class files the classes of {@code entry} name in their constant pools and {@code entry}
     * does not hold itself, as {@code a/b/C.class}; {@code java/} classes, which no classpath can
     * hold, are left out.
     */
    static List<String> referenced(Path entry) throws IOException {
        BasicFileAttributes attrs = attributes(entry);
        if (attrs == null) return List.of();
        String identity = REFERENCED_NAMESPACE + ClasspathFingerprint.entry(entry);
        String hit = AbiMemo.get(identity);
        if (hit != null) return hit.equals(NONE) ? List.of() : List.of(hit.split(","));
        Set<String> defined = new HashSet<>();
        Set<String> named = new TreeSet<>();
        ClasspathAbi.forEachClass(entry, attrs, (name, bytes) -> classesNamed(bytes, defined, named));
        named.removeAll(defined);
        List<String> out = new ArrayList<>(named.size());
        for (String internal : named) out.add(internal + ".class");
        AbiMemo.put(identity, out.isEmpty() ? NONE : String.join(",", out));
        return List.copyOf(out);
    }

    /** Adds the class's own internal name to {@code defined} and every class its constant pool names to {@code named}. */
    private static void classesNamed(byte[] bytes, Set<String> defined, Set<String> named) {
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
            defined.add(reader.getClassName());
            char[] buf = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                if (offset == 0 || reader.readByte(offset - 1) != CONSTANT_CLASS) continue;
                String internal = elementClass(reader.readUTF8(offset, buf));
                if (internal != null && !internal.startsWith("java/")) named.add(internal);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            // Not a class file the JVM would load either.
        }
    }

    /** The JVMS tag of a {@code CONSTANT_Class} entry. */
    private static final int CONSTANT_CLASS = 7;

    /** The class an internal name or array descriptor names; null for an array of primitives. */
    private static @Nullable String elementClass(String name) {
        if (!name.startsWith("[")) return name;
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') dims++;
        if (name.length() < dims + 3 || name.charAt(dims) != 'L' || !name.endsWith(";")) return null;
        return name.substring(dims + 1, name.length() - 1);
    }

    /** Adds what an annotation type's {@code @GroovyASTTransformationClass} names, by string or by class. */
    private static void collect(byte[] bytes, Set<String> into) {
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            return;
        }
        if ((reader.getAccess() & Opcodes.ACC_ANNOTATION) == 0) return;
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        return MARKER.equals(descriptor) ? names(into) : null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
    }

    private static AnnotationVisitor names(Set<String> into) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitArray(String name) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(@Nullable String n, Object value) {
                        if (value instanceof String className && !className.isBlank()) into.add(className.strip());
                        else if (value instanceof Type type) into.add(type.getClassName());
                    }
                };
            }
        };
    }

    /** The files of {@code relative} the tree or archive {@code abs} has. */
    private static Set<String> present(Path abs, Set<String> relative) throws IOException {
        if (Files.isDirectory(abs)) {
            Set<String> out = new HashSet<>();
            for (String file : relative) {
                if (Files.isRegularFile(abs.resolve(file))) out.add(file);
            }
            return out;
        }
        if (!Files.isRegularFile(abs)) return Set.of();
        try (JarClassReader jar = JarClassReader.open(abs)) {
            return jar.present(relative);
        } catch (ZipException notAZip) {
            return Set.of();
        }
    }

    private static @Nullable BasicFileAttributes attributes(Path abs) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(abs, BasicFileAttributes.class);
            return attrs.isRegularFile() || attrs.isDirectory() ? attrs : null;
        } catch (IOException absent) {
            return null;
        }
    }
}
