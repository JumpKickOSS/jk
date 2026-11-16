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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The agent a test JVM starts with when jk records the environment variables and system
 * properties its suite looks up.
 *
 * <p>Every class a class loader other than the JDK's defines has its {@code System.getenv}
 * references repointed at {@link EnvLookups}, and its {@code System.getProperty}, {@code
 * System.getProperties}, {@code Integer.getInteger}, {@code Long.getLong} and {@code
 * Boolean.getBoolean} references at {@link PropLookups}. The rewrite touches the constant pool
 * alone: two entries are appended per recorder used (its name and a class entry for it) and each
 * such method reference names that class instead. No instruction, stack map or index moves, so it
 * holds for any class file version, needs no bytecode library on the suite's class path and leaves
 * a class without such a call untouched.
 *
 * <p>The agent argument is the directory the recorders write to when the JVM exits, one file per
 * JVM and recorder: {@code <pid>.lookups} for variables, {@code <pid>.props} for properties.
 */
public final class EnvProbe {

    private static final String ENV_RECORDER = "cc/jumpkick/testrunner/env/EnvLookups";
    private static final String PROP_RECORDER = "cc/jumpkick/testrunner/env/PropLookups";

    /** Each call repointed, {@code owner.name descriptor}, and the recorder it is repointed at. */
    private static final Map<String, String> REPOINTED = Map.ofEntries(
            Map.entry("java/lang/System.getenv(Ljava/lang/String;)Ljava/lang/String;", ENV_RECORDER),
            Map.entry("java/lang/System.getenv()Ljava/util/Map;", ENV_RECORDER),
            Map.entry("java/lang/System.getProperty(Ljava/lang/String;)Ljava/lang/String;", PROP_RECORDER),
            Map.entry(
                    "java/lang/System.getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    PROP_RECORDER),
            Map.entry("java/lang/System.getProperties()Ljava/util/Properties;", PROP_RECORDER),
            Map.entry("java/lang/Integer.getInteger(Ljava/lang/String;)Ljava/lang/Integer;", PROP_RECORDER),
            Map.entry("java/lang/Integer.getInteger(Ljava/lang/String;I)Ljava/lang/Integer;", PROP_RECORDER),
            Map.entry(
                    "java/lang/Integer.getInteger(Ljava/lang/String;Ljava/lang/Integer;)Ljava/lang/Integer;",
                    PROP_RECORDER),
            Map.entry("java/lang/Long.getLong(Ljava/lang/String;)Ljava/lang/Long;", PROP_RECORDER),
            Map.entry("java/lang/Long.getLong(Ljava/lang/String;J)Ljava/lang/Long;", PROP_RECORDER),
            Map.entry("java/lang/Long.getLong(Ljava/lang/String;Ljava/lang/Long;)Ljava/lang/Long;", PROP_RECORDER),
            Map.entry("java/lang/Boolean.getBoolean(Ljava/lang/String;)Z", PROP_RECORDER));

    private static final String OWN_PACKAGE = "cc/jumpkick/testrunner/";

    private EnvProbe() {}

    /**
     * The agent argument's prefix that leaves system-property reads unprobed, as the engine writes
     * it when its class telemetry is off; no absolute path starts so.
     */
    static final String NO_PROPS = "!props ";

    /** The agent entry point. */
    public static void premain(@Nullable String args, Instrumentation inst) {
        if (args == null || args.isBlank()) return;
        boolean props = !args.startsWith(NO_PROPS);
        Path dir = Path.of(props ? args : args.substring(NO_PROPS.length()));
        long pid = ProcessHandle.current().pid();
        inst.addTransformer(new Transformer(props));
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            EnvLookups.writeTo(dir.resolve(pid + ".lookups"));
                            PropLookups.writeTo(dir.resolve(pid + ".props"));
                        },
                        "jk-env-probe"));
    }

    private static final class Transformer implements ClassFileTransformer {
        private final boolean props;

        Transformer(boolean props) {
            this.props = props;
        }

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
                return repoint(bytes, props);
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
     * {@code bytes} with each method reference {@link #REPOINTED} names repointed at its recorder,
     * or {@code null} when the class makes no such reference.
     */
    static byte @Nullable [] repoint(byte[] bytes) {
        return repoint(bytes, true);
    }

    /** As {@link #repoint(byte[])}, leaving system-property reads as compiled unless {@code props}. */
    static byte @Nullable [] repoint(byte[] bytes, boolean props) {
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
        // Offset of each class index to patch, by the recorder it is to name.
        Map<String, List<Integer>> patch = new LinkedHashMap<>();
        for (int[] ref : methodRefs) {
            Integer nameIndex = classNames.get(ref[1]);
            int[] nat = nameAndTypes.get(ref[2]);
            if (nameIndex == null || nat == null) continue;
            String recorder = REPOINTED.get(utf8.get(nameIndex) + "." + utf8.get(nat[0]) + utf8.get(nat[1]));
            if (recorder != null && (props || recorder.equals(ENV_RECORDER)))
                patch.computeIfAbsent(recorder, r -> new ArrayList<>()).add(ref[0]);
        }
        if (patch.isEmpty() || count + 2 * patch.size() > 0xffff) return null;
        ByteArrayOutputStream appended = new ByteArrayOutputStream();
        byte[] pool = Arrays.copyOfRange(bytes, 10, at);
        int next = count;
        for (Map.Entry<String, List<Integer>> e : patch.entrySet()) {
            int recorderName = next++;
            int recorderClass = next++;
            for (int offset : e.getValue()) {
                pool[offset - 10] = (byte) (recorderClass >>> 8);
                pool[offset - 9] = (byte) recorderClass;
            }
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            appended.write(1);
            writeU2(appended, name.length);
            appended.write(name, 0, name.length);
            appended.write(7);
            writeU2(appended, recorderName);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + appended.size());
        out.write(bytes, 0, 8);
        writeU2(out, next);
        out.write(pool, 0, pool.length);
        out.writeBytes(appended.toByteArray());
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
