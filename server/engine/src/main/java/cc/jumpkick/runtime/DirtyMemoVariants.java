// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Where {@link PreflightMemo}'s dirty memo lives: one durable file per request variant, a bounded set. */
final class DirtyMemoVariants {

    private DirtyMemoVariants() {}

    /**
     * Durable dirty-memo under the product cache, so {@code jk clean} (which wipes {@code target/})
     * does not erase input fingerprints: one per request variant ({@link #variant}), so commands an
     * agent alternates ({@code jk test -m x}, {@code jk build}, {@code -S}, {@code --profile})
     * each keep their own instead of overwriting one slot the next command then misses.
     */
    static Path file(Path entryDir, String variant) {
        return PreflightMemo.durablePreflightDir(entryDir).resolve("dirty-memo-" + variant + ".txt");
    }

    /** How many request variants' memos one entry dir keeps; the least recently written go first. */
    static final int MAX_VARIANTS = 8;

    /**
     * The request variant a memo answers for: the test mode, the profile, the module selection and
     * the modules the graph holds, which together decide which rows a memo carries.
     */
    static String variant(Path entryDir, BuildGraph.Result graph, boolean skipTests, @Nullable String profile) {
        Path root = entryDir.toAbsolutePath().normalize();
        StringBuilder key = new StringBuilder();
        key.append(skipTests ? '1' : '0')
                .append('\0')
                .append(PreflightMemo.profileHeader(profile))
                .append('\0');
        key.append(MemoInputs.selectionHeader()).append('\0');
        List<String> rels = new ArrayList<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder())
            rels.add(PreflightMemo.relKey(root, u.dir().toAbsolutePath().normalize()));
        Collections.sort(rels);
        for (String r : rels) key.append(r).append('\0');
        return Hashing.sha256Hex(key.toString()).substring(0, 16);
    }

    /** The variant's durable memo (survives clean), else the in-tree target memo. */
    static @Nullable Path resolve(Path entryDir, String variant) {
        Path durable = file(entryDir, variant);
        if (Files.isRegularFile(durable)) return durable;
        Path local = PreflightMemo.memoFile(entryDir);
        return Files.isRegularFile(local) ? local : null;
    }

    /** The newest durable memo of any variant, else the in-tree one; what a reader that names no request inspects. */
    static @Nullable Path newest(Path entryDir) {
        List<Path> variants = variantFiles(entryDir);
        if (!variants.isEmpty()) return variants.getFirst();
        Path local = PreflightMemo.memoFile(entryDir);
        return Files.isRegularFile(local) ? local : null;
    }

    /** The entry dir's durable variant memos, newest first. */
    private static List<Path> variantFiles(Path entryDir) {
        Path dir = PreflightMemo.durablePreflightDir(entryDir);
        record Stamped(Path path, long millis) {}
        List<Stamped> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (p, attrs) -> {
                String name = p.getFileName().toString();
                if (attrs.isRegularFile() && name.startsWith("dirty-memo-") && name.endsWith(".txt")) {
                    out.add(new Stamped(p, attrs.lastModifiedTime().toMillis()));
                }
                return true;
            });
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparingLong(Stamped::millis).reversed());
        return out.stream().map(Stamped::path).toList();
    }

    /** Drops all but the {@link #MAX_VARIANTS} newest variant memos of {@code entryDir}. */
    static void prune(Path entryDir) {
        List<Path> variants = variantFiles(entryDir);
        for (int i = MAX_VARIANTS; i < variants.size(); i++) {
            try {
                Files.deleteIfExists(variants.get(i));
            } catch (IOException ignored) {
                // the next store tries again
            }
        }
    }
}
