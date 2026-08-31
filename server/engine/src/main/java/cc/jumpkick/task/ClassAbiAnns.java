// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Canonical annotation / default-value lines for {@link ClassAbi}. */
final class ClassAbiAnns {

    private ClassAbiAnns() {}

    static AnnotationVisitor defaults(List<String> sink) {
        StringBuilder sb = new StringBuilder("DEF");
        return new ValueCollector(sb) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                sink.add(sb.toString());
            }
        };
    }

    static AnnotationVisitor line(String descriptor, boolean visible, List<String> sink) {
        return line(descriptor, visible, sink, null);
    }

    static AnnotationVisitor line(String descriptor, boolean visible, List<String> sink, String prefix) {
        if (ClassAbi.KOTLIN_METADATA.equals(descriptor)) return null;
        StringBuilder sb = new StringBuilder();
        if (prefix != null) sb.append(prefix).append(' ');
        sb.append("A ").append(visible ? "v " : "i ").append(descriptor);
        return new ValueCollector(sb) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                sink.add(sb.toString());
            }
        };
    }

    static String renderValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Type t) return t.getDescriptor();
        if (value instanceof String s) return "\"" + s + "\"";
        if (value.getClass().isArray()) {
            int n = Array.getLength(value);
            String[] parts = new String[n];
            for (int i = 0; i < n; i++) parts[i] = renderValue(Array.get(value, i));
            Arrays.sort(parts);
            return "[" + String.join(",", parts) + "]";
        }
        return String.valueOf(value);
    }

    private static String nameOrValue(String name) {
        return name == null ? "value" : name;
    }

    private static class ValueCollector extends AnnotationVisitor {
        private final StringBuilder sb;
        private final List<String> values = new ArrayList<>();

        ValueCollector(StringBuilder sb) {
            super(Opcodes.ASM9);
            this.sb = sb;
        }

        @Override
        public void visit(String name, Object value) {
            values.add(nameOrValue(name) + "=" + renderValue(value));
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            values.add(nameOrValue(name) + "=E " + descriptor + " " + value);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            if (ClassAbi.KOTLIN_METADATA.equals(descriptor)) return null;
            StringBuilder inner = new StringBuilder("@").append(descriptor);
            return new ValueCollector(inner) {
                @Override
                public void visitEnd() {
                    super.visitEnd();
                    values.add(nameOrValue(name) + "=" + inner);
                }
            };
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            List<String> inner = new ArrayList<>();
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String n, Object value) {
                    inner.add(renderValue(value));
                }

                @Override
                public void visitEnum(String n, String descriptor, String value) {
                    inner.add("E " + descriptor + " " + value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String n, String descriptor) {
                    if (ClassAbi.KOTLIN_METADATA.equals(descriptor)) return null;
                    StringBuilder nested = new StringBuilder("@").append(descriptor);
                    return new ValueCollector(nested) {
                        @Override
                        public void visitEnd() {
                            super.visitEnd();
                            inner.add(nested.toString());
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    Collections.sort(inner);
                    values.add(nameOrValue(name) + "=[" + String.join(",", inner) + "]");
                }
            };
        }

        @Override
        public void visitEnd() {
            Collections.sort(values);
            for (String v : values) sb.append(' ').append(v);
        }
    }
}
