// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.util.Hashing;
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
 * <li>{@code --release} and any extra javac options
 * <li>each source file's SHA-256 (so editing a file invalidates the key)
 * <li>each classpath entry's path — CAS paths already incorporate the content hash, so the file
 * name itself is enough to capture the dependency.
 * </ul>
 */
public final class ActionKey {

    private ActionKey() {}

    public static String forJavac(String taskId, CompileRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("release:").append(request.release()).append('\n');
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
     * jvm target + sorted free args + each source's content hash + classpath paths (both the
     * compilation classpath and the worker's Build Tools API closure — whose CAS paths encode the
     * compiler version, so a compiler bump invalidates the key).
     */
    public static String forKotlinc(String taskId, KotlincRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("jvmTarget:").append(request.jvmTarget()).append('\n');
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
            // CAS path when possible so repos/ vs sha256/ dual views do not fork the key.
            result.put("cp:" + FreshnessStamp.identityKey(cp), "");
        }
        for (Path pp : request.processorPath()) {
            result.put("pp:" + FreshnessStamp.identityKey(pp), "");
        }
        result.put("release", Integer.toString(request.release()));
        result.put("options", String.join(",", request.extraOptions()));
        return result;
    }

    /** Sorted source lines for action material — one content hash per path via {@link FileHashMemo}. */
    /**
     * One classpath/processorpath token. Jar entries are identified by path alone (CAS layout
     * encodes content); directory entries additionally carry a tree hash — a directory path
     * says nothing about its contents.
     */
    private static void appendCpToken(StringBuilder sb, String prefix, Path entry) throws IOException {
        Path p = FreshnessStamp.identityKey(entry);
        sb.append(prefix).append(p);
        if (Files.isDirectory(p)) {
            sb.append('=').append(ClasspathFingerprint.entry(p));
        }
        sb.append('\n');
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
