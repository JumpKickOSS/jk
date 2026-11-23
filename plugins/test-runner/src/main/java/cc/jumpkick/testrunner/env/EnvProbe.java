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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * JVM and recorder: {@code <pid>.lookups} for variables, {@code <pid>.props} for properties. When
 * the engine set up a broker for the caller's environment there ({@link BrokerLink}), the recorder
 * fetches what this JVM's own environment lacks through it, and {@code java.lang.ProcessBuilder} is
 * repointed the same way at {@link EnvLookups#start}, so a program a test starts sees the caller's
 * environment too. For that the agent lets {@code java.base} read the recorder and opens {@code
 * java.lang} to it ({@link Instrumentation#redefineModule}).
 *
 * <p>With {@link #USES} first in the argument, as the engine asks for a suite it replays by its
 * classes' cones, every class loaded from a directory also gets a call to {@link ClassUses#used} at
 * the start of each method body ({@link EntryMarks}), so the recording names the classes whose code
 * ran in each test class's window. That one rewrite moves instructions; it needs the flight
 * recorder's API and is not asked for under coverage.
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

    /** {@link #REPOINTED} less the property reads, for a JVM whose properties go unprobed. */
    private static final Map<String, String> ENV_ONLY = REPOINTED.entrySet().stream()
            .filter(e -> e.getValue().equals(ENV_RECORDER))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

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

    /**
     * The agent argument's prefix that leaves system-property reads unprobed, as the engine writes
     * it when its class telemetry is off; no absolute path starts so.
     */
    static final String NO_PROPS = "!props ";

    /**
     * The agent argument's prefix, before any other, that has every class loaded from a directory
     * marked so its use is recorded ({@link ClassUses}), as the engine writes it for a suite whose
     * classes it replays by their cones; no absolute path starts so.
     */
    static final String USES = "+uses ";

    /** The agent entry point. */
    public static void premain(@Nullable String args, Instrumentation inst) {
        if (args == null || args.isBlank()) return;
        if (args.startsWith(CHECK)) {
            check(Path.of(args.substring(CHECK.length())), inst);
            return;
        }
        boolean asked = args.startsWith(USES);
        boolean uses = asked && recordable();
        String rest = asked ? args.substring(USES.length()) : args;
        boolean props = !rest.startsWith(NO_PROPS);
        Path dir = Path.of(props ? rest : rest.substring(NO_PROPS.length()));
        long pid = ProcessHandle.current().pid();
        BrokerLink link = BrokerLink.in(dir);
        EnvLookups.deliverThrough(link);
        boolean programs = link != null && openToRecorder(inst) && EnvLookups.prepareStart();
        inst.addTransformer(new Transformer(programs, props, uses), programs);
        // Unhooked, the programs this JVM starts see its own environment: the engine checked this
        // JDK first ({@link #check}) and launches it with the caller's whole environment when it refuses.
        if (programs) hookProcessBuilder(inst);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            EnvLookups.writeTo(dir.resolve(pid + ".lookups"));
                            PropLookups.writeTo(dir.resolve(pid + ".props"));
                        },
                        "jk-env-probe"));
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
            inst.addTransformer(new Transformer(true, false, false), true);
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

    /** Whether this JVM has the flight recorder's API, which a marked class's use is recorded through. */
    private static boolean recordable() {
        try {
            Class.forName("jdk.jfr.Event", false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    private static final class Transformer implements ClassFileTransformer {
        private final boolean programs;
        private final boolean props;
        private final boolean uses;

        Transformer(boolean programs, boolean props, boolean uses) {
            this.programs = programs;
            this.props = props;
            this.uses = uses;
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
            byte[] repointed;
            try {
                repointed = repoint(bytes, props);
            } catch (RuntimeException malformed) {
                repointed = null; // the class loads as compiled
            }
            if (!uses || className == null || !fromDirectory(domain)) return repointed;
            byte[] in = repointed == null ? bytes : repointed;
            String binaryName = className.replace('/', '.');
            byte[] marked = EntryMarks.mark(in, ClassUses.id(binaryName));
            if (marked == null) ClassUses.unmarked(binaryName);
            return marked == null ? repointed : marked;
        }
    }

    /**
     * Whether the class comes from a directory of class files, as a workspace's class trees are:
     * a jar is a library the suite's key holds whole, or a sibling module's, whose classes key by
     * their bytes.
     */
    private static boolean fromDirectory(@Nullable ProtectionDomain domain) {
        if (domain == null || domain.getCodeSource() == null) return false;
        var location = domain.getCodeSource().getLocation();
        return location != null
                && "file".equals(location.getProtocol())
                && location.getPath().endsWith("/");
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
        return repoint(bytes, props ? REPOINTED : ENV_ONLY);
    }

    /**
     * {@code bytes} with each reference to the method {@code owner.name} of one of {@code descs}
     * naming {@link EnvLookups} instead, or {@code null} when the class makes no such reference.
     */
    static byte @Nullable [] repoint(byte[] bytes, String owner, String method, Set<String> descs) {
        Map<String, String> calls = new HashMap<>();
        for (String desc : descs) calls.put(owner + "." + method + desc, ENV_RECORDER);
        return repoint(bytes, calls);
    }

    /**
     * {@code bytes} with each method reference {@code calls} names, {@code owner.name descriptor},
     * repointed at the recorder it maps to, or {@code null} when the class makes no such reference.
     */
    private static byte @Nullable [] repoint(byte[] bytes, Map<String, String> calls) {
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
            String recorder = calls.get(utf8.get(nameIndex) + "." + utf8.get(nat[0]) + utf8.get(nat[1]));
            if (recorder != null)
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
