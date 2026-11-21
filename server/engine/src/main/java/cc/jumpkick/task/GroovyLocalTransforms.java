// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
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
 * The classpath entries that hold a local Groovy AST transform. An annotation type meta-annotated
 * {@code @GroovyASTTransformationClass} names the classes groovyc runs over every node that carries
 * it, and groovyc loads them from the compile classpath: a transform's body is the compile's
 * behaviour, as a global one's is, so the entry it loads from keys by content and not by its ABI.
 */
final class GroovyLocalTransforms {

    private static final String MARKER = "Lorg/codehaus/groovy/transform/GroovyASTTransformationClass;";

    /** Memo namespace of {@link #named}, keyed on the entry's content. */
    private static final String NAMESPACE = "groovy-local-transforms.1:";

    /** What {@link #named} memoizes for an entry that names no transform. */
    private static final String NONE = "none";

    private GroovyLocalTransforms() {}

    /**
     * The entries of {@code classpath} that groovyc loads a local transform class from: for each
     * class any entry's annotation names, the first entry that has it, as a class loader finds it.
     * Absolute and normalized.
     */
    static Set<Path> holders(List<Path> classpath) throws IOException {
        Set<String> classes = new TreeSet<>();
        for (Path entry : classpath) classes.addAll(named(entry));
        Set<Path> out = new HashSet<>();
        if (classes.isEmpty()) return out;
        for (String binaryName : classes) {
            String file = binaryName.replace('.', '/') + ".class";
            for (Path entry : classpath) {
                Path abs = entry.toAbsolutePath().normalize();
                if (holds(abs, file)) {
                    out.add(abs);
                    break;
                }
            }
        }
        return out;
    }

    /** The transform classes the annotation types in {@code entry} name, as binary names. */
    static List<String> named(Path entry) throws IOException {
        Path abs = entry.toAbsolutePath().normalize();
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        } catch (IOException absent) {
            return List.of();
        }
        if (!attrs.isRegularFile() && !attrs.isDirectory()) return List.of();
        String identity = NAMESPACE + ClasspathFingerprint.entry(abs);
        String hit = AbiMemo.get(identity);
        if (hit != null) return hit.equals(NONE) ? List.of() : List.of(hit.split(","));
        Set<String> found = new TreeSet<>();
        ClasspathAbi.forEachClass(abs, attrs, (name, bytes) -> collect(bytes, found));
        AbiMemo.put(identity, found.isEmpty() ? NONE : String.join(",", found));
        return List.copyOf(found);
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

    /** Whether the tree or archive {@code abs} has the file {@code relative}. */
    private static boolean holds(Path abs, String relative) throws IOException {
        if (Files.isDirectory(abs)) return Files.isRegularFile(abs.resolve(relative));
        if (!Files.isRegularFile(abs)) return false;
        try (JarClassReader jar = JarClassReader.open(abs)) {
            return jar.entry(relative) != null;
        } catch (ZipException notAZip) {
            return false;
        }
    }
}
