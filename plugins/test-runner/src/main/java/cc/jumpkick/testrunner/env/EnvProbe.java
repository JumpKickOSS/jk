// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.io.ByteArrayOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The agent a test JVM starts with when jk records the environment variables its suite looks up.
 *
 * <p>Every class a class loader other than the JDK's defines has its {@code System.getenv}
 * references repointed at {@link EnvLookups}. The rewrite touches the constant pool alone: two
 * entries are appended (the recorder's name and a class entry for it) and each method reference to
 * {@code java/lang/System.getenv} names that class instead. No instruction, stack map or index
 * moves, so it holds for any class file version, needs no bytecode library on the suite's class
 * path and leaves a class without such a call untouched.
 *
 * <p>The agent argument is the directory the recorder writes to when the JVM exits, one file per
 * JVM.
 */
public final class EnvProbe {

    private static final String SYSTEM = "java/lang/System";
    private static final String RECORDER = "cc/jumpkick/testrunner/env/EnvLookups";
    private static final String OWN_PACKAGE = "cc/jumpkick/testrunner/";

    private EnvProbe() {}

    /** The agent entry point. */
    public static void premain(@Nullable String args, Instrumentation inst) {
        if (args == null || args.isBlank()) return;
        Path out = Path.of(args).resolve(ProcessHandle.current().pid() + ".lookups");
        inst.addTransformer(new Transformer());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> EnvLookups.writeTo(out), "jk-env-probe"));
    }

    private static final class Transformer implements ClassFileTransformer {
        @Override
        public byte @Nullable [] transform(
                @Nullable ClassLoader loader,
                @Nullable String className,
                @Nullable Class<?> redefined,
                @Nullable ProtectionDomain domain,
                byte[] bytes) {
            if (loader == null || loader == ClassLoader.getPlatformClassLoader()) return null;
            if (className != null && className.startsWith(OWN_PACKAGE)) return null;
            if (fromJdk(domain)) return null;
            try {
                return repoint(bytes);
            } catch (RuntimeException malformed) {
                return null; // the class loads as compiled
            }
        }
    }

    /** A JDK module the application loader defines ({@code jdk.compiler}, …): its code source is a {@code jrt:} URL. */
    private static boolean fromJdk(@Nullable ProtectionDomain domain) {
        if (domain == null || domain.getCodeSource() == null) return false;
        var location = domain.getCodeSource().getLocation();
        return location != null && "jrt".equals(location.getProtocol());
    }

    /**
     * {@code bytes} with each {@code System.getenv} method reference naming {@link EnvLookups}, or
     * {@code null} when the class makes no such reference.
     */
    static byte @Nullable [] repoint(byte[] bytes) {
        int count = u2(bytes, 8);
        Map<Integer, String> utf8 = new HashMap<>();
        Map<Integer, Integer> classNames = new HashMap<>();
        Map<Integer, int[]> nameAndTypes = new HashMap<>();
        List<int[]> methodRefs = new ArrayList<>(); // {offset of class index, class index, name-and-type index}
        int at = 10;
        for (int i = 1; i < count; i++) {
            int tag = bytes[at] & 0xff;
            switch (tag) {
                case 1 -> {
                    int len = u2(bytes, at + 1);
                    utf8.put(i, new String(bytes, at + 3, len, StandardCharsets.UTF_8));
                    at += 3 + len;
                }
                case 7 -> {
                    classNames.put(i, u2(bytes, at + 1));
                    at += 3;
                }
                case 8, 16, 19, 20 -> at += 3;
                case 15 -> at += 4;
                case 3, 4, 9, 11, 17, 18 -> at += 5;
                case 10 -> {
                    methodRefs.add(new int[] {at + 1, u2(bytes, at + 1), u2(bytes, at + 3)});
                    at += 5;
                }
                case 12 -> {
                    nameAndTypes.put(i, new int[] {u2(bytes, at + 1), u2(bytes, at + 3)});
                    at += 5;
                }
                case 5, 6 -> {
                    at += 9;
                    i++;
                }
                default -> throw new IllegalArgumentException("constant pool tag " + tag);
            }
        }
        List<Integer> patch = new ArrayList<>();
        for (int[] ref : methodRefs) {
            Integer nameIndex = classNames.get(ref[1]);
            int[] nat = nameAndTypes.get(ref[2]);
            if (nameIndex == null || nat == null || !SYSTEM.equals(utf8.get(nameIndex))) continue;
            String desc = utf8.get(nat[1]);
            if ("getenv".equals(utf8.get(nat[0]))
                    && ("(Ljava/lang/String;)Ljava/lang/String;".equals(desc) || "()Ljava/util/Map;".equals(desc))) {
                patch.add(ref[0]);
            }
        }
        if (patch.isEmpty() || count + 2 > 0xffff) return null;
        int recorderName = count;
        int recorderClass = count + 1;
        byte[] name = RECORDER.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + name.length + 8);
        out.write(bytes, 0, 8);
        writeU2(out, count + 2);
        byte[] pool = Arrays.copyOfRange(bytes, 10, at);
        for (int offset : patch) {
            pool[offset - 10] = (byte) (recorderClass >>> 8);
            pool[offset - 9] = (byte) recorderClass;
        }
        out.write(pool, 0, pool.length);
        out.write(1);
        writeU2(out, name.length);
        out.write(name, 0, name.length);
        out.write(7);
        writeU2(out, recorderName);
        out.write(bytes, at, bytes.length - at);
        return out.toByteArray();
    }

    private static int u2(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    private static void writeU2(ByteArrayOutputStream out, int v) {
        out.write(v >>> 8);
        out.write(v);
    }
}
