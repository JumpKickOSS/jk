// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Log;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathAbi;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.KotlinClasspathAbi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The outputs a build brings back from its caches before anything keys on them, read by the
 * forecast walk as the bytes that return rather than as their absence. {@code jk clean} takes
 * every classes tree, fixtures tree, jar and binary under {@code target/}; the build restores each
 * from the action record its current inputs name and only then runs the consumers that hash it.
 * A read-only forecast sees the wiped tree, so each walked module publishes here what its own
 * records say will come back — a tree's ABI token and content identity, a jar's payload sha — and
 * every arm that keys on a sibling reads through this ledger. The walk runs in dependency order,
 * so a producer has published before any consumer asks.
 *
 * <p>Nothing here is ever a guess: an output no current record describes stays unpublished and
 * keys as missing, which is the pessimistic answer — a forecast of work the build then skips,
 * never a false hit.
 */
final class RestoredOutputs {

    private final ActionCache actionCache;

    // Concurrent, because the forecast walks one wave of independent modules at a time: a
    // producer publishes here before any consumer's wave reads it, but several producers publish
    // at once. Nothing in a wave reads what another member of it writes.

    /** Wiped jar → the CAS sha of its payload in the record its current package key names. */
    private final Map<Path, String> jarShas = new ConcurrentHashMap<>();

    /** Wiped tree → the {@code abi:} token of the tree its record restores. */
    private final Map<Path, String> treeAbi = new ConcurrentHashMap<>();

    /** Wiped tree → the {@code dir:} content identity of the tree its record restores. */
    private final Map<Path, String> treeIdentity = new ConcurrentHashMap<>();

    RestoredOutputs(ActionCache actionCache) {
        this.actionCache = actionCache;
    }

    /** The pinned jar shas, for the packaging arms that fingerprint dependency jars by content. */
    Map<Path, String> jarShas() {
        return jarShas;
    }

    /** Pin the content of a wiped jar from the record its current package key names. */
    void pinJar(Path jar, String sha) {
        jarShas.put(key(jar), sha);
    }

    /**
     * Publish a tree that is not whole on disk as the outputs its compile records restore, merged
     * with the resource roots {@code copy-resources} lays over them: {@code outputs} maps
     * tree-relative paths to content shas the action cache's CAS holds. Both readings of the tree
     * are memoized under the identity the restored tree will have, so the build's later sighting
     * of the live tree is a lookup.
     */
    void projectTree(Path tree, Map<String, String> outputs, List<Path> resourceRoots) {
        projectTree(tree, outputs, resourceRoots, Map.of());
    }

    /**
     * As above for a tree that also receives {@code copiedFiles} (tree-relative path to content
     * sha) after the resource roots — a plugin worker's module-root manifest.
     */
    void projectTree(
            Path tree, Map<String, String> outputs, List<Path> resourceRoots, Map<String, String> copiedFiles) {
        Path abs = key(tree);
        try {
            treeIdentity.put(
                    abs, ClasspathFingerprint.entryFromCompileAndResources(outputs, resourceRoots, copiedFiles));
            treeAbi.put(abs, ClasspathAbi.tokenFromOutputs(outputs, resourceRoots, copiedFiles, actionCache.cas()));
        } catch (IOException e) {
            // Without the projection a consumer keys on the tree's absence: the pessimistic
            // answer, never a false hit.
            treeIdentity.remove(abs);
            treeAbi.remove(abs);
            Log.debug("projectTree: consumers key on the tree as it is", e);
        }
    }

    /**
     * Publish {@code tree} as the merge of the outputs the records {@code keys} name — each
     * compiler's outputs copied over the one before, the resource roots and {@code copiedFiles}
     * over them all — when every key has a record. A null key or a record that is gone publishes
     * nothing: the tree cannot be described, so its consumers key on what is there.
     */
    void projectFromRecords(
            Path tree, List<@Nullable String> keys, List<Path> resourceRoots, Map<String, String> copiedFiles)
            throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String key : keys) {
            if (key == null) return;
            var record = actionCache.lookup(key);
            if (record.isEmpty()) return;
            merged.putAll(record.get().outputs());
        }
        if (merged.isEmpty() && resourceRoots.isEmpty() && copiedFiles.isEmpty()) return;
        projectTree(tree, merged, resourceRoots, copiedFiles);
    }

    /**
     * Publish a compiler's private output tree (kotlinc's, groovyc's) when it is gone and its
     * record is known: in a mixed module javac's classpath names it, so compile-main keys on the
     * tree the build restores there first.
     */
    void projectFromRecord(Path tree, @Nullable String key) {
        if (key == null) return;
        try {
            if (TaskForecaster.classesDirHasContent(tree)) return;
            actionCache.lookup(key).ifPresent(record -> projectTree(tree, record.outputs(), List.of()));
        } catch (IOException e) {
            Log.debug("projectFromRecord: compile-main keys on the tree as it is", e);
        }
    }

    /** True when {@code tree} is on disk or the build this walk prices restores it before it is read. */
    boolean willBePresent(Path tree) {
        Path abs = key(tree);
        return Files.isDirectory(abs) || treeIdentity.containsKey(abs);
    }

    /** The projected {@code dir:} identity of {@code tree}, or null when nothing published one. */
    @Nullable
    String projectedIdentity(Path tree) {
        return treeIdentity.get(key(tree));
    }

    /**
     * A javac classpath entry as the build will read it: a published tree by the token of the tree
     * that comes back, a pinned jar by the ABI of the payload the build restores, everything else
     * by what is on disk.
     */
    ActionKey.EntryToken abiToken() {
        return entry -> {
            Path abs = key(entry);
            String projected = treeAbi.get(abs);
            if (projected != null) return projected;
            Path blob = pinnedBlob(abs);
            return blob != null ? ClasspathAbi.token(blob) : ClasspathAbi.token(entry);
        };
    }

    /**
     * A classpath entry's content identity as the build will read it: a published tree by the
     * identity of the tree that comes back, a pinned jar as the live step fingerprints the
     * restored file ({@code file:<content sha>}), everything else by what is on disk.
     */
    ClasspathFingerprint.EntryIdentity identity() {
        return entry -> {
            Path abs = key(entry);
            String projected = treeIdentity.get(abs);
            if (projected != null) return projected;
            if (Files.exists(abs)) return ClasspathFingerprint.entry(abs);
            String sha = jarShas.get(abs);
            if (sha != null && Files.isRegularFile(actionCache.cas().pathFor(sha))) return "file:" + sha;
            return ClasspathFingerprint.entry(abs);
        };
    }

    /**
     * The Kotlin classpath reading of the forecast: never forks the worker, and reads a wiped
     * entry under the identity of the bytes that come back, so the snapshot digest the build
     * memoized against those bytes answers here.
     */
    KotlinClasspathAbi.Snapshotter kotlinSnapshotter() {
        return KotlinClasspathAbi.memoizedOnly(identity());
    }

    /** The CAS payload of a pinned jar that is not on disk, when the blob is still there. */
    private @Nullable Path pinnedBlob(Path abs) {
        if (Files.exists(abs)) return null;
        String sha = jarShas.get(abs);
        if (sha == null) return null;
        Cas cas = actionCache.cas();
        Path blob = cas.pathFor(sha);
        return Files.isRegularFile(blob) ? blob : null;
    }

    private static Path key(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
