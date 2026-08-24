// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@code SHA-256(action) → outputs} keys for the {@link ActionCache}.
 *
 * <p>The action key for a javac invocation is a stable hash of:
 *
 * <ul>
 * <li>the task identifier (e.g. {@code "compile-main"})
 * <li>jk version
 * <li>{@code --release}, the pinned source encoding, and any extra javac options
 * <li>each source file's SHA-256 (so editing a file invalidates the key)
 * <li>each classpath entry's content identity ({@code file:<sha256>} / directory tree hash)
 * </ul>
 */
public final class ActionKey {

    /**
     * The charset both javac front ends pin — {@code ZincJavaCompiler} (in the worker JVM, hence
     * its own copy of this constant) and {@link cc.jumpkick.compile.JavacRunner} each declare
     * {@code -encoding UTF-8}. Because it is a constant it can never separate two of today's keys.
     * It is hashed anyway because the charset decides the bytes: a key that omits it would serve
     * Latin-1-decoded artifacts to a UTF-8 build the day the pin moves, and a one-time recompile
     * is the whole cost of being able to move it. Move this and the front ends together.
     */
    private static final String SOURCE_ENCODING = "UTF-8";

    private ActionKey() {}

    public static String forJavac(String taskId, CompileRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("release:").append(request.release()).append('\n');
        sb.append("encoding:").append(SOURCE_ENCODING).append('\n');
        sb.append("options:");
        List<String> opts = new ArrayList<>(request.extraOptions());
        opts.sort(Comparator.naturalOrder());
        sb.append(String.join(",", opts)).append('\n');

        // Sources: path + content hash (FileHashMemo — at most one content read per path/thread).
        appendSources(sb, request.sources());

        // Classpath: CAS jar paths already include the content hash in their layout;
        // DIRECTORY entries (a sibling lane's classes dir) do not — hash their tree, or a
        // Groovy/Kotlin-only change leaves stale Java bytecode behind a key hit.
        List<Path> cp = new ArrayList<>(request.classpath());
        cp.sort(Comparator.comparing(Path::toString));
        for (Path entry : cp) {
            appendCpToken(sb, "cp:", entry);
        }

        // Processor path: a processor change can regenerate everything, so it must
        // invalidate the key (CAS paths encode the processor jar's content).
        List<Path> pp = new ArrayList<>(request.processorPath());
        pp.sort(Comparator.comparing(Path::toString));
        for (Path entry : pp) {
            appendCpToken(sb, "pp:", entry);
        }

        if (request.mixedScala()) {
            sb.append("scala:").append(request.scalaVersion()).append('\n');
            List<Path> scp = new ArrayList<>(request.compilerClasspath());
            scp.sort(Comparator.comparing(Path::toString));
            for (Path entry : scp) {
                appendCpToken(sb, "sc:", entry);
            }
        }

        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * Action key for a Kotlin worker invocation. Same shape as {@link #forJavac}: task + jk version +
     * jvm target + the project JDK + sorted free args + each source's content hash + classpath paths
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
        sb.append("args:");
        List<String> args = new ArrayList<>(request.extraArgs());
        args.sort(Comparator.naturalOrder());
        sb.append(String.join(",", args)).append('\n');

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
     * version + jvm target + sorted free args + each source's content hash + Java-source-root file
     * hashes (they feed joint resolution) + classpath paths (both the compilation classpath and the
     * worker's Groovy closure — whose CAS paths encode the compiler version, so a compiler bump
     * invalidates the key).
     */
    public static String forGroovyc(String taskId, GroovycRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("jvmTarget:").append(request.jvmTarget()).append('\n');
        sb.append("args:");
        List<String> args = new ArrayList<>(request.extraArgs());
        args.sort(Comparator.naturalOrder());
        sb.append(String.join(",", args)).append('\n');

        appendSources(sb, request.sources());

        // Java source roots feed joint resolution — hash every.java under them so an
        // edit to a swept file invalidates the key just like an explicit source would.
        List<Path> rootJava = new ArrayList<>();
        for (Path root : request.javaSourceRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(rootJava::add);
            }
        }
        appendSources(sb, rootJava);

        List<Path> cp = new ArrayList<>(request.classpath());
        cp.addAll(request.workerClasspath());
        cp.sort(Comparator.comparing(Path::toString));
        for (Path entry : cp) {
            appendCpToken(sb, "cp:", entry); // dirs tree-hashed
        }
        List<Path> pp = new ArrayList<>(request.processorPath());
        pp.sort(Comparator.comparing(Path::toString));
        for (Path entry : pp) {
            appendCpToken(sb, "pp:", entry);
        }
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * Action key for a packaging artifact (jar, fat-jar, native binary, OCI tarball, …). A stable
     * hash of the task id, jk version, and a set of pre-computed input tokens — typically {@link
     * ClasspathFingerprint} hashes of the input classes/jars plus config strings (main-class,
     * manifest, build args, toolchain version, …). The caller MUST include every input that affects
     * the produced bytes; a missing token risks serving a stale artifact.
     */
    public static String forArtifact(String taskId, String jkVersion, List<String> inputTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        List<String> sorted = new ArrayList<>(inputTokens);
        sorted.sort(Comparator.naturalOrder());
        for (String t : sorted) sb.append("in:").append(t).append('\n');
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * Snapshot of inputs that produced an action — for {@code jk why-rebuilt} diffs. Source hashes
     * reuse {@link FileHashMemo#contentHash} so a prior {@link #forJavac} on the same thread does
     * not re-read file bytes.
     */
    public static Map<String, String> snapshotInputs(CompileRequest request) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<Path> sortedSources = new ArrayList<>(request.sources());
        sortedSources.sort(Comparator.comparing(Path::toString));
        for (Path src : sortedSources) {
            Path abs = src.toAbsolutePath().normalize();
            result.put(abs.toString(), FileHashMemo.contentHash(abs));
        }
        for (Path cp : request.classpath()) {
            result.put("cp:" + FreshnessStamp.identityKey(cp), "");
        }
        for (Path pp : request.processorPath()) {
            result.put("pp:" + FreshnessStamp.identityKey(pp), "");
        }
        result.put("release", Integer.toString(request.release()));
        result.put("options", String.join(",", request.extraOptions()));
        return result;
    }

    /**
     * Identity of the JDK an action compiles against, from its {@code release} file — one small
     * property file carrying {@code JAVA_VERSION}, {@code IMPLEMENTOR} and {@code OS_ARCH}, i.e.
     * exactly the facts that decide which platform classes the compiler sees. Content, not path,
     * so a point-release upgraded in place (or reached through a stable {@code <vendor>-<major>}
     * pointer that has been repointed) still moves the key. Deliberately NOT a tree fingerprint:
     * a JDK is tens of thousands of files and this runs on every compile. A directory with no
     * readable release file keys its absolute path — the same string that reaches the tool.
     */
    private static String jdkToken(Path javaHome) throws IOException {
        Path abs = javaHome.toAbsolutePath().normalize();
        Path release = abs.resolve("release");
        return Files.isRegularFile(release) ? FileHashMemo.contentHash(release) : abs.toString();
    }

    /**
     * One classpath/processorpath token. Identity is content (lock digest / file hash), not the
     * on-disk path — the same jar may live under the Maven local repo or {@code repos/<name>/}.
     */
    private static void appendCpToken(StringBuilder sb, String prefix, Path entry) throws IOException {
        sb.append(prefix).append(ClasspathFingerprint.entry(entry)).append('\n');
    }

    private static void appendSources(StringBuilder sb, List<Path> sources) throws IOException {
        List<Path> sortedSources = new ArrayList<>(sources);
        sortedSources.sort(Comparator.comparing(Path::toString));
        for (Path src : sortedSources) {
            Path abs = src.toAbsolutePath().normalize();
            sb.append("source:")
                    .append(abs)
                    .append(':')
                    .append(FileHashMemo.contentHash(abs))
                    .append('\n');
        }
    }

    /**
     * Qualify a base task id (e.g. {@code compile-main}) with a stable tag derived from a
     * module-unique directory (the compile output dir), so the {@link ActionCache} {@code
     * tasks/<taskId>} pointer doesn't collide across projects or workspace modules that share the
     * same base task name. The action key itself is already project-unique (it hashes absolute source
     * and classpath paths); this only disambiguates the per-task pointer.
     */
    public static String qualifiedTaskId(String base, Path moduleDir) {
        return base + "@" + taskTag(moduleDir);
    }

    /**
     * The 12-hex-char tag {@link #qualifiedTaskId} appends after {@code @} — a stable hash of a
     * module-unique directory. Exposed so cache maintenance ({@code jk clean --force}) can recompute
     * the tags for a project's output dirs and match every {@code tasks/<base>@<tag>} pointer that
     * belongs to it, regardless of the base task name.
     */
    public static String taskTag(Path moduleDir) {
        Path p = moduleDir.toAbsolutePath().normalize();
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
