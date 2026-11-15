// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.SnapshotQualifier;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** The durable memo of a request's {@link #variant}. */
    static Path file(Path entryDir, BuildGraph.Result graph, boolean skipTests, @Nullable String profile) {
        return file(entryDir, variant(entryDir, graph, skipTests, profile));
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
                .append('\0')
                // A release's memo is its own: its modules package to plain-version jars.
                .append(SnapshotQualifier.active() ? '1' : '0')
                .append('\0');
        key.append(MemoInputs.selectionHeader()).append('\0');
        List<String> rels = new ArrayList<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder())
            rels.add(PreflightMemo.relKey(root, u.dir().toAbsolutePath().normalize()));
        Collections.sort(rels);
        for (String r : rels) key.append(r).append('\0');
        return Hashing.sha256Hex(key.toString()).substring(0, 16);
    }

    /** The memo a request reads: its {@link #variant}'s durable one, else the in-tree one. */
    static @Nullable Path resolve(Path entryDir, BuildGraph.Result graph, boolean skipTests, @Nullable String profile) {
        return resolve(entryDir, variant(entryDir, graph, skipTests, profile));
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

    /** A memo row's third column for a module the next build schedules. */
    private static final String DIRTY_BIT = "1";

    /**
     * Mark {@code dirs} dirty in every memo of {@code entryDir}, whichever request wrote it. A build
     * that did not finish a module green proved nothing about it, so a memo an earlier green build
     * certified must not skip it on the next: the failure stays red until a build passes it.
     */
    static void markDirty(Path entryDir, Set<Path> dirs) {
        if (dirs.isEmpty()) return;
        Path root = entryDir.toAbsolutePath().normalize();
        Set<String> rels = new HashSet<>();
        for (Path d : dirs)
            rels.add(PreflightMemo.relKey(root, d.toAbsolutePath().normalize()));
        List<Path> memos = new ArrayList<>(variantFiles(entryDir));
        Path local = PreflightMemo.memoFile(entryDir);
        if (Files.isRegularFile(local)) memos.add(local);
        for (Path file : memos) {
            try {
                StringBuilder sb = new StringBuilder();
                boolean changed = false;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    // A row: the module, its fingerprint, its dirty bit, its logic token.
                    String[] parts = line.split("\t", 4);
                    if (parts.length >= 3 && rels.contains(parts[0]) && !DIRTY_BIT.equals(parts[2])) {
                        parts[2] = DIRTY_BIT;
                        line = String.join("\t", parts);
                        changed = true;
                    }
                    sb.append(line).append('\n');
                }
                if (changed) AtomicWrites.replaceIfChanged(file, sb.toString());
            } catch (IOException | RuntimeException e) {
                // A memo that cannot be marked cannot be trusted either.
                try {
                    Files.deleteIfExists(file);
                } catch (IOException gone) {
                    Log.debug("markDirty: memo neither marked nor removed", "memo", file, "cause", gone);
                }
            }
        }
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
