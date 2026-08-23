// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Collects CAS shas named by on-disk roots ({@code actions/keys}, {@code actions/synced},
 * {@code tools/envs}, local-repo {@code .jk} memos) so the sweep can drop unreferenced
 * objects. Pattern-based: any CAS-looking path counts as reachable.
 */
public final class CacheRoots {

    private CacheRoots() {}

    /**
     * Collect every reachable sha from the on-disk roots beneath the given action + tools
     * directories. The CAS is consulted only to recognise its own path layout — no objects are
     * touched.
     */
    public static Set<String> collect(Cas cas, Path actionsDir, Path toolsDir) throws IOException {
        return collect(cas, actionsDir, toolsDir, Set.of());
    }

    /**
     * Like {@link #collect(Cas, Path, Path)}, but treating {@code ignoreKeyFiles} as already
     * deleted. Dry-run parity: a dry Class-C purge leaves its key files on disk, and counting
     * them as roots hides every heavy blob from the following dry sweep — the real run then
     * frees GiB a dry run reported as "40 MiB reclaimable".
     */
    public static Set<String> collect(Cas cas, Path actionsDir, Path toolsDir, Set<Path> ignoreKeyFiles)
            throws IOException {
        Set<String> refs = new HashSet<>();
        if (Files.isDirectory(actionsDir.resolve("keys"))) {
            scanTextFilesRecursively(actionsDir.resolve("keys"), cas, refs, ignoreKeyFiles);
        }
        if (Files.isDirectory(actionsDir.resolve(Sweep.SYNCED_SUBDIR))) {
            scanTextFilesRecursively(actionsDir.resolve(Sweep.SYNCED_SUBDIR), cas, refs, Set.of());
        }
        if (Files.isDirectory(toolsDir.resolve("envs"))) {
            scanTextFilesRecursively(toolsDir.resolve("envs"), cas, refs, Set.of());
        }
        // repos/jk-local is a PUBLISH DESTINATION (installLocal / jk publish local), not a derived
        // cache: a freshly published worker is legitimately unreferenced by any action until the
        // first build consumes it. Its .jk memos are roots so a leftover store-CAS copy of those
        // bytes is not swept. Maven-layout files themselves are never deleted by CAS sweep.
        // A pre-rename repos/local not yet folded into jk-local is the same first-party store
        // under its old name — its memos are roots too until the migration completes.
        List<Path> firstPartyRepos = new ArrayList<>();
        firstPartyRepos.add(cas.root().resolve("repos").resolve(cc.jumpkick.repo.RepoArtifactResolver.JK_LOCAL));
        if (cc.jumpkick.repo.RepoArtifactStore.legacyLocalPending(cas.root())) {
            firstPartyRepos.add(cas.root().resolve("repos").resolve("local"));
        }
        for (Path localRepo : firstPartyRepos) {
            if (!Files.isDirectory(localRepo)) continue;
            try (Stream<Path> stream = Files.walk(localRepo)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file)
                            || !file.getFileName().toString().endsWith(".jk")) {
                        continue;
                    }
                    cc.jumpkick.repo.ArtifactMemo.read(file)
                            .map(cc.jumpkick.repo.ArtifactMemo::sha256)
                            .ifPresent(refs::add);
                }
            }
        }
        // Promoted blobs (ActionPromote): their Class-C action key is
        // dropped by any `jk cache clean`, so the promotion marker is their root. Markers expire
        // by age (see PROMOTED_MARKER_TTL_MILLIS) — durable, not immortal.
        Path promoted = cas.root().resolve(cc.jumpkick.cache.ActionPromote.PROMOTED_DIR);
        if (Files.isDirectory(promoted)) {
            long now = System.currentTimeMillis();
            try (Stream<Path> stream = Files.list(promoted)) {
                for (Path marker : (Iterable<Path>) stream::iterator) {
                    String name = marker.getFileName().toString();
                    if (name.length() != 64) continue;
                    try {
                        long age = now - Files.getLastModifiedTime(marker).toMillis();
                        if (age < PROMOTED_MARKER_TTL_MILLIS) refs.add(name);
                    } catch (IOException ignored) {
                        refs.add(name); // unreadable mtime — keep the blob, never eat a release
                    }
                }
            }
        }
        return refs;
    }

    /** How long a promotion keeps a blob rooted after its last (re-)release: 180 days. */
    public static final long PROMOTED_MARKER_TTL_MILLIS = 180L * 24 * 60 * 60 * 1000;

    /** Delete promotion markers past their TTL (real sweeps only — the collect stays pure). */
    public static long pruneExpiredPromotedMarkers(Cas cas) throws IOException {
        Path promoted = cas.root().resolve(cc.jumpkick.cache.ActionPromote.PROMOTED_DIR);
        if (!Files.isDirectory(promoted)) return 0;
        long now = System.currentTimeMillis();
        long pruned = 0;
        try (Stream<Path> stream = Files.list(promoted)) {
            for (Path marker : (Iterable<Path>) stream::iterator) {
                try {
                    if (now - Files.getLastModifiedTime(marker).toMillis() >= PROMOTED_MARKER_TTL_MILLIS) {
                        Files.deleteIfExists(marker);
                        pruned++;
                    }
                } catch (IOException ignored) {
                    // leave it; next sweep retries
                }
            }
        }
        return pruned;
    }

    /**
     * Walk {@code dir}, read every regular file as text, pull explicit sha tokens AND any CAS-style
     * path fragments into {@code refs}.
     */
    private static void scanTextFilesRecursively(Path dir, Cas cas, Set<String> refs, Set<Path> ignore)
            throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (!ignore.isEmpty() && ignore.contains(file)) continue;
                String body;
                try {
                    body = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // Skip binaries / unreadable files; if our text roots
                    // ever go binary we'll need a per-source parser anyway.
                    continue;
                }
                addExplicitShaTokens(body, refs);
                addPathEmbeddedShas(body, cas, refs);
            }
        }
    }

    /**
     * Action records emit lines like {@code INPUT <sha> <path>} and {@code REF <sha>} (sync
     * manifests). Pull those direct mentions.
     */
    private static void addExplicitShaTokens(String body, Set<String> refs) {
        // 64-char lowercase hex, surrounded by start-of-line / whitespace /
        // a `sha256:` prefix. Anchor on whitespace either side so we don't
        // catch hex fragments inside longer strings.
        Matcher m = EXPLICIT_SHA.matcher(body);
        while (m.find()) {
            refs.add(m.group(1));
        }
    }

    /**
     * Any absolute path that looks like {@code .../sha256/AA/BB/<rest>} is treated as a reference —
     * covers tool env JSONs, action-record {@code INPUT cp:} lines, and any other writer that stamps
     * absolute CAS paths.
     */
    private static void addPathEmbeddedShas(String body, Cas cas, Set<String> refs) {
        Matcher m = CAS_PATH.matcher(body);
        while (m.find()) {
            cas.hashFromPath(Path.of(m.group())).ifPresent(refs::add);
        }
    }

    /** 64 hex chars, optionally preceded by {@code sha256:}. */
    private static final Pattern EXPLICIT_SHA =
            Pattern.compile("(?:^|[\\s:])(?:sha256:)?([0-9a-f]{64})(?:$|[\\s\\n])", Pattern.MULTILINE);

    /**
     * A path fragment ending in the CAS layout's {@code sha256/AA/BB/<60-hex>} suffix. We anchor on
     * the literal {@code sha256/} segment so the match starts where the directory does — the caller
     * still calls {@link Cas#hashFromPath} to verify the path actually sits under the active CAS
     * root.
     */
    private static final Pattern CAS_PATH =
            Pattern.compile("(?<=[\\s\"'(])/[^\\s\"'\\n]*/sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{60}");
}
