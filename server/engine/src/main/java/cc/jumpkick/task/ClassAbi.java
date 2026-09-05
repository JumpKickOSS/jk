// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;

/**
 * Language-neutral ABI fingerprint of a JVM class file. Gradle {@code ApiMemberSelector} semantics
 * (not Gradle's compiler): public/protected members, generic {@code Signature}, {@code throws},
 * {@code ConstantValue} of {@code static final} fields, API annotations with values, record
 * components, permitted subclasses, {@code module-info}. Method bodies, private members, debug, and
 * {@code kotlin.Metadata} stay out. Named nested classes fold into their owner's fingerprint
 * ({@link #of(byte[], SortedMap)}) for affected-test ranking.
 */
public final class ClassAbi {

    static final String KOTLIN_METADATA = "Lkotlin/Metadata;";

    private ClassAbi() {}

    public record Fingerprint(String apiHex) {}

    public static Fingerprint of(byte[] classBytes) {
        return new Fingerprint(Hashing.sha256Hex(String.join("\n", apiLines(classBytes))));
    }

    /**
     * Fingerprint of an owner class plus its named nested classes' API surfaces, keyed by nested
     * class file name (sorted, so order is stable). Anonymous/local classes ({@code Foo$1}) are the
     * caller's to exclude — a body edit that adds one must stay BODY.
     */
    public static Fingerprint of(byte[] ownerBytes, SortedMap<String, byte[]> nestedByName) {
        List<String> lines = new ArrayList<>(apiLines(ownerBytes));
        for (var e : nestedByName.entrySet()) {
            lines.add("$ " + e.getKey());
            lines.addAll(apiLines(e.getValue()));
        }
        return new Fingerprint(Hashing.sha256Hex(String.join("\n", lines)));
    }

    static List<String> apiLines(byte[] classBytes) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            ApiCollector api = new ApiCollector();
            cr.accept(api, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return api.finish();
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            return List.of("RAW " + Hashing.sha256Hex(classBytes));
        }
    }

    public enum Kind {
        ABI,
        BODY
    }

    /**
     * Compare a dirty FQC's current bytes to the pre-compile fingerprint. Matching hashes still
     * yield {@link Kind#BODY} so a git-dirty source name-matches.
     */
    public static Kind classify(@Nullable Fingerprint previous, Fingerprint current) {
        if (previous == null) return Kind.ABI;
        if (!previous.apiHex().equals(current.apiHex())) return Kind.ABI;
        return Kind.BODY;
    }

    private static final class ApiCollector extends ClassVisitor {
        private final List<String> header = new ArrayList<>();
        private final List<String> ifaces = new ArrayList<>();
        private final List<String> permitted = new ArrayList<>();
        private final List<String> inners = new ArrayList<>();
        private final List<String> records = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();
        private final List<String> methods = new ArrayList<>();
        private final List<String> anns = new ArrayList<>();
        private final List<String> module = new ArrayList<>();
        private @Nullable String nestHost;

        ApiCollector() {
            super(Opcodes.ASM9);
        }

        List<String> finish() {
            List<String> out = new ArrayList<>(header);
            Collections.sort(ifaces);
            out.addAll(ifaces);
            if (nestHost != null) out.add("N " + nestHost);
            Collections.sort(permitted);
            out.addAll(permitted);
            Collections.sort(inners);
            out.addAll(inners);
            Collections.sort(records);
            out.addAll(records);
            Collections.sort(anns);
            out.addAll(anns);
            Collections.sort(fields);
            out.addAll(fields);
            Collections.sort(methods);
            out.addAll(methods);
            out.addAll(module);
            return out;
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            StringBuilder sb = new StringBuilder("C ")
                    .append(access)
                    .append(' ')
                    .append(name)
                    .append(' ')
                    .append(nullToEmpty(superName));
            if (signature != null) sb.append(" S ").append(signature);
            header.add(sb.toString());
            if (interfaces != null) {
                for (String i : interfaces) ifaces.add("I " + i);
            }
        }

        @Override
        public void visitNestHost(String nestHost) {
            this.nestHost = nestHost;
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            permitted.add("P " + permittedSubclass);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (outerName == null || innerName == null || isPrivate(access)) return;
            inners.add("INNER " + access + " " + name + " " + outerName + " " + innerName);
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return ClassAbiAnns.line(descriptor, visible, anns);
        }

        @Override
        public @Nullable FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            if (!isApi(access)) return null;
            StringBuilder sb = new StringBuilder("F ")
                    .append(access)
                    .append(' ')
                    .append(name)
                    .append(' ')
                    .append(descriptor);
            if (signature != null) sb.append(" S ").append(signature);
            if (value != null && isStaticFinal(access)) sb.append(" V ").append(ClassAbiAnns.renderValue(value));
            List<String> fieldAnns = new ArrayList<>();
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return ClassAbiAnns.line(desc, visible, fieldAnns);
                }

                @Override
                public void visitEnd() {
                    Collections.sort(fieldAnns);
                    for (String a : fieldAnns) sb.append(' ').append(a);
                    fields.add(sb.toString());
                }
            };
        }

        @Override
        public @Nullable MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if ("<clinit>".equals(name)) return null;
            if (!isApi(access)) return null;
            StringBuilder sb = new StringBuilder("M ")
                    .append(access)
                    .append(' ')
                    .append(name)
                    .append(' ')
                    .append(descriptor);
            if (signature != null) sb.append(" S ").append(signature);
            if (exceptions != null && exceptions.length > 0) {
                List<String> ex = new ArrayList<>(List.of(exceptions));
                Collections.sort(ex);
                sb.append(" T ").append(String.join(",", ex));
            }
            List<String> methodAnns = new ArrayList<>();
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return ClassAbiAnns.line(desc, visible, methodAnns);
                }

                @Override
                public @Nullable AnnotationVisitor visitParameterAnnotation(
                        int parameter, String desc, boolean visible) {
                    return ClassAbiAnns.line(desc, visible, methodAnns, "P" + parameter);
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return ClassAbiAnns.defaults(methodAnns);
                }

                @Override
                public void visitEnd() {
                    Collections.sort(methodAnns);
                    for (String a : methodAnns) sb.append(' ').append(a);
                    methods.add(sb.toString());
                }
            };
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            StringBuilder sb = new StringBuilder("R ").append(name).append(' ').append(descriptor);
            if (signature != null) sb.append(" S ").append(signature);
            List<String> recAnns = new ArrayList<>();
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return ClassAbiAnns.line(desc, visible, recAnns);
                }

                @Override
                public void visitEnd() {
                    Collections.sort(recAnns);
                    for (String a : recAnns) sb.append(' ').append(a);
                    records.add(sb.toString());
                }
            };
        }

        @Override
        public ModuleVisitor visitModule(String name, int access, String version) {
            module.add("MOD " + access + " " + name + " " + nullToEmpty(version));
            List<String> parts = new ArrayList<>();
            return new ModuleVisitor(Opcodes.ASM9) {
                @Override
                public void visitMainClass(String mainClass) {
                    parts.add("MAIN " + mainClass);
                }

                @Override
                public void visitPackage(String packaze) {
                    parts.add("PKG " + packaze);
                }

                @Override
                public void visitRequire(String module, int acc, String ver) {
                    parts.add("REQ " + acc + " " + module + " " + nullToEmpty(ver));
                }

                @Override
                public void visitExport(String packaze, int acc, String... modules) {
                    parts.add("EXP " + acc + " " + packaze + " " + sortedJoin(modules));
                }

                @Override
                public void visitOpen(String packaze, int acc, String... modules) {
                    parts.add("OPEN " + acc + " " + packaze + " " + sortedJoin(modules));
                }

                @Override
                public void visitUse(String service) {
                    parts.add("USE " + service);
                }

                @Override
                public void visitProvide(String service, String... providers) {
                    parts.add("PROV " + service + " " + sortedJoin(providers));
                }

                @Override
                public void visitEnd() {
                    Collections.sort(parts);
                    module.addAll(parts);
                }
            };
        }
    }

    private static boolean isApi(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    private static boolean isPrivate(int access) {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    private static boolean isStaticFinal(int access) {
        return (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sortedJoin(String[] items) {
        if (items == null || items.length == 0) return "";
        List<String> copy = new ArrayList<>(List.of(items));
        Collections.sort(copy);
        return String.join(",", copy);
    }
}
