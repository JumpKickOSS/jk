// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycInputs;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincInputs;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.BuildIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Builds {@code SHA-256(action) → outputs} keys for the {@link ActionCache}.
 *
 * <p>The action key for a javac invocation is a stable hash of:
 *
 * <ul>
 * <li>the task identifier ({@link #qualifiedTaskId}: the base name plus a location-free tag)
 * <li>jk version
 * <li>{@code --release}, the pinned source encoding, and any extra javac options
 * <li>the compiling JDK's toolchain token ({@link JdkToolchain#token}): vendor, version and build, never its OS or path
 * <li>each source file's module-relative path and SHA-256 (so editing a file invalidates the key,
 *     and two checkouts of the same module compute the same key)
 * <li>a constant {@code mirrored:excluded-unless-rewritten} line, so a record that captured
 *     resource files copied into the same directory under an older rule is not a hit
 * <li>when processors run, the digest of the main resources the copy lays into the output
 *     directory before the compile ({@code resources:}): a processor reads them through the Filer
 * <li>each compile-classpath entry's JVM ABI ({@link ClasspathAbi}: {@code abi:<sha256>}) and each
 *     processor-path entry's content identity ({@code file:<sha256>} / directory tree hash) —
 *     see {@link #javacClasspathTokens}
 * </ul>
 */
public final class ActionKey {

    /**
     * The charset both javac front ends pin. {@link cc.jumpkick.compile.JavacRunner} passes this
     * very constant to {@code -encoding}; {@code ZincJavaCompiler} keeps its own copy because it
     * runs in a forked worker JVM whose classpath is rebuilt from a POM and cannot see this class
     * — layer-forced, so move the two together.
     *
     * <p>Because it is a constant it can never separate two of today's keys. It is hashed anyway
     * because the charset decides the bytes: a key that omits it would serve Latin-1-decoded
     * artifacts to a UTF-8 build the day the pin moves, and a one-time recompile is the whole cost
     * of being able to move it.
     */
    public static final String SOURCE_ENCODING = "UTF-8";

    /** Prefix of a Java declaration-digest line in a kotlinc key and its record. */
    public static final String JAVA_API = "java-api:";

    private ActionKey() {}

    private static final EntryToken DEFAULT_CP = ClasspathAbi::token;

    public static String forJavac(String taskId, CompileRequest request, String jkVersion) throws IOException {
        return forJavac(taskId, request, jkVersion, DEFAULT_CP);
    }

    /**
     * As {@link #forJavac(String, CompileRequest, String)} with the compile classpath read through
     * {@code cp} — the forecast's view, which answers a sibling tree the build will restore before
     * this compile keys on it with the token that tree will have.
     */
    public static String forJavac(String taskId, CompileRequest request, String jkVersion, EntryToken cp)
            throws IOException {
        return forJavac(taskId, request, jkVersion, javacClasspathTokens(request, cp));
    }

    /** As {@link #forJavac(String, CompileRequest, String)} reusing already-computed {@code classpathTokens}. */
    public static String forJavac(String taskId, CompileRequest request, String jkVersion, List<String> classpathTokens)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        appendJavacOptions(sb, request);
        // Resources copied into this output directory are not compile outputs unless a processor
        // rewrote them. A record stored under an older rule does not hit.
        sb.append("mirrored:excluded-unless-rewritten\n");

        // Sources: path + content hash (FileHashMemo — at most one content read per path/thread).
        appendSources(sb, request.sources());
        for (String line : classpathTokens) sb.append(line).append('\n');
        KeyAudit.tapLines(sb);
        String key = EnvKeyText.digest(sb);
        ToolchainPolicy.expect(
                key,
                request.compilerHome(),
                ToolchainPolicy.level(request.release(), request.compilerHome()),
                ToolchainPolicy.tiedToMajor(request.extraOptions()),
                request.outputDir());
        return key;
    }

    /**
     * The classpath lines {@link #forJavac} hashes, one per entry in key order: {@code cp:} for the
     * compile classpath, then {@code pp:} for the processor path.
     *
     * <p>A {@code cp:} entry is its JVM ABI ({@link ClasspathAbi}) — what javac can see of it:
     * signatures, supers, inlined constants, API annotations — never its bodies. A dependency whose
     * implementation changed therefore keys the same compile: the consumer forks no worker and runs
     * no Zinc analysis, and the dependents of an unchanged consumer keep hitting. A {@code pp:}
     * entry is full content: a processor's behaviour is its bodies, and what it generates is this
     * compile's output. Directory entries (a sibling lane's classes dir) go through the same
     * extractor as jars, so a Kotlin- or Groovy-only API change still moves the key.
     *
     * <p>The freshness stamp in front of the key records these very lines, so the stamp moves
     * exactly when the key would and on nothing else.
     */
    public static List<String> javacClasspathTokens(CompileRequest request) throws IOException {
        return javacClasspathTokens(request, DEFAULT_CP);
    }

    /** As {@link #javacClasspathTokens(CompileRequest)} with the compile classpath read through {@code cp}. */
    public static List<String> javacClasspathTokens(CompileRequest request, EntryToken cp) throws IOException {
        JAVAC_CLASSPATH_READS.incrementAndGet();
        List<String> lines = new ArrayList<>();
        appendCpTokens(lines, "cp:", request.classpath(), javacEntryToken(request, cp));
        // Through cp's identity: a sibling tree on the processor path that the build restores before
        // this compile keys on it reads as the restored tree, not the stale one on disk.
        appendCpTokens(lines, "pp:", request.processorPath(), cp::identity);
        return lines;
    }

    /** Every {@link #javacClasspathTokens} read so far: each one fingerprints every entry it names. */
    private static final AtomicLong JAVAC_CLASSPATH_READS = new AtomicLong();

    /** How many javac classpaths have been read; tests count one step's reads by the difference. */
    public static long javacClasspathReads() {
        return JAVAC_CLASSPATH_READS.get();
    }

    /**
     * How a javac compile's classpath entries are spelled: their ABI, with their {@code META-INF}
     * resources beside it when processors run, or their whole content when javac finds the
     * processors on the classpath itself, whose bodies are then the compile's behaviour.
     */
    private static EntryToken javacEntryToken(CompileRequest request, EntryToken cp) {
        boolean asked = processingAsked(request.extraOptions());
        if (asked && request.processorPath().isEmpty()) return ClasspathFingerprint::entry;
        if (asked || !request.processorPath().isEmpty()) return withResources(cp);
        return cp;
    }

    /** How a groovyc compile's classpath entries are spelled: see {@link #groovycClasspathTokens}. */
    private static EntryToken groovycEntryToken(EntryToken cp) {
        return entry -> ClasspathAbi.hasGlobalTransform(entry)
                ? ClasspathFingerprint.entry(entry)
                : withResources(cp).of(entry);
    }

    /**
     * Whether a javac compile with this processor path and these options may run annotation
     * processors: a processor path is named, or the options turn processing on.
     */
    public static boolean processorsMayRun(List<Path> processorPath, List<String> options) {
        return !processorPath.isEmpty() || processingAsked(options);
    }

    /** Whether the options turn annotation processing on without naming a processor path. */
    private static boolean processingAsked(List<String> options) {
        for (String o : options) {
            if (o.equals("-proc:full") || o.equals("-proc:only") || o.equals("-processor")) return true;
        }
        return false;
    }

    /**
     * {@code cp} with each entry's {@code META-INF} resources beside its ABI ({@link
     * ClasspathAbi#resources}): what a processor or groovyc reads of a dependency besides its API.
     */
    private static EntryToken withResources(EntryToken cp) {
        if (cp == DEFAULT_CP) return ClasspathAbi::tokenWithResources;
        return entry -> cp.of(entry) + "+" + ClasspathAbi.resources(entry);
    }

    /**
     * Digest of a javac request's option-bearing inputs — everything {@link #forJavac} hashes
     * apart from the sources and the class/processor paths: {@code --release}, the encoding, the
     * JDK, the options (which carry the {@code [javac]} plugins) and the Scala toolchain. The
     * freshness stamp records it so an option or toolchain change is stale even though no file
     * moved; the same request the build keys with feeds it, so the forecast's answer matches.
     */
    public static String javacOptionsDigest(CompileRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        appendJavacOptions(sb, request);
        // The stamp is this checkout's own: a classes tree javac of another JDK wrote is stale here
        // even where the key, which names the level, would let the cache serve it.
        sb.append("jdk:").append(JdkToolchain.token(request.compilerHome())).append('\n');
        KeyAudit.tapLines(sb);
        return EnvKeyText.digest(sb);
    }

    private static void appendJavacOptions(StringBuilder sb, CompileRequest request) throws IOException {
        sb.append("release:").append(request.release()).append('\n');
        sb.append("encoding:").append(SOURCE_ENCODING).append('\n');
        // The module's Java level, not the JDK that ran javac: the JDK is the record's TOOLCHAIN
        // and a lookup dimension (ToolchainPolicy), so a record from another vendor or patch of
        // the same major serves by default and one from another major only when the reader allows.
        // Always concrete: a module with no --release compiles for its compiler's own major.
        sb.append("java:")
                .append(ToolchainPolicy.level(request.release(), request.compilerHome()))
                .append('\n');
        // In argv order, never sorted: options pair with the value that follows them, so
        // `--add-modules a --limit-modules b` and `--add-modules b --limit-modules a` hold the
        // same words and are different compiles.
        sb.append("options:").append(optionsToken(request.extraOptions())).append('\n');
        // The worker that drives javac, by content: jk's wrapper decides the options javac sees
        // and what is kept of its output, so a different worker is a different compiler. Its jar
        // carries no version of its own under a release build, so its bytes are the identity.
        for (String token : WorkerClosure.tokens(PluginJar.JAVA_COMPILER))
            sb.append("worker:").append(token).append('\n');
        // What a processor can read back from the class output: the resources copied in ahead
        // of the compile. Absent when no processor runs, so a resource edit moves no other key.
        if (request.resourcesDigest() != null)
            sb.append("resources:").append(request.resourcesDigest()).append('\n');
        // The module's [env] reaches the worker, and what reads it there is a processor or a javac
        // plugin: keyed only when one may run, so a declared variable rebuilds no plain compile.
        if (processorsMayRun(request.processorPath(), request.extraOptions())
                || request.extraOptions().stream().anyMatch(o -> o.startsWith("-Xplugin:"))) {
            for (String token : request.env()) sb.append(token).append('\n');
        }
        if (request.mixedScala()) {
            sb.append("scala:").append(request.scalaVersion()).append('\n');
            List<Path> scp = new ArrayList<>(request.compilerClasspath());
            scp.sort(Comparator.comparing(Path::toString));
            for (Path entry : scp) {
                appendCpToken(sb, "sc:", entry);
            }
        }
    }

    /**
     * Action key for a Kotlin worker invocation. Same shape as {@link #forJavac}: task + jk version +
     * jvm target + the project JDK + the free args in order + each source's content hash + each
     * Java source's declaration digest + the compile classpath's ABI + the worker's Build Tools API
     * closure by content (its CAS paths encode the compiler version, so a compiler bump invalidates
     * the key).
     *
     * <p>The compile classpath is keyed on each entry's {@link KotlinClasspathAbi Kotlin ABI token},
     * not its bytes: a sibling whose implementation changed but whose ABI did not leaves this key —
     * and the consumer's compile — alone. The worker still receives the real jars and directories;
     * only the key looks at them through the snapshot. {@code snapshotter} is how a token not yet
     * memoized gets computed (the worker's {@code snapshot} op in a build; a fake in tests).
     *
     * <p>A mixed module's Java sources reach kotlinc through {@code -Xjava-source-roots}: it reads
     * their declarations and links against them, never their bodies. They enter the key as
     * {@link JavaSourceApi declaration digests} — see {@link #kotlincJavaSourceTokens} — so a Java
     * signature edit the Kotlin side depends on misses, while a Java body-only edit keeps the key
     * and restores Kotlin output that is still correct.
     */
    public static String forKotlinc(
            String taskId, KotlincRequest request, String jkVersion, KotlinClasspathAbi.Snapshotter snapshotter)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        appendKotlincOptions(sb, request);
        appendSources(sb, request.sources());
        for (String line : kotlincJavaSourceTokens(request)) sb.append(line).append('\n');

        // The classpath's ABI in request order: kotlinc takes a class from the first entry that has it.
        for (String token : KotlinClasspathAbi.tokens(request.classpath(), snapshotter)) {
            sb.append("cp:").append(token).append('\n');
        }
        // The compiler itself, by content: a different Build Tools API closure is a different
        // compiler and may emit different bytecode from the same sources and ABI.
        List<Path> worker = new ArrayList<>(request.workerClasspath());
        worker.sort(Comparator.comparing(Path::toString));
        for (Path entry : worker) {
            appendCpToken(sb, "worker:", entry);
        }
        KeyAudit.tapLines(sb);
        String key = EnvKeyText.digest(sb);
        ToolchainPolicy.expect(
                key,
                request.javaHome(),
                ToolchainPolicy.level(request.jvmTarget(), request.javaHome()),
                ToolchainPolicy.tiedToMajor(request.extraArgs()),
                request.outputDir());
        return key;
    }

    /**
     * The Java-source lines {@link #forKotlinc} hashes, one per {@code .java} under the request's
     * Java source roots in path order: {@code java-api:<module-relative path>:<declaration
     * digest>}. Empty for a Kotlin-only module. The digest is {@link JavaSourceApi}'s: the file's
     * declarations, never its bodies — the part of a Java file kotlinc consumes.
     */
    public static List<String> kotlincJavaSourceTokens(KotlincRequest request) throws IOException {
        List<Path> javaSources = KotlincInputs.javaSources(request);
        if (javaSources.isEmpty()) return List.of();
        Map<Path, String> digests = JavaSourceApi.digests(javaSources);
        List<String> lines = new ArrayList<>(javaSources.size());
        for (Path src : javaSources) {
            lines.add(JAVA_API + PortablePath.of(src) + ":" + Objects.requireNonNull(digests.get(src), "digest"));
        }
        return lines;
    }

    private static void appendKotlincOptions(StringBuilder sb, KotlincRequest request) throws IOException {
        sb.append("jvmTarget:").append(request.jvmTarget()).append('\n');
        // The module's Java level; the JDK kotlinc links against (-jdk-home) is the record's
        // TOOLCHAIN and a lookup dimension (ToolchainPolicy), as for javac.
        sb.append("java:")
                .append(ToolchainPolicy.level(request.jvmTarget(), request.javaHome()))
                .append('\n');
        // -module-name reshapes internal-member mangling in the output.
        if (request.moduleName() != null) {
            sb.append("moduleName:").append(request.moduleName()).append('\n');
        }
        // Argv order, as forJavac: a flag pairs with the value after it.
        sb.append("args:").append(optionsToken(request.extraArgs())).append('\n');
        for (String token : request.env()) sb.append(token).append('\n');

        // Compiler plugins reshape the output (all-open/no-arg synthesize members)
        // key on id + jar CONTENT + options so a plugin change re-compiles.
        for (var plugin : request.plugins()) {
            sb.append("plugin:")
                    .append(plugin.id())
                    .append(':')
                    .append(FileHashMemo.contentHash(plugin.jar()))
                    .append(':')
                    .append(String.join(",", plugin.options()))
                    .append('\n');
        }
    }

    /**
     * The inputs of a Kotlin compile record, for {@code jk explain}: each source's hash, each
     * Java source's declaration digest under {@code java-api:} (so a Java signature edit names the
     * file and a body-only one reads as nothing), each classpath entry's {@link KotlinClasspathAbi
     * ABI token} under its module-relative path (so a sibling whose ABI moved reads as that entry
     * changing, and a body-only rewrite reads as nothing), and the option-bearing facts {@link
     * #forKotlinc} hashes. The tokens and digests are memoized, so after the key this is a lookup
     * per entry.
     */
    public static Map<String, String> kotlincInputs(KotlincRequest request, KotlinClasspathAbi.Snapshotter snapshotter)
            throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<Path> sortedSources = new ArrayList<>(request.sources());
        sortedSources.sort(Comparator.comparing(Path::toString));
        for (Path src : sortedSources) {
            Path abs = src.toAbsolutePath().normalize();
            result.put(PortablePath.key(abs), FileHashMemo.contentHash(abs));
        }
        List<Path> javaSources = KotlincInputs.javaSources(request);
        if (!javaSources.isEmpty()) {
            Map<Path, String> digests = JavaSourceApi.digests(javaSources);
            for (Path src : javaSources) {
                result.put(JAVA_API + PortablePath.key(src), Objects.requireNonNull(digests.get(src), "digest"));
            }
        }
        List<String> tokens = KotlinClasspathAbi.tokens(request.classpath(), snapshotter);
        for (int i = 0; i < tokens.size(); i++) {
            result.put("cp:" + PortablePath.key(request.classpath().get(i)), tokens.get(i));
        }
        for (Path entry : request.workerClasspath()) {
            Path identity = FreshnessStamp.identityKey(entry);
            result.put(
                    "worker:"
                            + (identity.isAbsolute()
                                    ? PortablePath.key(identity)
                                    : identity.toString().replace('\\', '/')),
                    "");
        }
        result.put("jvmTarget", Integer.toString(request.jvmTarget()));
        result.put("jdk", JdkToolchain.token(request.javaHome()));
        String moduleName = request.moduleName();
        result.put("moduleName", moduleName == null ? "" : moduleName);
        result.put("args", optionsToken(request.extraArgs()));
        for (var plugin : request.plugins()) {
            result.put(
                    "plugin:" + plugin.id(),
                    FileHashMemo.contentHash(plugin.jar()) + ":" + String.join(",", plugin.options()));
        }
        return result;
    }

    /**
     * Action key for a Groovy worker invocation. Same shape as {@link #forKotlinc}: task + jk
     * version + jvm target + the free args in order + each source's content hash + Java-source-root file
     * hashes (they feed joint resolution) + classpath paths (both the compilation classpath and the
     * worker's Groovy closure — whose CAS paths encode the compiler version, so a compiler bump
     * invalidates the key).
     */
    public static String forGroovyc(String taskId, GroovycRequest request, String jkVersion) throws IOException {
        return forGroovyc(taskId, request, jkVersion, ClasspathAbi::token);
    }

    /** As {@link #forGroovyc(String, GroovycRequest, String)} with the compile classpath read through {@code cp}. */
    public static String forGroovyc(String taskId, GroovycRequest request, String jkVersion, EntryToken cp)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("jvmTarget:").append(request.jvmTarget()).append('\n');
        // The module's Java level; the engine's own JDK, which groovyc runs on, is the record's
        // TOOLCHAIN and a lookup dimension (ToolchainPolicy).
        sb.append("java:")
                .append(ToolchainPolicy.level(request.jvmTarget(), JavaHomes.runningJavaHome()))
                .append('\n');
        sb.append("args:").append(optionsToken(request.extraArgs())).append('\n');
        for (String token : request.env()) sb.append(token).append('\n');

        // The hashed set IS the spec's SOURCE set (GroovycInputs): explicit sources plus every
        // .java the roots feed joint resolution — an edit to a swept file invalidates the key
        // just like an explicit source, and the worker compiles exactly what was hashed.
        appendSources(sb, GroovycInputs.compileSet(request));
        for (String line : groovycClasspathTokens(request, cp)) sb.append(line).append('\n');
        KeyAudit.tapLines(sb);
        String key = EnvKeyText.digest(sb);
        ToolchainPolicy.expect(
                key,
                JavaHomes.runningJavaHome(),
                ToolchainPolicy.level(request.jvmTarget(), JavaHomes.runningJavaHome()),
                false,
                request.outputDir());
        return key;
    }

    /**
     * The classpath lines {@link #forGroovyc} hashes, in key order. {@code cp:} is the compile
     * classpath's JVM ABI — groovyc's compile-time view of a Java or Groovy dependency is JVM
     * signatures and constant values, the same token javac keys on ({@link #javacClasspathTokens});
     * the Groovy worker is always a full compile, so this is the only skip a body-only sibling
     * change can give it. {@code worker:} is the worker's Groovy closure by content — that is the
     * compiler, and its CAS paths already encode the version. {@code pp:} is the processor path by
     * content, as for javac.
     */
    public static List<String> groovycClasspathTokens(GroovycRequest request) throws IOException {
        return groovycClasspathTokens(request, ClasspathAbi::token);
    }

    /** As {@link #groovycClasspathTokens(GroovycRequest)} with the compile classpath read through {@code cp}. */
    public static List<String> groovycClasspathTokens(GroovycRequest request, EntryToken cp) throws IOException {
        List<String> lines = new ArrayList<>();
        // groovyc loads extension modules and global AST transforms off the compile classpath's
        // META-INF; a transform's body is the compile's behaviour, so its jar keys by content.
        appendCpTokens(lines, "cp:", request.classpath(), groovycEntryToken(cp));
        appendCpTokens(lines, "worker:", request.workerClasspath(), ClasspathFingerprint::entry);
        appendCpTokens(lines, "pp:", request.processorPath(), ClasspathFingerprint::entry);
        return lines;
    }

    /**
     * Action key for a packaging artifact (jar, fat-jar, native binary, OCI tarball, …) or any other
     * output the engine's own code produces from a token bag (a plugin step's staging, a guard
     * lane's verdict, a build-logic run). A stable hash of the task id, jk version, the producing
     * code's identity, and a set of pre-computed input tokens — typically {@link
     * ClasspathFingerprint} hashes of the input classes/jars plus config strings (main-class,
     * manifest, build args, toolchain version, …). The caller MUST include every input that affects
     * the produced bytes; a missing token risks serving a stale artifact.
     *
     * <p>The producer's identity is {@link BuildIdentity#keyId()}, the running engine archive's code.
     * The rules a packager applies — what it excludes, how it merges, what it writes at the root —
     * are engine code, and a version string alone does not name them: two engines built from
     * different sources under one version would otherwise share a key, and the second would
     * restore the first's artifact, defect and all, until {@code --redo}. Compile keys do not
     * carry it: their producers are the compiler workers, which those keys already hash.
     */
    public static String forArtifact(String taskId, String jkVersion, List<String> inputTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("producer:").append(BuildIdentity.keyId()).append('\n');
        List<String> sorted = new ArrayList<>(inputTokens);
        sorted.sort(Comparator.naturalOrder());
        for (String t : sorted) sb.append("in:").append(t).append('\n');
        KeyAudit.tapLines(sb);
        return EnvKeyText.digest(sb);
    }

    /**
     * Snapshot of inputs that produced an action — for {@code jk explain} diffs. Every path is a
     * {@link PortablePath}, so a record one checkout wrote reads correctly in another. Source
     * hashes reuse {@link FileHashMemo#contentHash} so a prior {@link #forJavac} on the same thread
     * does not re-read file bytes. Classpath entries are valued by the very token the key hashed
     * ({@link #javacClasspathTokens}), so a body-only dependency change diffs as "nothing changed"
     * and an API change names the entry that moved.
     */
    public static Map<String, String> snapshotInputs(CompileRequest request) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        snapshotSources(result, request.sources());
        snapshotEntries(result, "cp:", request.classpath(), javacEntryToken(request, ClasspathAbi::token));
        snapshotEntries(result, "pp:", request.processorPath(), ClasspathFingerprint::entry);
        result.put("release", Integer.toString(request.release()));
        result.put("options", optionsToken(request.extraOptions()));
        // The key hashes the compiling JDK, so the why-rebuilt diff has to be able to name it:
        // without this a JDK switch reads as "nothing changed, rebuilt anyway".
        result.put("jdk", JdkToolchain.token(request.compilerHome()));
        // Every other line the key hashes, so the diff can name whatever moved it: jk's identity,
        // the compiler worker, the resources a processor reads, the [env] a processor sees.
        result.put(JK_INPUT, BuildIdentity.compileKeyVersion());
        result.put("worker", String.join(",", WorkerClosure.tokens(PluginJar.JAVA_COMPILER)));
        if (request.resourcesDigest() != null) result.put("resources", request.resourcesDigest());
        if (processorsMayRun(request.processorPath(), request.extraOptions())
                || request.extraOptions().stream().anyMatch(o -> o.startsWith("-Xplugin:"))) {
            result.put("env", String.join(",", request.env()));
        }
        return result;
    }

    /** The record input naming the jk whose key shape and engine identity the key hashed. */
    public static final String JK_INPUT = "jk";

    /**
     * What moved between two records' inputs ({@link #snapshotInputs}' spelling), in words: sources
     * by count, {@code java-api:} declarations, {@code cp:} entries by ABI, {@code pp:} processors,
     * {@code worker} the compiler, then the settings (release, options, JDK, resources, env, jk) by
     * name. Empty when the two agree.
     */
    public static String missReason(Map<String, String> prior, Map<String, String> now) {
        int sources = 0;
        Set<String> declarations = new TreeSet<>();
        Set<String> api = new TreeSet<>();
        Set<String> processors = new TreeSet<>();
        Set<String> settings = new TreeSet<>();
        boolean compiler = false;
        Set<String> keys = new TreeSet<>(now.keySet());
        keys.addAll(prior.keySet());
        for (String k : keys) {
            if (Objects.equals(now.get(k), prior.get(k))) continue;
            if (k.startsWith("cp:")) api.add(fileName(k.substring(3)));
            else if (k.startsWith("pp:")) processors.add(fileName(k.substring(3)));
            else if (k.startsWith(JAVA_API)) declarations.add(fileName(k.substring(JAVA_API.length())));
            else if (k.equals("worker") || k.startsWith("worker:")) compiler = true;
            else if (SETTINGS.contains(k)) settings.add(k.equals(JK_INPUT) ? "jk" : k);
            else sources++;
        }
        List<String> parts = new ArrayList<>();
        if (sources > 0) parts.add(sources + (sources == 1 ? " source" : " sources") + " changed");
        if (!declarations.isEmpty()) parts.add("Java declarations changed (" + String.join(", ", declarations) + ")");
        if (!api.isEmpty()) parts.add("dependency ABI changed (" + String.join(", ", api) + ")");
        if (!processors.isEmpty()) parts.add("processor changed (" + String.join(", ", processors) + ")");
        if (compiler) parts.add("compiler changed");
        for (String setting : settings) {
            // The JDK by name, so a miss across two hosts on two patches says which two.
            if (setting.equals("jdk"))
                parts.add("JDK changed (" + JdkToolchain.describe(prior.get(setting)) + " → "
                        + JdkToolchain.describe(now.get(setting)) + ")");
            else parts.add(setting + " changed");
        }
        return String.join(" · ", parts);
    }

    /** The record inputs that are a setting rather than a file. */
    private static final Set<String> SETTINGS = Set.of("release", "options", "jdk", "jvmTarget", "args", JK_INPUT);

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** As {@link #snapshotInputs(CompileRequest)} for a Groovy compile: its source set and its token lines. */
    public static Map<String, String> snapshotInputs(GroovycRequest request) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        snapshotSources(result, GroovycInputs.compileSet(request));
        snapshotEntries(result, "cp:", request.classpath(), groovycEntryToken(ClasspathAbi::token));
        snapshotEntries(result, "worker:", request.workerClasspath(), ClasspathFingerprint::entry);
        snapshotEntries(result, "pp:", request.processorPath(), ClasspathFingerprint::entry);
        result.put("jvmTarget", Integer.toString(request.jvmTarget()));
        result.put("args", optionsToken(request.extraArgs()));
        return result;
    }

    /**
     * The sources whose bytes differ from what {@code recorded} holds for them under {@link
     * #snapshotInputs}' spelling ({@link PortablePath#key} to content hash), or that are gone. A
     * compile compares its request's sources against the snapshot it took before the worker read
     * them, so an edit that landed while the worker ran is named; the record diff for
     * {@code jk why-rebuilt} asks the same question of a prior record.
     */
    public static List<Path> changedSources(List<Path> sources, Map<String, String> recorded) throws IOException {
        List<Path> changed = new ArrayList<>();
        for (Path s : sources) {
            Path abs = s.toAbsolutePath().normalize();
            String prior = recorded.get(PortablePath.key(abs));
            if (prior == null || !Files.isRegularFile(abs) || !prior.equals(FileHashMemo.contentHash(abs))) {
                changed.add(s);
            }
        }
        return changed;
    }

    private static void snapshotSources(Map<String, String> into, List<Path> sources) throws IOException {
        List<Path> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparing(Path::toString));
        for (Path src : sorted) {
            Path abs = src.toAbsolutePath().normalize();
            into.put(PortablePath.key(abs), FileHashMemo.contentHash(abs));
        }
    }

    private static void snapshotEntries(Map<String, String> into, String prefix, List<Path> entries, EntryToken token)
            throws IOException {
        List<Path> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Path::toString));
        for (Path entry : sorted) {
            into.put(prefix + PortablePath.key(entry), token.of(entry));
        }
    }

    /**
     * The spelling of compiler argv in key material: the tokens joined by commas, in order, with
     * every absolute path inside a token rendered as a {@link PortablePath}. A compiler receives
     * absolute paths ({@code --source-path}, {@code --patch-module m=<roots>}, {@code
     * -Xjava-source-roots=<roots>}, native-image's {@code -H:ConfigurationFileDirectories=<dirs>}),
     * and a key that hashed them would name the checkout. Paths are recognised inside {@code =},
     * {@code ,} and path-separator lists; everything else passes through unchanged. The content the
     * paths name enters the key on its own terms (sources by hash, classpath entries by ABI).
     */
    public static String optionsToken(List<String> options) {
        StringBuilder sb = new StringBuilder();
        for (String option : options) {
            if (sb.length() > 0) sb.append(',');
            sb.append(portableArgument(option));
        }
        return sb.toString();
    }

    static String portableArgument(String token) {
        StringBuilder out = new StringBuilder(token.length());
        int start = 0;
        for (int i = 0; i <= token.length(); i++) {
            boolean end = i == token.length();
            char c = end ? 0 : token.charAt(i);
            if (end || c == ',' || c == '=' || c == Classpaths.SEPARATOR.charAt(0)) {
                out.append(portablePiece(token.substring(start, i)));
                if (!end) out.append(c);
                start = i + 1;
            }
        }
        return out.toString();
    }

    private static String portablePiece(String piece) {
        boolean drive = piece.length() > 2
                && Character.isLetter(piece.charAt(0))
                && piece.charAt(1) == ':'
                && (piece.charAt(2) == '\\' || piece.charAt(2) == '/');
        if (!(piece.startsWith("/") || drive)) return piece;
        try {
            Path p = Path.of(piece);
            return p.isAbsolute() ? PortablePath.of(p) : piece;
        } catch (InvalidPathException notAPath) {
            return piece;
        }
    }

    /**
     * How a classpath entry is spelled in a key. {@link ClasspathAbi#token} for a compile
     * classpath; the forecast substitutes a view that answers a tree not yet on disk with the
     * token the build will read once it has restored it.
     */
    @FunctionalInterface
    public interface EntryToken {
        String of(Path entry) throws IOException;

        /**
         * The entry's content identity, as a processor-path line keys it: the tree on disk unless
         * the reader knows the tree the build will restore there first (the forecast's view).
         */
        default String identity(Path entry) throws IOException {
            return ClasspathFingerprint.entry(entry);
        }
    }

    /**
     * One classpath/processorpath token. Identity is content (lock digest / file hash), not the
     * on-disk path — the same jar may live under the Maven local repo or {@code repos/<name>/}.
     */
    private static void appendCpToken(StringBuilder sb, String prefix, Path entry) throws IOException {
        sb.append(prefix).append(ClasspathFingerprint.entry(entry)).append('\n');
    }

    /**
     * {@code prefix + token} per entry, in list order: a compiler takes a class from the first entry
     * that has it, so two entries trading places can change what it compiles against.
     */
    private static void appendCpTokens(List<String> into, String prefix, List<Path> entries, EntryToken token)
            throws IOException {
        for (Path entry : entries) into.add(prefix + token.of(entry));
    }

    private static void appendSources(StringBuilder sb, List<Path> sources) throws IOException {
        List<Path> sortedSources = new ArrayList<>(sources);
        sortedSources.sort(Comparator.comparing(Path::toString));
        for (Path src : sortedSources) {
            Path abs = src.toAbsolutePath().normalize();
            sb.append("source:")
                    .append(PortablePath.of(abs))
                    .append(':')
                    .append(FileHashMemo.contentHash(abs))
                    .append('\n');
        }
    }

    /**
     * Qualify a base task id (e.g. {@code compile-main}) with a tag naming the output within its
     * project: the project's durable id ({@link ProjectIds}) and {@code outputDir}'s path relative
     * to the workspace root, hashed to 12 hex characters. Every checkout of one project spells the
     * same tag for the same output, so the {@code task:} line of a key and the {@link ActionCache}
     * {@code tasks/<taskId>} pointer are shared across worktrees, while two modules or two projects
     * with identical inputs keep distinct pointers. No absolute path enters; a directory under no
     * project at all falls back to {@link #checkoutTag}.
     */
    public static String qualifiedTaskId(String base, @Nullable Path outputDir) {
        return base + "@" + taskTag(outputDir);
    }

    /**
     * The 12-hex-char tag {@link #qualifiedTaskId} appends after {@code @}. Exposed so cache
     * maintenance ({@code jk clean -F}) can recompute the tags for a project's output dirs and
     * match every {@code tasks/<base>@<tag>} pointer that belongs to it.
     */
    public static String taskTag(@Nullable Path outputDir) {
        Path p = (outputDir == null ? Path.of("") : outputDir).toAbsolutePath().normalize();
        Optional<Path> root = PortablePath.projectRoot(p);
        if (root.isEmpty()) return checkoutTag(p);
        String id = ProjectIds.idOf(root.get().toString());
        if (id == null) return checkoutTag(p);
        String rel = root.get().relativize(p).toString().replace('\\', '/');
        return Hashing.sha256Hex(id + "\n" + rel).substring(0, 12);
    }

    /**
     * A 12-hex-char tag of {@code dir}'s real absolute path: the one spelling in the cache that
     * names a checkout. It qualifies the incremental compiler state ({@link #stateDir}), whose
     * analysis holds absolute paths and belongs to one checkout only; never a key or a pointer.
     */
    public static String checkoutTag(@Nullable Path dir) {
        Path p = (dir == null ? Path.of("") : dir).toAbsolutePath().normalize();
        Path probe = p;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            try {
                Path real = probe.toRealPath();
                p = real.resolve(probe.relativize(p));
            } catch (IOException ignored) {
                // output dir may not exist yet — keep the absolute path
            }
        }
        return Hashing.sha256Hex(p.toString()).substring(0, 12);
    }

    /**
     * The incremental compiler state dir for {@code base} writing {@code outputDir}, under {@code
     * incrementalRoot} ({@code ActionTree.INCREMENTAL_JAVA} or {@code INCREMENTAL_KOTLIN} under the
     * actions tree): {@code <base>@<checkoutTag>}. Per checkout, unlike the task pointer.
     */
    public static Path stateDir(Path incrementalRoot, String base, @Nullable Path outputDir) {
        return incrementalRoot.resolve(base + "@" + checkoutTag(outputDir));
    }
}
