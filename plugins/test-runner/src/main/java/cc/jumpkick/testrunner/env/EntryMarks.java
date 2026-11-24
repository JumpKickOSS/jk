// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A class file with a call to {@link ClassUses#used} put at the start of every method body, so the
 * probe learns which classes' code ran in each test class's window, not only which were loaded in
 * it: a class loads once per JVM, in whichever window first needs it.
 *
 * <p>The call is eight bytes, {@code ldc_w <number>; invokestatic used(I)V; nop; nop}, which keeps
 * every {@code tableswitch}'s padding as compiled. Branches are relative and stay; what names an
 * absolute offset moves with the code: the exception table, the first stack map frame and every
 * uninitialized type a frame names, line numbers and local variable ranges. Type annotations on
 * instructions are dropped: nothing a test runs reads them. A body carrying any other attribute,
 * or too long to grow, is left as compiled, and the class is not marked at all. Seven constants
 * are appended to the pool; nothing else moves, so no bytecode library is needed and any class
 * file version holds.
 */
final class EntryMarks {

    private EntryMarks() {}

    static final String RECORDER = "cc/jumpkick/testrunner/env/ClassUses";

    /** What each method body is prefixed with, past its two constant numbers. */
    private static final int PREFIX = 8;

    /**
     * {@code bytes} with every method body first calling {@link ClassUses#used} with {@code id}, or
     * null when some body cannot hold the call.
     */
    static byte @Nullable [] mark(byte[] bytes, int id) {
        try {
            return new EntryMarks.Rewrite(bytes).mark(id);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static final class Rewrite {
        private final byte[] b;
        private final Map<Integer, String> utf8 = new HashMap<>();
        private int at;

        Rewrite(byte[] b) {
            this.b = b;
        }

        byte @Nullable [] mark(int id) {
            int count = u2(8);
            at = 10;
            for (int i = 1; i < count; i++) {
                int tag = b[at] & 0xff;
                switch (tag) {
                    case 1 -> {
                        int len = u2(at + 1);
                        utf8.put(i, new String(b, at + 3, len, StandardCharsets.UTF_8));
                        at += 3 + len;
                    }
                    case 7, 8, 16, 19, 20 -> at += 3;
                    case 15 -> at += 4;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 5;
                    case 5, 6 -> {
                        at += 9;
                        i++;
                    }
                    default -> throw new IllegalArgumentException("constant pool tag " + tag);
                }
            }
            if (count + 7 > 0xffff) return null;
            int poolEnd = at;
            at += 6;
            at += 2 + 2 * u2(at);
            int fields = u2(at);
            at += 2;
            for (int i = 0; i < fields; i++) skipMember();
            int methodsAt = at;
            int methods = u2(at);
            at += 2;
            ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 64 + methods * PREFIX);
            out.write(b, 0, 8);
            put2(out, count + 7);
            out.write(b, 10, poolEnd - 10);
            int recorderName = count;
            int recorder = count + 1;
            int usedName = count + 2;
            int usedType = count + 3;
            int nameAndType = count + 4;
            int method = count + 5;
            int number = count + 6;
            utf8Entry(out, RECORDER);
            out.write(7);
            put2(out, recorderName);
            utf8Entry(out, "used");
            utf8Entry(out, "(I)V");
            out.write(12);
            put2(out, usedName);
            put2(out, usedType);
            out.write(10);
            put2(out, recorder);
            put2(out, nameAndType);
            out.write(3);
            out.write(id >>> 24);
            out.write(id >>> 16);
            out.write(id >>> 8);
            out.write(id);
            out.write(b, poolEnd, methodsAt + 2 - poolEnd);
            byte[] prefix = {
                0x13, (byte) (number >>> 8), (byte) number, (byte) 0xb8, (byte) (method >>> 8), (byte) method, 0, 0
            };
            for (int i = 0; i < methods; i++) {
                out.write(b, at, 6);
                int attrs = u2(at + 6);
                put2(out, attrs);
                at += 8;
                for (int a = 0; a < attrs; a++) {
                    String name = utf8.get(u2(at));
                    int len = u4(at + 2);
                    if (!"Code".equals(name)) {
                        out.write(b, at, 6 + len);
                    } else {
                        byte[] code = code(at + 6, len, prefix);
                        if (code == null) return null;
                        out.write(b, at, 2);
                        put4(out, code.length);
                        out.write(code, 0, code.length);
                    }
                    at += 6 + len;
                }
            }
            out.write(b, at, b.length - at);
            return out.toByteArray();
        }

        /** A Code attribute's body, from {@code from}, prefixed with {@code prefix}; null when it cannot be. */
        private byte @Nullable [] code(int from, int len, byte[] prefix) {
            int p = from;
            int maxStack = u2(p);
            int codeLength = u4(p + 4);
            if (codeLength + PREFIX > 0xffff) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(len + PREFIX);
            put2(out, Math.max(1, maxStack));
            out.write(b, p + 2, 2);
            put4(out, codeLength + PREFIX);
            out.write(prefix, 0, PREFIX);
            p += 8;
            out.write(b, p, codeLength);
            p += codeLength;
            int handlers = u2(p);
            put2(out, handlers);
            p += 2;
            for (int i = 0; i < handlers; i++, p += 8) {
                put2(out, u2(p) + PREFIX);
                put2(out, u2(p + 2) + PREFIX);
                put2(out, u2(p + 4) + PREFIX);
                out.write(b, p + 6, 2);
            }
            int attrs = u2(p);
            p += 2;
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            int keptCount = 0;
            for (int a = 0; a < attrs; a++) {
                String name = utf8.get(u2(p));
                int alen = u4(p + 2);
                int body = p + 6;
                byte[] moved;
                switch (name == null ? "" : name) {
                    case "LineNumberTable" -> moved = shifted(body, alen, 4);
                    case "LocalVariableTable", "LocalVariableTypeTable" -> moved = shifted(body, alen, 10);
                    case "StackMapTable" -> moved = frames(body);
                    case "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> moved = null;
                    default -> {
                        return null;
                    }
                }
                p = body + alen;
                if (moved == null) continue;
                kept.write(b, body - 6, 2);
                put4(kept, moved.length);
                kept.write(moved, 0, moved.length);
                keptCount++;
            }
            put2(out, keptCount);
            out.writeBytes(kept.toByteArray());
            return out.toByteArray();
        }

        /** A table of {@code size}-byte rows each starting with a code offset, every offset moved. */
        private byte[] shifted(int body, int len, int size) {
            byte[] out = new byte[len];
            System.arraycopy(b, body, out, 0, len);
            int rows = u2(body);
            for (int r = 0; r < rows; r++) {
                int o = 2 + r * size;
                int pc = ((out[o] & 0xff) << 8 | (out[o + 1] & 0xff)) + PREFIX;
                out[o] = (byte) (pc >>> 8);
                out[o + 1] = (byte) pc;
            }
            return out;
        }

        /** A StackMapTable with its first frame's offset and every uninitialized type's moved. */
        private byte[] frames(int body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int n = u2(body);
            put2(out, n);
            int p = body + 2;
            for (int f = 0; f < n; f++) {
                int add = f == 0 ? PREFIX : 0;
                int type = b[p++] & 0xff;
                if (type < 64) {
                    sameFrame(out, type + add);
                } else if (type < 128) {
                    int delta = type - 64 + add;
                    if (delta < 64) out.write(64 + delta);
                    else {
                        out.write(247);
                        put2(out, delta);
                    }
                    p = verification(out, p);
                } else if (type < 247) {
                    throw new IllegalArgumentException("stack map frame type " + type);
                } else {
                    out.write(type);
                    put2(out, u2(p) + add);
                    p += 2;
                    if (type == 247) p = verification(out, p);
                    else if (type >= 252 && type <= 254) {
                        for (int i = 0; i < type - 251; i++) p = verification(out, p);
                    } else if (type == 255) {
                        for (int part = 0; part < 2; part++) {
                            int items = u2(p);
                            put2(out, items);
                            p += 2;
                            for (int i = 0; i < items; i++) p = verification(out, p);
                        }
                    }
                }
            }
            return out.toByteArray();
        }

        private static void sameFrame(ByteArrayOutputStream out, int delta) {
            if (delta < 64) out.write(delta);
            else {
                out.write(251);
                put2(out, delta);
            }
        }

        /** One verification type from {@code p}, written to {@code out}; where the next one starts. */
        private int verification(ByteArrayOutputStream out, int p) {
            int tag = b[p] & 0xff;
            out.write(tag);
            if (tag == 7) {
                out.write(b, p + 1, 2);
                return p + 3;
            }
            if (tag == 8) {
                // Uninitialized: the offset of the `new` that made it.
                put2(out, u2(p + 1) + PREFIX);
                return p + 3;
            }
            if (tag > 8) throw new IllegalArgumentException("verification type " + tag);
            return p + 1;
        }

        private void skipMember() {
            int attrs = u2(at + 6);
            at += 8;
            for (int a = 0; a < attrs; a++) at += 6 + u4(at + 2);
        }

        private int u2(int p) {
            return (b[p] & 0xff) << 8 | (b[p + 1] & 0xff);
        }

        private int u4(int p) {
            return (b[p] & 0xff) << 24 | (b[p + 1] & 0xff) << 16 | (b[p + 2] & 0xff) << 8 | (b[p + 3] & 0xff);
        }
    }

    private static void utf8Entry(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(1);
        put2(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void put2(ByteArrayOutputStream out, int v) {
        out.write(v >>> 8);
        out.write(v);
    }

    private static void put4(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }
}
