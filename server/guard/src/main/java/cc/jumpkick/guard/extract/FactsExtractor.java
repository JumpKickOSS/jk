// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * One class file → {@link ClassFacts}. Visits method bodies (calls, field refs, the literal loaded
 * right before an invoke, branch counts, line numbers) and every annotation the class file keeps.
 * Type references include the type arguments generic signatures keep ({@code List<Rule>} refers to
 * {@code Rule}) and record components. Measured at ~40 µs per class warm; the whole jk tree is
 * ~100 ms.
 */
public final class FactsExtractor {

    /** A boxing method's descriptor: one primitive in, its box out ({@code Integer.valueOf(int)}). */
    private static final Pattern BOXING = Pattern.compile("\\([ZBCSIJFD]\\)Ljava/lang/\\w+;");

    private static final String STRING_CONCAT = "java/lang/invoke/StringConcatFactory";

    /** A concatenation recipe's argument and constant markers. */
    private static final char ARG = '\u0001';

    private static final char CONST = '\u0002';

    private FactsExtractor() {}

    public static ClassFacts extract(byte[] classBytes) {
        Collector c = new Collector();
        new ClassReader(classBytes).accept(c, ClassReader.SKIP_FRAMES);
        return c.finish();
    }

    /**
     * A digest of this extractor's own class files, every nest member included. A cached index
     * records it, so an index another extractor wrote reads as stale instead of silently lacking
     * what this one finds.
     */
    public static String identity() {
        return Identity.DIGEST;
    }

    private static final class Identity {
        static final String DIGEST = digest();

        private static String digest() {
            Class<?>[] nest = FactsExtractor.class.getNestMembers();
            Arrays.sort(nest, Comparator.comparing(Class::getName));
            MessageDigest md = Hashing.newSha256();
            for (Class<?> c : nest) {
                String resource = c.getName().replace('.', '/') + ".class";
                try (InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
                    if (in == null) throw new IllegalStateException("no class file for " + c.getName());
                    md.update(c.getName().getBytes(StandardCharsets.UTF_8));
                    md.update(in.readAllBytes());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return Hashing.hex(md.digest());
        }
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

        /** Static field → the last call the static initializer made before assigning it. */
        final Map<String, String> initializers = new LinkedHashMap<>();

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
            refSignature(sig, false);
        }

        @Override
        public void visitSource(@Nullable String source, @Nullable String debug) {
            sourceFile = source;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            refType(desc);
            return new AnnotationCollector(desc, visible, annotations::add, this::refType);
        }

        @Override
        public FieldVisitor visitField(
                int access, String fname, String desc, @Nullable String sig, @Nullable Object value) {
            refType(desc);
            refSignature(sig, true);
            List<AnnotationFacts> fann = new ArrayList<>();
            String constant = value == null ? null : String.valueOf(value);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                    refType(adesc);
                    return new AnnotationCollector(adesc, visible, fann::add, Collector.this::refType);
                }

                @Override
                public void visitEnd() {
                    fields.add(new FieldFacts(fname, desc, access, constant, fann, null));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String mname, String desc, @Nullable String sig, String @Nullable [] exceptions) {
            refMethodDesc(desc);
            refSignature(sig, false);
            if (exceptions != null) for (String e : exceptions) refs.add(e);
            return new MethodCollector(access, mname, desc);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String cname, String desc, @Nullable String sig) {
            refType(desc);
            refSignature(sig, true);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                    refType(adesc);
                    return new AnnotationCollector(adesc, visible, a -> {}, Collector.this::refType);
                }
            };
        }

        ClassFacts finish() {
            refs.remove(name);
            if (!initializers.isEmpty()) {
                // Fields are visited before methods, so the initializer joins them here.
                fields.replaceAll(f -> {
                    String init = initializers.get(f.name());
                    return init == null
                            ? f
                            : new FieldFacts(f.name(), f.desc(), f.access(), f.constantValue(), f.annotations(), init);
                });
            }
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

        /** Every class a signature names: a field's or local's when {@code type}, else a class's or method's. */
        void refSignature(@Nullable String sig, boolean type) {
            if (sig == null) return;
            SignatureReader reader = new SignatureReader(sig);
            if (type) reader.acceptType(new SignatureRefs());
            else reader.accept(new SignatureRefs());
        }

        /** One class type of a signature, its type arguments each read by their own visitor. */
        private final class SignatureRefs extends SignatureVisitor {
            private String current = "";

            SignatureRefs() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visitClassType(String name) {
                current = name;
                refs.add(name);
            }

            @Override
            public void visitInnerClassType(String name) {
                current = current + '$' + name;
                refs.add(current);
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                return new SignatureRefs();
            }
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

            /** In the static initializer, the last call made since the last field access. */
            @Nullable
            String lastCall;

            /**
             * String constants loaded since the last invoke, field write or allocation other than a
             * varargs array's: the argument window. Where control does not fall through (a return,
             * a throw, a {@code goto}, a switch) the window ends; at a label it is the windows of the
             * jumps to that label joined with the one falling into it, so {@code f(c ? "a" : "b")}
             * keeps both and a literal returned on one branch is no argument on the other.
             */
            final List<String> window = new ArrayList<>();

            /** The window at each forward jump not yet reached, by the label it lands on. */
            final Map<Label, List<String>> jumpWindows = new LinkedHashMap<>();

            MethodCollector(int access, String mname, String desc) {
                super(Opcodes.ASM9);
                this.access = access;
                this.mname = mname;
                this.desc = desc;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                refType(adesc);
                return new AnnotationCollector(adesc, visible, mann::add, Collector.this::refType);
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String adesc, boolean visible) {
                refType(adesc);
                return new AnnotationCollector(
                        adesc,
                        visible,
                        a -> pann.computeIfAbsent(parameter, k -> new ArrayList<>())
                                .add(a),
                        Collector.this::refType);
            }

            @Override
            public void visitLabel(Label label) {
                List<String> joined = jumpWindows.remove(label);
                if (joined != null) for (String s : joined) if (!window.contains(s)) window.add(s);
            }

            /** The window as control leaves for {@code target}; joined back in when the label is reached. */
            private void jumpTo(Label target) {
                if (window.isEmpty()) return;
                List<String> at = jumpWindows.computeIfAbsent(target, k -> new ArrayList<>());
                for (String s : window) if (!at.contains(s)) at.add(s);
            }

            /** Control does not fall through to the next instruction. */
            private void endWindow() {
                window.clear();
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
                if (lastLdc != null) window.add(lastLdc);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String mdesc, boolean itf) {
                refInternal(owner);
                refMethodDesc(mdesc);
                String key = owner + '#' + name + mdesc;
                // Boxing a primitive argument evaluates an argument of the call that follows: the window is that
                // call's.
                boolean boxing = name.equals("valueOf")
                        && owner.startsWith("java/lang/")
                        && BOXING.matcher(mdesc).matches();
                List<String> args = boxing ? List.of() : window;
                CallSite prior = calls.get(key);
                calls.put(
                        key,
                        prior == null
                                ? CallSite.first(owner, name, mdesc, line, lastLdc, args)
                                : prior.merged(line, lastLdc, args));
                lastLdc = null;
                if (!boxing) window.clear();
                lastCall = owner + '#' + name;
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String fdesc) {
                refInternal(owner);
                refType(fdesc);
                if (opcode == Opcodes.PUTSTATIC && mname.equals("<clinit>") && owner.equals(Collector.this.name)) {
                    if (lastCall == null) initializers.remove(name);
                    else initializers.put(name, lastCall);
                }
                lastCall = null;
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
                // Reading a field evaluates an argument (get(URL, this.owner.getId())): the window stays. A write
                // ends a statement.
                if (write) window.clear();
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                refInternal(type);
                lastLdc = null;
                // A varargs call allocates its array after its leading arguments: they stay in the window.
                if (opcode != Opcodes.ANEWARRAY) window.clear();
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String idesc, Handle bsm, Object... args) {
                refMethodDesc(idesc);
                lastCall = null;
                for (Object a : args) {
                    if (a instanceof Handle h) {
                        refInternal(h.getOwner());
                        // A method reference or lambda body is a call the rule must still see. A
                        // lambda body of this class is not: its calls are already its method's.
                        // A reference to one of this class's own methods is (this::helper).
                        String key = h.getOwner() + '#' + h.getName() + h.getDesc();
                        if (!h.getOwner().equals(Collector.this.name) || !lambdaBody(h.getName())) {
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
                if (bsm.getOwner().equals(STRING_CONCAT)) concatConstants(args);
                lastLdc = null;
            }

            /**
             * A string concatenation's constant parts join the window: {@code get("/orders/" + id)}
             * carries {@code /orders/}. The recipe marks each argument with U+0001 and each bootstrap
             * constant with U+0002.
             */
            private void concatConstants(Object[] args) {
                if (args.length == 0 || !(args[0] instanceof String recipe)) return;
                int constant = 1;
                StringBuilder part = new StringBuilder();
                for (int i = 0; i <= recipe.length(); i++) {
                    char ch = i < recipe.length() ? recipe.charAt(i) : ARG;
                    if (ch == CONST && constant < args.length) {
                        part.append(args[constant++]);
                    } else if (ch == ARG) {
                        if (!part.isEmpty() && !window.contains(part.toString())) window.add(part.toString());
                        part.setLength(0);
                    } else {
                        part.append(ch);
                    }
                }
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) branches++;
                lastLdc = null;
                jumpTo(label);
                if (opcode == Opcodes.GOTO) endWindow();
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                branches += labels.length;
                lastLdc = null;
                switchTo(dflt, labels);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                branches += labels.length;
                lastLdc = null;
                switchTo(dflt, labels);
            }

            private void switchTo(Label dflt, Label[] labels) {
                jumpTo(dflt);
                for (Label l : labels) jumpTo(l);
                endWindow();
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
                if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) endWindow();
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
                refSignature(sig, true);
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

    /** A synthetic method a compiler made of a lambda body: javac's {@code lambda$m$0}, Kotlin's {@code m$lambda$0}. */
    static boolean lambdaBody(String method) {
        return method.startsWith("lambda$") || method.contains("$lambda");
    }

    /** Collects string-shaped attribute values; nested annotations are walked for their refs only. */
    private static final class AnnotationCollector extends AnnotationVisitor {
        private final String desc;
        private final boolean visible;
        private final Consumer<AnnotationFacts> sink;
        private final Consumer<String> refType;
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        AnnotationCollector(String desc, boolean visible, Consumer<AnnotationFacts> sink, Consumer<String> refType) {
            super(Opcodes.ASM9);
            this.desc = desc;
            this.visible = visible;
            this.sink = sink;
            this.refType = refType;
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (value instanceof Type t) refType.accept(t.getDescriptor());
            if (value.getClass().isArray()) {
                // ASM hands a primitive array value (int[] mv(), Kotlin's @Metadata) over whole, and
                // its toString is an identity hash, different in every JVM: record it element-wise.
                for (int i = 0, n = Array.getLength(value); i < n; i++) add(name, String.valueOf(Array.get(value, i)));
                return;
            }
            add(name, value instanceof Type t ? t.getInternalName() : String.valueOf(value));
        }

        @Override
        public void visitEnum(@Nullable String name, String edesc, String value) {
            refType.accept(edesc);
            add(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(@Nullable String name) {
            String key = name == null ? "value" : name;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(@Nullable String n, Object value) {
                    if (value instanceof Type t) refType.accept(t.getDescriptor());
                    add(key, value instanceof Type t ? t.getInternalName() : String.valueOf(value));
                }

                @Override
                public void visitEnum(@Nullable String n, String edesc, String value) {
                    refType.accept(edesc);
                    add(key, value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(@Nullable String n, String ndesc) {
                    refType.accept(ndesc);
                    // A repeatable's container: each nested annotation is a fact on the element too.
                    return new AnnotationCollector(ndesc, visible, sink, refType);
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(@Nullable String name, String ndesc) {
            refType.accept(ndesc);
            return new AnnotationCollector(ndesc, visible, sink, refType);
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
