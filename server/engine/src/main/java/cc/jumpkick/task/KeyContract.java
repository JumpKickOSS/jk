// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.BuildIdentity;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What the engine's own code contributes to an action key. A key names the program that executes
 * its action by content (the compiler worker, the test runner, a plugin's jar, GraalVM) beside
 * every input it reads, and never the engine's jar: an engine reinstalled with a change no action
 * reaches replays every compile, suite, package and lane. What the engine runs itself to produce
 * or judge a result is a contract instead: each constant names the sources of that code and
 * records their digest, the digest folds into the keys of the contract's actions ({@link
 * #keyVersion()}), and the {@code key-contract-recorded} guard is red until the recorded digest is
 * the sources' digest. So a change to that code moves exactly its actions' keys, by an edit the
 * guard prints, and a change anywhere else in the engine moves none.
 *
 * <p>The digest reads code, not prose: comments and whitespace are left out, so rewording a
 * Javadoc or reformatting a file records nothing new.
 */
public enum KeyContract {

    /**
     * The code that picks which test classes a suite runs and builds the JVM that runs them: the
     * package {@code cc.jumpkick.test} and every class of the engine whose own name carries {@code
     * Test}, {@code Suite} or {@code JUnit} ({@link #suiteCode}). A run-tests stamp keys by it. The
     * code that judges a suite's outcome, decides which suites run, which of a suite's reads count as
     * its inputs and which failures a retry answers is not here: it lives in the package {@code cc.jumpkick.orchestrator}, which the stamp keys by its compiled classes ({@code
     * OrchestratorCode}). Nor is the code that writes the stamp and its discovered-read tokens
     * ({@link #STAMP}): what it changes reaches a suite only through the key it writes; nor the code
     * that sizes a suite JVM's heap ({@link #HEAP}), which no key names. The {@code suite-code-keyed} guard
     * keeps the types that select and judge a suite inside the one or the other.
     */
    SUITE("4a2164985c23", List.of(Sources.SERVER, Sources.SHARED), KeyContract::suiteSource),

    /**
     * The code that writes an artifact or a verdict the engine produces itself: the jar, assembly
     * and war packagers and the zip they write through, the sources and javadoc jars, the war,
     * jlink, lib-dir and image steps, plugin steps' staging, build-logic scripts, Node.js steps and
     * guard lanes (the guard runtime runs in the engine). Every {@code ActionKey.forArtifact} key
     * but a native image's keys by it.
     */
    ARTIFACT(
            "7bac486d0176",
            List.of(
                    Sources.ENGINE + "compile/*Packager.java",
                    Sources.ENGINE + "runtime/PlannerJlink.java",
                    Sources.ENGINE + "runtime/PlannerLibDir.java",
                    Sources.ENGINE + "runtime/PlannerPlugin.java",
                    Sources.ENGINE + "runtime/PlannerTails.java",
                    Sources.ENGINE + "runtime/PlannerWar.java",
                    Sources.ENGINE + "runtime/BuildLogic*.java",
                    Sources.ENGINE + "runtime/Guard*.java",
                    Sources.ENGINE + "runtime/Node*.java",
                    Sources.ENGINE + "runtime/Plugin*.java",
                    Sources.ENGINE + "runtime/workspace/Image*.java",
                    "shared/host/src/main/java/cc/jumpkick/host/ClassPathJar.java",
                    "shared/host/src/main/java/cc/jumpkick/host/DeterministicZip.java",
                    "shared/client-io/src/main/java/cc/jumpkick/cache/JavadocJar.java",
                    "shared/client-io/src/main/java/cc/jumpkick/cache/SourcesJar.java",
                    "server/guard/src/main/java/**/*.java"),
            path -> true),

    /**
     * The code that runs native-image: {@code NativeImageDriver}, which spells the command line
     * around the args. GraalVM, the classpath, the args, the main class and the metadata are keyed
     * by content; a native image's key keys this code apart from {@link #ARTIFACT}, so a packaging
     * edit does not link every image again.
     */
    NATIVE(
            "1b58447316c8",
            List.of("shared/toolchain-jdk/src/main/java/cc/jumpkick/tool/NativeImageDriver.java"),
            path -> true);

    /** Source roots the constants name, workspace-relative. */
    private static final class Sources {
        static final String ENGINE = "server/engine/src/main/java/cc/jumpkick/";
        static final String SERVER = "server/*/src/main/java/**/*.java";
        static final String SHARED = "shared/*/src/main/java/**/*.java";
    }

    private static final String MAIN = "/src/main/java/";
    private static final String ROOT = "cc/jumpkick/";
    private static final String PACKAGE = ROOT + "test/";

    /** The package of the code that judges a suite, keyed by its classes and never by this contract. */
    private static final String ORCHESTRATOR = ROOT + "orchestrator/";

    private static final String CLASS = ".class";

    /**
     * The classes that write a run-tests stamp's tokens: every input they read reaches the suite's
     * JVM as a token of the key they write, so an edit to them moves the key by what it changes, or
     * not at all. {@code TestStampInputs} writes the stamp; {@code SuiteReads} reads the suite's
     * recordings and keeps and reads back what they found, the {@code read:}, {@code list:} and
     * {@code proc:} tokens, while the rules that decide which reads count live in the orchestrator.
     */
    private static final List<String> STAMP = List.of(ROOT + "runtime/TestStampInputs", PACKAGE + "SuiteReads");

    /**
     * The classes that size a suite JVM's heap and climb it when the heap runs out: the heap a JVM
     * runs at is learned on each host and no stamp names it, so neither does the code that picks it.
     * Which failures a retry answers and how its run replaces them is the orchestrator's.
     */
    private static final List<String> HEAP = List.of(PACKAGE + "SuiteRetry");

    /** The words a class's own name carries when it is suite code, wherever its package. */
    private static final List<String> WORDS = List.of("Test", "Suite", "JUnit");

    private final String recorded;
    private final List<String> sources;
    private final Predicate<String> keeps;
    private volatile @Nullable String override;

    KeyContract(String recorded, List<String> sources, Predicate<String> keeps) {
        this.recorded = recorded;
        this.sources = sources;
        this.keeps = keeps;
    }

    /** The digest the keys fold in: the one recorded here, unless a test stands another in. */
    public String digest() {
        String o = override;
        return o != null ? o : recorded;
    }

    /** The digest recorded in this file, which the guard compares with the sources'. */
    public String recorded() {
        return recorded;
    }

    /** The version string this contract's actions key by: {@link BuildIdentity#cacheKeyVersion()} and {@link #digest()}. */
    public String keyVersion() {
        return BuildIdentity.cacheKeyVersion() + "+" + digest();
    }

    /** Workspace-relative globs of the files the contract may read from. */
    public List<String> sources() {
        return sources;
    }

    /** Whether the file {@code path}, one a {@link #sources()} glob matched, is the contract's. */
    public boolean keeps(String path) {
        return keeps.test(path);
    }

    /** Pretend the contract's code is the one {@code digest} names; {@code null} returns to the recorded digest. */
    public void overrideDigestForTests(@Nullable String digest) {
        override = digest;
    }

    /**
     * The digest of {@code code}, each file's text by its workspace-relative path, comments already
     * blanked: path and text with every whitespace character removed, in path order, 12 hex.
     */
    public static String digest(SortedMap<String, String> code) {
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, String> e : code.entrySet()) {
            all.append(e.getKey()).append('\n');
            all.append(e.getValue().replaceAll("\\s+", "")).append('\n');
        }
        return Hashing.sha256Hex(all.toString()).substring(0, 12);
    }

    /**
     * Whether the archive entry {@code entry} is suite code: a jk class of the package {@code
     * cc.jumpkick.test}, or one whose top-level class name carries {@code Test}, {@code Suite} or
     * {@code JUnit}, outside the package {@code cc.jumpkick.orchestrator}, the classes that write
     * the stamp ({@link #STAMP}) and those that size a suite JVM's heap ({@link #HEAP}).
     */
    public static boolean suiteCode(String entry) {
        if (!entry.startsWith(ROOT) || !entry.endsWith(CLASS) || entry.startsWith(ORCHESTRATOR)) return false;
        String name = entry.substring(0, entry.length() - CLASS.length());
        int nested = name.indexOf('$');
        String owner = nested < 0 ? name : name.substring(0, nested);
        if (STAMP.contains(owner) || HEAP.contains(owner)) return false;
        if (entry.startsWith(PACKAGE)) return true;
        String simple = entry.substring(entry.lastIndexOf('/') + 1, entry.length() - CLASS.length());
        nested = simple.indexOf('$');
        String top = nested < 0 ? simple : simple.substring(0, nested);
        for (String word : WORDS) {
            if (top.contains(word)) return true;
        }
        return false;
    }

    /** The archive entry a main source file compiles to: {@code cc/jumpkick/test/Discovery.class}. */
    public static String entryOf(String path) {
        int at = path.indexOf(MAIN);
        String rel = at < 0 ? path : path.substring(at + MAIN.length());
        return rel.endsWith(".java") ? rel.substring(0, rel.length() - ".java".length()) + CLASS : rel;
    }

    private static boolean suiteSource(String path) {
        return suiteCode(entryOf(path));
    }
}
