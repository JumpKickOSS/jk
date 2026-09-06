// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * One class file → {@link ClassFacts}. Visits method bodies (calls, field refs, the literal loaded
 * right before an invoke, branch counts, line numbers) and every annotation the class file keeps.
 * Measured at ~40 µs per class warm; the whole jk tree is ~100 ms.
 */
public final class FactsExtractor {

    private FactsExtractor() {}

    public static ClassFacts extract(byte[] classBytes) {
        Collector c = new Collector();
        new ClassReader(classBytes).accept(c, ClassReader.SKIP_FRAMES);
        return c.finish();
    }

    private static final class Collector extends ClassVisitor {
        String name = "";
        int access;

        @Nullable
        String superName;

        List<String> interfaces = List.of();

        @Nullable
        String sourceFile;

        final List<AnnotationFacts> annotations = new ArrayList<>();
        final List<FieldFacts> fields = new ArrayList<>();
        final List<MethodFacts> methods = new ArrayList<>();
        final TreeSet<String> refs = new TreeSet<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                @Nullable String sig,
                @Nullable String superName,
                String @Nullable [] interfaces) {
            this.name = name;
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
            if (superName != null) refs.add(superName);
            for (String i : this.interfaces) refs.add(i);
        }

        @Override
        public void visitSource(@Nullable String source, @Nullable String debug) {
            sourceFile = source;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            refType(desc);
            return new AnnotationCollector(desc, visible, annotations::add);
        }

        @Override
        public FieldVisitor visitField(
                int access, String fname, String desc, @Nullable String sig, @Nullable Object value) {
            refType(desc);
            List<AnnotationFacts> fann = new ArrayList<>();
            String constant = value == null ? null : String.valueOf(value);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                    refType(adesc);
                    return new AnnotationCollector(adesc, visible, fann::add);
                }

                @Override
                public void visitEnd() {
                    fields.add(new FieldFacts(fname, desc, access, constant, fann));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String mname, String desc, @Nullable String sig, String @Nullable [] exceptions) {
            refMethodDesc(desc);
            if (exceptions != null) for (String e : exceptions) refs.add(e);
            return new MethodCollector(access, mname, desc);
        }

        ClassFacts finish() {
            refs.remove(name);
            return new ClassFacts(name, access, superName, interfaces, sourceFile, annotations, fields, methods, refs);
        }

        void refType(String desc) {
            Type t = Type.getType(desc);
            while (t.getSort() == Type.ARRAY) t = t.getElementType();
            if (t.getSort() == Type.OBJECT) refs.add(t.getInternalName());
        }

        void refMethodDesc(String desc) {
            for (Type a : Type.getArgumentTypes(desc)) refType(a.getDescriptor());
            refType(Type.getReturnType(desc).getDescriptor());
        }

        void refInternal(String internalName) {
            String n = internalName;
            while (n.startsWith("[")) n = n.substring(1);
            if (n.startsWith("L") && n.endsWith(";")) n = n.substring(1, n.length() - 1);
            if (!n.isEmpty() && n.indexOf(';') < 0 && !isPrimitiveDescriptor(n)) refs.add(n);
        }

        private static boolean isPrimitiveDescriptor(String n) {
            return n.length() == 1 && "VZCBSIFJD".indexOf(n.charAt(0)) >= 0;
        }

        private final class MethodCollector extends MethodVisitor {
            final int access;
            final String mname;
            final String desc;
            final List<AnnotationFacts> mann = new ArrayList<>();
            final Map<Integer, List<AnnotationFacts>> pann = new LinkedHashMap<>();
            final Map<String, CallSite> calls = new LinkedHashMap<>();
            final Map<String, FieldRef> fieldRefs = new LinkedHashMap<>();
            int branches;
            int line;
            int firstLine;

            @Nullable
            String lastLdc;

            MethodCollector(int access, String mname, String desc) {
                super(Opcodes.ASM9);
                this.access = access;
                this.mname = mname;
                this.desc = desc;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                refType(adesc);
                return new AnnotationCollector(adesc, visible, mann::add);
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String adesc, boolean visible) {
                refType(adesc);
                return new AnnotationCollector(
                        adesc, visible, a -> pann.computeIfAbsent(parameter, k -> new ArrayList<>())
                                .add(a));
            }

            @Override
            public void visitLineNumber(int l, Label start) {
                line = l;
                if (firstLine == 0) firstLine = l;
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Type t) refType(t.getDescriptor());
                lastLdc = value instanceof String s ? s : null;
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String mdesc, boolean itf) {
                refInternal(owner);
                refMethodDesc(mdesc);
                String key = owner + '#' + name + mdesc;
                CallSite prior = calls.get(key);
                calls.put(
                        key,
                        prior == null
                                ? new CallSite(owner, name, mdesc, line, lastLdc, 1)
                                : prior.merged(line, lastLdc));
                lastLdc = null;
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String fdesc) {
                refInternal(owner);
                refType(fdesc);
                boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
                String key = owner + '#' + name + (write ? "=" : "");
                FieldRef prior = fieldRefs.get(key);
                fieldRefs.put(
                        key,
                        prior == null
                                ? new FieldRef(owner, name, fdesc, line, write, 1)
                                : new FieldRef(
                                        owner, name, fdesc, Math.min(prior.line(), line), write, prior.count() + 1));
                lastLdc = null;
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                refInternal(type);
                lastLdc = null;
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String idesc, Handle bsm, Object... args) {
                refMethodDesc(idesc);
                for (Object a : args) {
                    if (a instanceof Handle h) {
                        refInternal(h.getOwner());
                        // A method reference or lambda body is a call the rule must still see.
                        String key = h.getOwner() + '#' + h.getName() + h.getDesc();
                        if (!h.getOwner().equals(Collector.this.name)) {
                            CallSite prior = calls.get(key);
                            calls.put(
                                    key,
                                    prior == null
                                            ? new CallSite(h.getOwner(), h.getName(), h.getDesc(), line, null, 1)
                                            : prior.merged(line, null));
                        }
                    } else if (a instanceof Type t) {
                        if (t.getSort() == Type.METHOD) refMethodDesc(t.getDescriptor());
                        else refType(t.getDescriptor());
                    }
                }
                lastLdc = null;
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) branches++;
                lastLdc = null;
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                branches += labels.length;
                lastLdc = null;
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                branches += labels.length;
                lastLdc = null;
            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, @Nullable String type) {
                if (type != null) {
                    branches++;
                    refs.add(type);
                }
            }

            @Override
            public void visitInsn(int opcode) {
                lastLdc = null;
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                lastLdc = null;
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                lastLdc = null;
            }

            @Override
            public void visitMultiANewArrayInsn(String adesc, int dims) {
                refType(adesc);
                lastLdc = null;
            }

            @Override
            public void visitLocalVariable(
                    String vname, String vdesc, @Nullable String sig, Label start, Label end, int index) {
                refType(vdesc);
            }

            @Override
            public void visitEnd() {
                List<List<AnnotationFacts>> params = new ArrayList<>();
                int n = Type.getArgumentTypes(desc).length;
                for (int i = 0; i < n; i++) params.add(pann.getOrDefault(i, List.of()));
                methods.add(new MethodFacts(
                        mname,
                        desc,
                        access,
                        mann,
                        params,
                        new ArrayList<>(calls.values()),
                        new ArrayList<>(fieldRefs.values()),
                        branches,
                        firstLine));
            }
        }
    }

    /** Collects string-shaped attribute values; nested annotations are walked for their refs only. */
    private static final class AnnotationCollector extends AnnotationVisitor {
        private final String desc;
        private final boolean visible;
        private final Consumer<AnnotationFacts> sink;
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        AnnotationCollector(String desc, boolean visible, Consumer<AnnotationFacts> sink) {
            super(Opcodes.ASM9);
            this.desc = desc;
            this.visible = visible;
            this.sink = sink;
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            add(name, value instanceof Type t ? t.getInternalName() : String.valueOf(value));
        }

        @Override
        public void visitEnum(@Nullable String name, String edesc, String value) {
            add(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(@Nullable String name) {
            String key = name == null ? "value" : name;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(@Nullable String n, Object value) {
                    add(key, value instanceof Type t ? t.getInternalName() : String.valueOf(value));
                }

                @Override
                public void visitEnum(@Nullable String n, String edesc, String value) {
                    add(key, value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(@Nullable String n, String ndesc) {
                    // A repeatable's container: each nested annotation is a fact on the element too.
                    return new AnnotationCollector(ndesc, visible, sink);
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(@Nullable String name, String ndesc) {
            return new AnnotationCollector(ndesc, visible, sink);
        }

        @Override
        public void visitEnd() {
            sink.accept(new AnnotationFacts(desc, visible, values));
        }

        private void add(@Nullable String name, String value) {
            values.computeIfAbsent(name == null ? "value" : name, k -> new ArrayList<>())
                    .add(value);
        }
    }
}
