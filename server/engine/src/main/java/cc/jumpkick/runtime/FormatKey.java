// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * incomplete. A new input is now one component here and both stores inherit it — a compile
 * classpath (once {@code optimize-imports} wires one in) is one more component and one more line,
 * not a redesign.
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
        Path workerJar) {

    private static final String VERSION = "format-key-v1";

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
                "rewrite:" + rewriteHash(),
                "worker:" + workerIdentity()));
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
