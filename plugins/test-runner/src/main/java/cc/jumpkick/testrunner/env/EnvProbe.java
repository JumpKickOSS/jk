// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner.env;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * JVM. When the engine set up a broker for the caller's environment there ({@link BrokerLink}),
 * the recorder fetches what this JVM's own environment lacks through it, and {@code
 * java.lang.ProcessBuilder} is repointed the same way at {@link EnvLookups#start}, so a program a
 * test starts sees the caller's environment too. For that the agent lets {@code java.base} read
 * the recorder and opens {@code java.lang} to it ({@link Instrumentation#redefineModule}).
 */
public final class EnvProbe {

    private static final String SYSTEM = "java/lang/System";
    private static final String RECORDER = "cc/jumpkick/testrunner/env/EnvLookups";
    private static final String OWN_PACKAGE = "cc/jumpkick/testrunner/";
    private static final String PROCESS_BUILDER = "java/lang/ProcessBuilder";
    static final String PROCESS_IMPL = "java/lang/ProcessImpl";
    static final String START_DESC =
            "([Ljava/lang/String;Ljava/util/Map;Ljava/lang/String;[Ljava/lang/ProcessBuilder$Redirect;Z)Ljava/lang/Process;";

    /**
     * The agent argument that asks only whether this JDK lets the probe hand the caller's
     * environment on to the programs a test starts: {@code check:<file>} writes {@link #HOOKED}, or
     * {@code unhooked: <why>}, to the file. The engine runs it once per JDK before it relies on the
     * hook, and gives a JVM on a JDK that refuses it the caller's whole environment instead.
     */
    public static final String CHECK = "check:";

    /** What a check writes when the hook takes. */
    public static final String HOOKED = "hooked";

    /** Whether {@code java.lang.ProcessBuilder} was repointed at {@link EnvLookups#start}. */
    private static volatile boolean hooked;

    private EnvProbe() {}

    /** The agent entry point. */
    public static void premain(@Nullable String args, Instrumentation inst) {
        if (args == null || args.isBlank()) return;
        if (args.startsWith(CHECK)) {
            check(Path.of(args.substring(CHECK.length())), inst);
            return;
        }
        Path dir = Path.of(args);
        Path out = dir.resolve(ProcessHandle.current().pid() + ".lookups");
        BrokerLink link = BrokerLink.in(dir);
        EnvLookups.deliverThrough(link);
        boolean programs = link != null && openToRecorder(inst) && EnvLookups.prepareStart();
        inst.addTransformer(new Transformer(programs), programs);
        // Unhooked, the programs this JVM starts see its own environment: the engine checked this
        // JDK first ({@link #check}) and launches it with the caller's whole environment when it refuses.
        if (programs) hookProcessBuilder(inst);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> EnvLookups.writeTo(out), "jk-env-probe"));
    }

    /** The check's JVM runs nothing past the agent: {@code java -javaagent:<probe>=check:<file> -cp <probe> EnvProbe}. */
    public static void main(String[] args) {}

    /** Whether this JDK lets the probe repoint {@code ProcessBuilder}, written to {@code out}. */
    private static void check(Path out, Instrumentation inst) {
        String verdict;
        if (!openToRecorder(inst)) verdict = "unhooked: java.base does not open java.lang to an agent";
        else if (!EnvLookups.prepareStart())
            verdict = "unhooked: java.lang.ProcessImpl.start is not the method the probe calls";
        else {
            inst.addTransformer(new Transformer(true), true);
            verdict = hookProcessBuilder(inst)
                    ? HOOKED
                    : "unhooked: java.lang.ProcessBuilder does not start programs through ProcessImpl.start "
                            + START_DESC;
        }
        try {
            Files.writeString(out, verdict);
        } catch (IOException | RuntimeException unwritten) {
            // no verdict reads as a refusal
        }
    }

    /**
     * Repoint {@code ProcessBuilder} at {@link EnvLookups#start}: retransformed when already loaded,
     * else as it loads now. Whether its bytes held the call the probe repoints: a JDK that starts
     * programs another way leaves it as compiled.
     */
    static boolean hookProcessBuilder(Instrumentation inst) {
        try {
            boolean loaded = false;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (c.getClassLoader() == null && c.getName().equals("java.lang.ProcessBuilder")) loaded = true;
            }
            if (loaded) inst.retransformClasses(Class.forName("java.lang.ProcessBuilder"));
            else Class.forName("java.lang.ProcessBuilder");
        } catch (Exception | LinkageError refused) {
            return false;
        }
        return hooked;
    }

    /** Let {@code java.base} reach the recorder, and the recorder reach the JDK's process launch. */
    private static boolean openToRecorder(Instrumentation inst) {
        try {
            Module recorder = EnvLookups.class.getModule();
            Module base = Object.class.getModule();
            if (!inst.isModifiableModule(base)) return false;
            inst.redefineModule(
                    base, Set.of(recorder), Map.of(), Map.of("java.lang", Set.of(recorder)), Set.of(), Map.of());
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    private static final class Transformer implements ClassFileTransformer {
        private final boolean programs;

        Transformer(boolean programs) {
            this.programs = programs;
        }

        @Override
        public byte @Nullable [] transform(
                @Nullable ClassLoader loader,
                @Nullable String className,
                @Nullable Class<?> redefined,
                @Nullable ProtectionDomain domain,
                byte[] bytes) {
            if (programs && loader == null && PROCESS_BUILDER.equals(className)) {
                try {
                    byte[] hooks = repoint(bytes, PROCESS_IMPL, "start", Set.of(START_DESC));
                    if (hooks != null) hooked = true;
                    return hooks;
                } catch (RuntimeException malformed) {
                    return null;
                }
            }
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
        return repoint(bytes, SYSTEM, "getenv", Set.of("(Ljava/lang/String;)Ljava/lang/String;", "()Ljava/util/Map;"));
    }

    /**
     * {@code bytes} with each reference to the method {@code owner.name} of one of {@code descs}
     * naming {@link EnvLookups} instead, or {@code null} when the class makes no such reference.
     */
    static byte @Nullable [] repoint(byte[] bytes, String owner, String method, Set<String> descs) {
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
            if (nameIndex == null || nat == null || !owner.equals(utf8.get(nameIndex))) continue;
            String desc = utf8.get(nat[1]);
            if (method.equals(utf8.get(nat[0])) && desc != null && descs.contains(desc)) patch.add(ref[0]);
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
