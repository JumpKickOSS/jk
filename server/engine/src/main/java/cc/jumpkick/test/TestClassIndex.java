// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** ASM scan of compiled test classes: imported production types, JUnit tags, naming heuristic. */
public final class TestClassIndex {

    private TestClassIndex() {}

    public record Entry(String className, Set<String> imports, Set<String> tags, String nameMatchSimple) {}

    public static List<Entry> scan(Path testClassesDir, Set<String> productionFqcs) throws IOException {
        if (testClassesDir == null || !Files.isDirectory(testClassesDir)) return List.of();
        List<Entry> out = new ArrayList<>();
        PathUtil.forEachRegularFile(testClassesDir, (p, attrs) -> {
            if (!p.toString().endsWith(".class")) return;
            String rel = testClassesDir.relativize(p).toString().replace('\\', '/');
            if (rel.contains("$")) return;
            byte[] bytes = Files.readAllBytes(p);
            Collector c = new Collector();
            new ClassReader(bytes).accept(c, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (c.className == null || c.className.contains("$")) return;
            Set<String> imports = new LinkedHashSet<>();
            for (String ref : c.refs) {
                if (productionFqcs.contains(ref)) imports.add(ref);
            }
            out.add(new Entry(c.className, Set.copyOf(imports), Set.copyOf(c.tags), nameMatchSimple(c.className)));
        });
        return List.copyOf(out);
    }

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
        String className;
        final Set<String> refs = new LinkedHashSet<>();
        final Set<String> tags = new LinkedHashSet<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = Type.getObjectType(name).getClassName();
            addInternal(superName);
            if (interfaces != null) {
                for (String i : interfaces) addInternal(i);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addDesc(descriptor);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                    return annotation(desc);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            addDesc(descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                    return annotation(desc);
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

        private AnnotationVisitor annotation(String descriptor) {
            addDesc(descriptor);
            if ("Lorg/junit/jupiter/api/Tag;".equals(descriptor)) {
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
                    public AnnotationVisitor visitAnnotation(String name, String desc) {
                        return annotation(desc);
                    }
                };
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
