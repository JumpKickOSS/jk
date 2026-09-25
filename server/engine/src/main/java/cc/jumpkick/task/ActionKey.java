// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycInputs;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincInputs;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
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
 * <li>the project JDK's identity ({@link #jdkToken})
 * <li>each source file's module-relative path and SHA-256 (so editing a file invalidates the key,
 *     and two checkouts of the same module compute the same key)
 * <li>a constant {@code mirrored:excluded} line, so a record that captured resource files copied
 *     into the same directory is not a hit — a resource edit does not move the key
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

    public static String forJavac(String taskId, CompileRequest request, String jkVersion) throws IOException {
        return forJavac(taskId, request, jkVersion, ClasspathAbi::token);
    }

    /**
     * As {@link #forJavac(String, CompileRequest, String)} with the compile classpath read through
     * {@code cp} — the forecast's view, which answers a sibling tree the build will restore before
     * this compile keys on it with the token that tree will have.
     */
    public static String forJavac(String taskId, CompileRequest request, String jkVersion, EntryToken cp)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        appendJavacOptions(sb, request);
        // Resources copied into this output directory are not compile outputs. The line does not
        // move when a resource changes; a record stored without it does not hit.
        sb.append("mirrored:excluded\n");

        // Sources: path + content hash (FileHashMemo — at most one content read per path/thread).
        appendSources(sb, request.sources());
        for (String line : javacClasspathTokens(request, cp)) sb.append(line).append('\n');
        return Hashing.sha256Hex(sb.toString());
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
        return javacClasspathTokens(request, ClasspathAbi::token);
    }

    /** As {@link #javacClasspathTokens(CompileRequest)} with the compile classpath read through {@code cp}. */
    public static List<String> javacClasspathTokens(CompileRequest request, EntryToken cp) throws IOException {
        List<String> lines = new ArrayList<>();
        appendCpTokens(lines, "cp:", request.classpath(), cp);
        appendCpTokens(lines, "pp:", request.processorPath(), ClasspathFingerprint::entry);
        return lines;
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
        return Hashing.sha256Hex(sb.toString());
    }

    private static void appendJavacOptions(StringBuilder sb, CompileRequest request) throws IOException {
        sb.append("release:").append(request.release()).append('\n');
        sb.append("encoding:").append(SOURCE_ENCODING).append('\n');
        // The project JDK is a compile INPUT, not a consequence of --release: ForkedJavac launches
        // javac out of this very home, so it is where the platform classes (and the compiler) come
        // from. Switching jdk = 17 to 21 leaves --release alone, so without this the key never
        // moves and the build restores bytecode compiled by the old javac against the old
        // platform. Same reasoning, same rendering, as forKotlinc's `jdk:`.
        sb.append("jdk:").append(jdkToken(request.javaHome())).append('\n');
        // In argv order, never sorted: options pair with the value that follows them, so
        // `--add-modules a --limit-modules b` and `--add-modules b --limit-modules a` hold the
        // same words and are different compiles.
        sb.append("options:").append(optionsToken(request.extraOptions())).append('\n');
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

        // The classpath's ABI, order-independent: the worker joins the entries in request order,
        // but kotlinc resolves the same declarations either way.
        List<String> cp = new ArrayList<>(KotlinClasspathAbi.tokens(request.classpath(), snapshotter));
        cp.sort(Comparator.naturalOrder());
        for (String token : cp) {
            sb.append("cp:").append(token).append('\n');
        }
        // The compiler itself, by content: a different Build Tools API closure is a different
        // compiler and may emit different bytecode from the same sources and ABI.
        List<Path> worker = new ArrayList<>(request.workerClasspath());
        worker.sort(Comparator.comparing(Path::toString));
        for (Path entry : worker) {
            appendCpToken(sb, "worker:", entry);
        }
        return Hashing.sha256Hex(sb.toString());
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
        // The project JDK is a compile INPUT, not a consequence of jvmTarget: KotlincDriver hands
        // it to kotlinc as -jdk-home, and that is where the platform classes a cross-compile links
        // against come from. Switching jdk = 17 to 21 leaves jvmTarget alone, so without this the
        // key never moves and the build restores bytecode linked against the old platform.
        sb.append("jdk:").append(jdkToken(request.javaHome())).append('\n');
        // -module-name reshapes internal-member mangling in the output.
        if (request.moduleName() != null) {
            sb.append("moduleName:").append(request.moduleName()).append('\n');
        }
        // Argv order, as forJavac: a flag pairs with the value after it.
        sb.append("args:").append(optionsToken(request.extraArgs())).append('\n');

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
            result.put("worker:" + FreshnessStamp.identityKey(entry), "");
        }
        result.put("jvmTarget", Integer.toString(request.jvmTarget()));
        result.put("jdk", jdkToken(request.javaHome()));
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
        sb.append("args:").append(optionsToken(request.extraArgs())).append('\n');

        // The hashed set IS the spec's SOURCE set (GroovycInputs): explicit sources plus every
        // .java the roots feed joint resolution — an edit to a swept file invalidates the key
        // just like an explicit source, and the worker compiles exactly what was hashed.
        appendSources(sb, GroovycInputs.compileSet(request));
        for (String line : groovycClasspathTokens(request, cp)) sb.append(line).append('\n');
        return Hashing.sha256Hex(sb.toString());
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
        appendCpTokens(lines, "cp:", request.classpath(), cp);
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
     * <p>The producer's identity is {@link BuildIdentity#buildId()}, the running engine archive.
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
        sb.append("producer:").append(BuildIdentity.buildId()).append('\n');
        List<String> sorted = new ArrayList<>(inputTokens);
        sorted.sort(Comparator.naturalOrder());
        for (String t : sorted) sb.append("in:").append(t).append('\n');
        return Hashing.sha256Hex(sb.toString());
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
        snapshotEntries(result, "cp:", request.classpath(), ClasspathAbi::token);
        snapshotEntries(result, "pp:", request.processorPath(), ClasspathFingerprint::entry);
        result.put("release", Integer.toString(request.release()));
        result.put("options", optionsToken(request.extraOptions()));
        // The key hashes the JDK, so the why-rebuilt diff has to be able to name it: without this
        // a JDK switch reads as "nothing changed, rebuilt anyway".
        result.put("jdk", jdkToken(request.javaHome()));
        return result;
    }

    /** As {@link #snapshotInputs(CompileRequest)} for a Groovy compile: its source set and its token lines. */
    public static Map<String, String> snapshotInputs(GroovycRequest request) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        snapshotSources(result, GroovycInputs.compileSet(request));
        snapshotEntries(result, "cp:", request.classpath(), ClasspathAbi::token);
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
     * Identity of the JDK an action compiles against, from its {@code release} file — one small
     * property file carrying {@code JAVA_VERSION}, {@code IMPLEMENTOR} and {@code OS_ARCH}, i.e.
     * exactly the facts that decide which platform classes the compiler sees. Content, not path,
     * so a point-release upgraded in place (or reached through a stable {@code <vendor>-<major>}
     * pointer that has been repointed) still moves the key. Deliberately NOT a tree fingerprint:
     * a JDK is tens of thousands of files and this runs on every compile. A home with no readable
     * release file keys its install directory ({@link #installDirToken}).
     *
     * <p>The one JDK-identity convention in the tree: {@link #forJavac}, {@link #forKotlinc} and
     * the {@code jdk:} token both {@code PlannerPlugin} arms add to their {@link #forArtifact}
     * bags all render it through here, so a second spelling cannot appear. A null home
     * — a request that names no project JDK — keys the literal {@code none}, which is a value no
     * real home can produce, rather than silently collapsing onto whichever JDK ran last.
     */
    public static String jdkToken(@Nullable Path javaHome) throws IOException {
        if (javaHome == null) return "none";
        Path abs = javaHome.toAbsolutePath().normalize();
        Path release = abs.resolve("release");
        return Files.isRegularFile(release) ? FileHashMemo.contentHash(release) : installDirToken(abs);
    }

    /**
     * A release-less home by the two segments naming its install directory — above the {@code
     * Contents/Home} every macOS bundle ends in, which would otherwise spell every JDK alike — and
     * the size of its {@code lib/modules} image, the one cheap fact that moves with its content.
     */
    private static String installDirToken(Path home) throws IOException {
        Path install = home;
        if (home.endsWith(Path.of("Contents", "Home"))) {
            Path contents = home.getParent();
            Path bundle = contents == null ? null : contents.getParent();
            if (bundle != null) install = bundle;
        }
        Path modules = home.resolve("lib").resolve("modules");
        String image = Files.isRegularFile(modules) ? ":modules=" + Files.size(modules) : "";
        return "home:" + PortablePath.of(install) + image;
    }

    /**
     * How a classpath entry is spelled in a key. {@link ClasspathAbi#token} for a compile
     * classpath; the forecast substitutes a view that answers a tree not yet on disk with the
     * token the build will read once it has restored it.
     */
    @FunctionalInterface
    public interface EntryToken {
        String of(Path entry) throws IOException;
    }

    /**
     * One classpath/processorpath token. Identity is content (lock digest / file hash), not the
     * on-disk path — the same jar may live under the Maven local repo or {@code repos/<name>/}.
     */
    private static void appendCpToken(StringBuilder sb, String prefix, Path entry) throws IOException {
        sb.append(prefix).append(ClasspathFingerprint.entry(entry)).append('\n');
    }

    /** {@code prefix + token} per entry, sorted by path so list order cannot move a key. */
    private static void appendCpTokens(List<String> into, String prefix, List<Path> entries, EntryToken token)
            throws IOException {
        List<Path> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Path::toString));
        for (Path entry : sorted) into.add(prefix + token.of(entry));
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
     * maintenance ({@code jk clean --force}) can recompute the tags for a project's output dirs and
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
