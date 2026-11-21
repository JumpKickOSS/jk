// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.RepoArtifactResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the cache CAS shas named by on-disk roots ({@code actions/keys}, {@code actions/synced},
 * local-repo {@code .jk} memos) so the sweep can drop unreferenced objects. A record or manifest
 * names a blob by its bare hash; a path it holds is spelled relative to a jk root ({@code
 * cache:sha256/…}), never as an absolute CAS path, so only the hash tokens are read.
 */
public final class CacheRoots {

    private CacheRoots() {}

    /**
     * Collect every reachable sha from the on-disk roots beneath {@code actionsDir} and the CAS's
     * own {@code repos/jk-local}. No objects are touched.
     */
    public static Set<String> collect(Cas cas, Path actionsDir) throws IOException {
        return collect(cas, actionsDir, PruneYield.NEVER);
    }

    /** As {@link #collect(Cas, Path)}, stopping between files at {@code yield}'s say-so: it only reads. */
    public static Set<String> collect(Cas cas, Path actionsDir, PruneYield yield) throws IOException {
        Set<String> refs = new HashSet<>();
        for (ActionTree root : List.of(ActionTree.KEYS, ActionTree.SYNCED)) {
            Path dir = root.under(actionsDir);
            if (Files.isDirectory(dir)) scanTextFilesRecursively(dir, refs, yield);
        }
        // repos/jk-local is a PUBLISH DESTINATION (jk install / jk publish local), not a derived
        // cache: a freshly published worker is legitimately unreferenced by any action until the
        // first build consumes it. Its .jk memos are roots so a leftover store-CAS copy of those
        // bytes is not swept. Maven-layout files themselves are never deleted by CAS sweep.
        List<Path> firstPartyRepos = List.of(cas.root().resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL));
        for (Path localRepo : firstPartyRepos) {
            if (!Files.isDirectory(localRepo)) continue;
            PathUtil.forEachRegularFile(localRepo, (file, attrs) -> {
                yield.check();
                if (!file.getFileName().toString().endsWith(".jk")) return;
                ArtifactMemo.read(file).map(ArtifactMemo::sha256).ifPresent(refs::add);
            });
        }
        return refs;
    }

    /** Walk {@code dir}, read every regular file as text, pull its sha tokens into {@code refs}. */
    private static void scanTextFilesRecursively(Path dir, Set<String> refs, PruneYield yield) throws IOException {
        // Guard G45: regular-file-ness comes from the attributes the walk already read.
        PathUtil.forEachRegularFile(dir, (file, attrs) -> {
            yield.check();
            String body;
            try {
                body = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Skip binaries / unreadable files; if our text roots
                // ever go binary we'll need a per-source parser anyway.
                return;
            }
            addExplicitShaTokens(body, refs);
        });
    }

    /**
     * Action records emit lines like {@code INPUT <sha> <path>} and {@code REF <sha>} (sync
     * manifests). Pull those direct mentions.
     */
    private static void addExplicitShaTokens(String body, Set<String> refs) {
        // 64-char lowercase hex, surrounded by start-of-line / whitespace /
        // a `sha256:` prefix, with the `/<bytes>` size of an output line allowed
        // after it. Anchor on whitespace either side so we don't catch hex
        // fragments inside longer strings.
        Matcher m = EXPLICIT_SHA.matcher(body);
        while (m.find()) {
            refs.add(m.group(1));
        }
    }

    /**
     * 64 hex chars, optionally preceded by {@code sha256:} and followed by {@code /<bytes>}, the
     * size an action record writes after each output blob.
     */
    private static final Pattern EXPLICIT_SHA =
            Pattern.compile("(?:^|[\\s:])(?:sha256:)?([0-9a-f]{64})(?:/\\d+)?(?:$|\\s)", Pattern.MULTILINE);
}
