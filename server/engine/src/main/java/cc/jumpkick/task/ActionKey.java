// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycInputs;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.BuildIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Builds {@code SHA-256(action) → outputs} keys for the {@link ActionCache}.
 *
 * <p>The action key for a javac invocation is a stable hash of:
 *
 * <ul>
 * <li>the task identifier (e.g. {@code "compile-main"})
 * <li>jk version
 * <li>{@code --release}, the pinned source encoding, and any extra javac options
 * <li>the project JDK's identity ({@link #jdkToken})
 * <li>each source file's module-relative path and SHA-256 (so editing a file invalidates the key,
 *     and two checkouts of the same module compute the same key)
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

    private ActionKey() {}

    public static String forJavac(String taskId, CompileRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        appendJavacOptions(sb, request);

        // Sources: path + content hash (FileHashMemo — at most one content read per path/thread).
        appendSources(sb, request.sources());
        for (String line : javacClasspathTokens(request)) sb.append(line).append('\n');
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
        List<String> lines = new ArrayList<>();
        appendCpTokens(lines, "cp:", request.classpath(), ClasspathAbi::token);
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
        sb.append("options:").append(String.join(",", request.extraOptions())).append('\n');
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
     * jvm target + the project JDK + the free args in order + each source's content hash + classpath paths
     * (both the compilation classpath and the worker's Build Tools API closure — whose CAS paths
     * encode the compiler version, so a compiler bump invalidates the key).
     */
    public static String forKotlinc(String taskId, KotlincRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
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
        sb.append("args:").append(String.join(",", request.extraArgs())).append('\n');

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

        appendSources(sb, request.sources());

        List<Path> cp = new ArrayList<>(request.classpath());
        cp.addAll(request.workerClasspath());
        cp.sort(Comparator.comparing(Path::toString));
        for (Path entry : cp) {
            appendCpToken(sb, "cp:", entry); // dirs tree-hashed
        }
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * Action key for a Groovy worker invocation. Same shape as {@link #forKotlinc}: task + jk
     * version + jvm target + the free args in order + each source's content hash + Java-source-root file
     * hashes (they feed joint resolution) + classpath paths (both the compilation classpath and the
     * worker's Groovy closure — whose CAS paths encode the compiler version, so a compiler bump
     * invalidates the key).
     */
    public static String forGroovyc(String taskId, GroovycRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("jvmTarget:").append(request.jvmTarget()).append('\n');
        sb.append("args:").append(String.join(",", request.extraArgs())).append('\n');

        // The hashed set IS the spec's SOURCE set (GroovycInputs): explicit sources plus every
        // .java the roots feed joint resolution — an edit to a swept file invalidates the key
        // just like an explicit source, and the worker compiles exactly what was hashed.
        appendSources(sb, GroovycInputs.compileSet(request));
        for (String line : groovycClasspathTokens(request)) sb.append(line).append('\n');
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
        List<String> lines = new ArrayList<>();
        appendCpTokens(lines, "cp:", request.classpath(), ClasspathAbi::token);
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
     * Snapshot of inputs that produced an action — for {@code jk why-rebuilt} diffs. Source hashes
     * reuse {@link FileHashMemo#contentHash} so a prior {@link #forJavac} on the same thread does
     * not re-read file bytes. Classpath entries are keyed by path and valued by the very token the
     * key hashed ({@link #javacClasspathTokens}), so a body-only dependency change diffs as
     * "nothing changed" and an API change names the entry that moved.
     */
    public static Map<String, String> snapshotInputs(CompileRequest request) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        snapshotSources(result, request.sources());
        snapshotEntries(result, "cp:", request.classpath(), ClasspathAbi::token);
        snapshotEntries(result, "pp:", request.processorPath(), ClasspathFingerprint::entry);
        result.put("release", Integer.toString(request.release()));
        result.put("options", String.join(",", request.extraOptions()));
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
        result.put("args", String.join(",", request.extraArgs()));
        return result;
    }

    private static void snapshotSources(Map<String, String> into, List<Path> sources) throws IOException {
        List<Path> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator.comparing(Path::toString));
        for (Path src : sorted) {
            Path abs = src.toAbsolutePath().normalize();
            into.put(abs.toString(), FileHashMemo.contentHash(abs));
        }
    }

    private static void snapshotEntries(Map<String, String> into, String prefix, List<Path> entries, EntryToken token)
            throws IOException {
        List<Path> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Path::toString));
        for (Path entry : sorted) {
            into.put(prefix + entry.toAbsolutePath().normalize(), token.of(entry));
        }
    }

    /**
     * Identity of the JDK an action compiles against, from its {@code release} file — one small
     * property file carrying {@code JAVA_VERSION}, {@code IMPLEMENTOR} and {@code OS_ARCH}, i.e.
     * exactly the facts that decide which platform classes the compiler sees. Content, not path,
     * so a point-release upgraded in place (or reached through a stable {@code <vendor>-<major>}
     * pointer that has been repointed) still moves the key. Deliberately NOT a tree fingerprint:
     * a JDK is tens of thousands of files and this runs on every compile. A directory with no
     * readable release file keys its directory name, the one fact about it that is not a location.
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
        return Files.isRegularFile(release) ? FileHashMemo.contentHash(release) : PortablePath.of(abs);
    }

    /** How one classpath entry is spelled in a key: by ABI, or by content. */
    private interface EntryToken {
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
     * Qualify a base task id (e.g. {@code compile-main}) with a stable tag derived from a
     * module-unique directory (the compile output dir), so the {@link ActionCache} {@code
     * tasks/<taskId>} pointer doesn't collide across projects or workspace modules that share the
     * same base task name. This tag is the only place a location enters the cache: the action key
     * hashes module-relative paths and content, so two modules with identical inputs share one key
     * on purpose, and the tag is what keeps their {@code tasks/} pointers apart.
     */
    public static String qualifiedTaskId(String base, @Nullable Path moduleDir) {
        return base + "@" + taskTag(moduleDir);
    }

    /**
     * The 12-hex-char tag {@link #qualifiedTaskId} appends after {@code @} — a stable hash of a
     * module-unique directory. Exposed so cache maintenance ({@code jk clean --force}) can recompute
     * the tags for a project's output dirs and match every {@code tasks/<base>@<tag>} pointer that
     * belongs to it, regardless of the base task name.
     */
    public static String taskTag(@Nullable Path moduleDir) {
        Path p = (moduleDir == null ? Path.of("") : moduleDir).toAbsolutePath().normalize();
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
}
