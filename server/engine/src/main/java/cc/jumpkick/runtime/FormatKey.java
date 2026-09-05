// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
 * <p>A new input is one component here and both stores inherit it — which is how
 * {@link #indexIdentity} arrived: the set of names {@code optimize-imports} can shorten is part of
 * the configuration, exactly as the compile classpath was before it.
 */
public record FormatKey(
        @Nullable String javaStyle,
        String javaVersion,
        @Nullable String kotlinStyle,
        String kotlinVersion,
        int kotlinMaxWidth,
        boolean optimizeImports,
        boolean importOrder,
        boolean removeUnusedImports,
        // google-java-format's version — what `removeUnusedImports` actually runs, whatever the style is.
        String removeUnusedVersion,
        String scalaVersion,
        // The sources the type index is built from; see #indexIdentity.
        List<Path> indexFiles,
        Path workerJar) {

    private static final String VERSION = "format-key-v3";

    public FormatKey {
        indexFiles = indexFiles == null ? List.of() : List.copyOf(indexFiles);
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
                "scala-version:" + nullToEmpty(scalaVersion),
                // Same rule as the GJF version: inert while the pass that reads it is off.
                "index:" + (optimizeImports ? indexIdentity() : ""),
                "worker:" + workerIdentity()));
    }

    /**
     * The index's <em>file list</em>, not its content. What decides whether {@code cc.jumpkick.foo.Bar}
     * shortens is whether {@code Bar} is a name the index knows and whether it is unique — a property
     * of which sources exist, not of what is inside them.
     *
     * <p>Without this component both stores are blind to the one input that matters most. A file is
     * stamped clean under the set of types that were nameable when it was formatted; delete the
     * second {@code Foo} and the surviving {@code com.a.Foo} reference should now shorten, but the
     * referring file's bytes have not changed, so the mtime/size index never sends it to the worker
     * and the key never changes. The tree keeps a fully-qualified name the formatter claims it can
     * fix — a house rule enforced by a no-op, which is the defect the classpath component this
     * replaces was itself introduced to end.
     *
     * <p>Paths rather than bytes, for the same reason the classpath hashed entries and not content:
     * hashing content would re-key on every edit and re-format the whole tree for a change that
     * cannot affect anyone else's imports. Java ties a public type's name to its file name, so
     * adding, deleting, renaming or moving a type moves this digest. A second type declared inside
     * an existing file is the acknowledged gap: renaming that one does not re-key, and the tree
     * converges on the next run that touches the file for any other reason.
     */
    private String indexIdentity() {
        if (indexFiles.isEmpty()) return "none";
        List<String> normalized = new ArrayList<>(indexFiles.size());
        for (Path entry : indexFiles) {
            normalized.add(entry.toAbsolutePath().normalize().toString());
        }
        // Sorted: the walk order is already deterministic, but the key must not depend on that.
        Collections.sort(normalized);
        return Hashing.sha256Hex(String.join("\n", normalized));
    }

    /**
     * Worker identity is the content hash of the thin jar <em>and</em> of its sibling Maven POM.
     * Spotless lives on the POM runtime closure; fingerprinting only the thin jar left
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

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
