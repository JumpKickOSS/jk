// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Everything that can change a formatted byte, in one digest — the sole owner of what {@code jk
 * format} is keyed by.
 *
 * <p>Both format stores are named by {@link #digest()}: the engine's mtime/size
 * {@link FormatFreshnessIndex} <em>is</em> the file {@code <digest>.idx}, and the worker's per-file
 * stamp folds the same digest in with the file's bytes ({@code FormatStampCache}). The host owns it
 * because only the host knows the whole configuration — the ktfmt width, which google-java-format
 * backs {@code removeUnusedImports}, and the worker jar's own content. It travels to the worker as
 * one spec field, so a run cannot cache under one notion of "the config" and skip under another.
 *
 * <p>That split is exactly how {@code kotlinMaxWidth} and the {@code removeUnusedImports} GJF
 * version came to be in neither key: two hand-maintained field lists, in two modules, both
 * incomplete. A new input is one component here and both stores inherit it — which is how
 * {@code compileClasspath} arrived: one field, one line in {@link #digest()}, no redesign.
 */
public record FormatKey(
        String javaStyle,
        String javaVersion,
        String kotlinStyle,
        String kotlinVersion,
        int kotlinMaxWidth,
        boolean optimizeImports,
        boolean importOrder,
        boolean removeUnusedImports,
        // google-java-format's version — what `removeUnusedImports` actually runs, whatever the style is.
        String removeUnusedVersion,
        Path rewriteConfig,
        // What `optimize-imports` resolves type names against; see #classpathIdentity.
        List<Path> compileClasspath,
        Path workerJar) {

    private static final String VERSION = "format-key-v1";

    public FormatKey {
        compileClasspath = compileClasspath == null ? List.of() : List.copyOf(compileClasspath);
    }

    /** Hex SHA-256 over the whole configuration; the name of both format stores' entries. */
    public String digest() {
        return Hashing.sha256Hex(String.join(
                "\n",
                VERSION,
                "java-style:" + nullToEmpty(javaStyle),
                "java-version:" + nullToEmpty(javaVersion),
                "kotlin-style:" + nullToEmpty(kotlinStyle),
                "kotlin-version:" + nullToEmpty(kotlinVersion),
                "kotlin-max-width:" + kotlinMaxWidth,
                "optimize-imports:" + optimizeImports,
                "import-order:" + importOrder,
                "remove-unused-imports:" + removeUnusedImports,
                // Only when the step runs: switching google-java-format while the step is off
                // changes nothing about the output, and re-formatting the tree for it is waste.
                "remove-unused-version:" + (removeUnusedImports ? nullToEmpty(removeUnusedVersion) : ""),
                // Same rule as the GJF version: inert while the pass that reads it is off.
                "compile-classpath:" + (optimizeImports ? classpathIdentity() : ""),
                "rewrite:" + rewriteHash(),
                "worker:" + workerIdentity()));
    }

    /**
     * The classpath's <em>entry list</em>, not its content. What decides whether {@code
     * cc.jumpkick.foo.Bar} shortens is whether the name resolves at all, and that is a property of
     * which entries are on the path — a set fixed by the lockfile. Hashing the bytes instead would
     * re-key on every build, because the class-output directories on this path are rewritten by
     * every compile, and would re-format the whole tree for output that cannot have changed.
     */
    private String classpathIdentity() {
        if (compileClasspath.isEmpty()) return "none";
        StringBuilder joined = new StringBuilder();
        for (Path entry : compileClasspath) {
            joined.append(entry.toAbsolutePath().normalize()).append('\n');
        }
        return Hashing.sha256Hex(joined.toString());
    }

    private String rewriteHash() {
        if (rewriteConfig == null || !Files.isRegularFile(rewriteConfig)) return "none";
        try {
            return Hashing.sha256Hex(rewriteConfig);
        } catch (IOException e) {
            return "unreadable";
        }
    }

    /**
     * Worker identity is the content hash of the thin jar <em>and</em> of its sibling Maven POM.
     * OpenRewrite/Spotless live on the POM runtime closure; fingerprinting only the thin jar left
     * freshness green across formatter dependency upgrades.
     *
     * <p>Content, not {@code path:size:mtime}: the same worker re-materialised into the store is
     * the same formatter, and keying on its stat minted a fresh 162 KB index every time one was
     * re-fetched or re-installed. Neither is the path part of it — two byte-identical jars format
     * identically wherever they sit. Nor would {@code path:size} do, because a rebuilt worker of
     * the same length and different formatter code is exactly the case the POM hash exists to
     * catch. One SHA-256 of a few MB, once per {@code jk format}, on a command that is about to
     * fork a JVM.
     */
    private String workerIdentity() {
        if (workerJar == null || !Files.isRegularFile(workerJar)) return "none";
        return contentIdentity(workerJar) + "|" + contentIdentity(siblingPom(workerJar));
    }

    private static Path siblingPom(Path workerJar) {
        String name =
                workerJar.getFileName() == null ? "" : workerJar.getFileName().toString();
        if (!name.endsWith(".jar")) return workerJar.resolveSibling(name + ".pom");
        return workerJar.resolveSibling(name.substring(0, name.length() - 4) + ".pom");
    }

    /** SHA-256 of the file, or a path-derived stand-in when it cannot be read (fails dirty). */
    private static String contentIdentity(Path path) {
        if (path == null || !Files.isRegularFile(path)) return "none";
        try {
            return Hashing.sha256Hex(path);
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
