// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard's file tree, one directory at a time, and the path finder behind its filter box.
 * Nothing here reads a whole checkout into one response: {@link #list} answers one directory in
 * pages, and {@link #find} returns a capped set of matches. Both walk the same entries {@link
 * WorkspaceFileAccess#servable} admits: hidden names, {@code node_modules} and key material are
 * never shown, symlinks are followed only while their real path stays in the checkout.
 */
final class WorkspaceTree {

    static final int DEFAULT_PAGE = 500;
    static final int MAX_PAGE = 1000;

    /** A directory is read whole to sort it; past this many entries the page says it is partial. */
    static final int MAX_DIR_ENTRIES = 50_000;

    /** How many single-child directories one row may fold together (a Java package chain). */
    static final int MAX_CHAIN = 16;

    static final int DEFAULT_MATCHES = 200;
    static final int MAX_MATCHES = 1000;

    /** Entries one find visits before it stops and says so. */
    static final int MAX_FIND_VISITS = 200_000;

    static final long FIND_DEADLINE_MILLIS = 3_000;

    private WorkspaceTree() {}

    /**
     * One row. {@code name} is what the row shows, relative to the listed directory: a folded chain
     * of single-child directories reads {@code main/java/cc/jumpkick}. {@code path} is the deepest
     * directory or the file, relative to the checkout; it is what the next {@link #list} asks for.
     * {@code lang} is null for a directory. {@code output} marks a module's build output; {@code
     * empty} a directory with nothing to show.
     */
    record Entry(
            String name, String path, boolean dir, @Nullable String lang, boolean output, boolean empty) {}

    /**
     * One page of a directory: {@code total} entries in all, {@code next} the cursor of the page
     * after this one (null on the last), {@code partial} when the directory held more than {@link
     * #MAX_DIR_ENTRIES} and only that many were read.
     */
    record Page(
            Path root,
            String path,
            List<Entry> entries,
            int total,
            @Nullable String next,
            boolean partial) {}

    sealed interface ListResult {
        record Ok(Page page) implements ListResult {}

        record BadRequest(String error) implements ListResult {}

        record NotFound() implements ListResult {}
    }

    record Found(String path, String lang) {}

    /** {@code more}: matches past the limit exist; {@code partial}: the walk stopped before the end. */
    record Matches(Path root, List<Found> files, boolean more, boolean partial) {}

    /** A directory waiting in {@link #find}'s walk, and whether it is under build output. */
    private record Queued(String rel, boolean output) {}

    /** A child of a directory, before paging: the name, and whether it is a directory. */
    private record Child(String name, Path path, boolean dir) {}

    /**
     * The page of directory {@code rawRel} (blank for the checkout root) that starts at {@code
     * cursor}: directories first, then files, each side ordered case-insensitively.
     */
    static ListResult list(Path root, @Nullable String rawRel, @Nullable String cursor, int limit) {
        Path absRoot = root.toAbsolutePath().normalize();
        Path realRoot = realOrSelf(absRoot);
        String rel = "";
        if (rawRel != null && !rawRel.isBlank()) {
            rel = WorkspaceFileAccess.normalizeRel(rawRel);
            if (rel == null) return new ListResult.BadRequest("illegal path");
            if (!WorkspaceFileAccess.servable(absRoot, rel)) return new ListResult.NotFound();
        }
        int offset;
        try {
            offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            return new ListResult.BadRequest("illegal cursor");
        }
        if (offset < 0) return new ListResult.BadRequest("illegal cursor");
        int size = limit <= 0 ? DEFAULT_PAGE : Math.min(limit, MAX_PAGE);
        Path dir = rel.isEmpty() ? absRoot : absRoot.resolve(rel).normalize();
        if (!dir.startsWith(absRoot) || !Files.isDirectory(dir) || !contained(dir, realRoot)) {
            return new ListResult.NotFound();
        }
        boolean output = underOutput(absRoot, rel);
        List<Child> children = new ArrayList<>();
        boolean partial;
        try {
            partial = readChildren(dir, realRoot, children, MAX_DIR_ENTRIES);
        } catch (IOException e) {
            return new ListResult.NotFound();
        }
        children.sort(Comparator.comparing((Child c) -> !c.dir())
                .thenComparing(Child::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Child::name));
        int from = Math.min(offset, children.size());
        int to = Math.min(from + size, children.size());
        List<Entry> entries = new ArrayList<>(to - from);
        for (Child c : children.subList(from, to)) {
            String childRel = rel.isEmpty() ? c.name() : rel + "/" + c.name();
            entries.add(
                    c.dir()
                            ? folded(absRoot, realRoot, c, childRel, output)
                            : new Entry(
                                    c.name(), childRel, false, WorkspaceFileAccess.langOf(c.name()), output, false));
        }
        String next = to < children.size() ? String.valueOf(to) : null;
        return new ListResult.Ok(new Page(absRoot, rel, List.copyOf(entries), children.size(), next, partial));
    }

    /**
     * A directory row, with any chain of single-child directories below it folded in: while the
     * directory shows exactly one entry and that entry is a directory, the row moves down to it.
     */
    private static Entry folded(Path absRoot, Path realRoot, Child top, String rel, boolean parentOutput) {
        StringBuilder name = new StringBuilder(top.name());
        Path dir = top.path();
        String path = rel;
        boolean output = parentOutput || WorkspaceFileAccess.isOutputDir(dir);
        boolean empty = false;
        for (int i = 0; i < MAX_CHAIN; i++) {
            List<Child> peek = new ArrayList<>(2);
            try {
                readChildren(dir, realRoot, peek, 2);
            } catch (IOException unreadable) {
                break;
            }
            if (peek.isEmpty()) {
                empty = true;
                break;
            }
            if (peek.size() > 1 || !peek.getFirst().dir()) break;
            Child only = peek.getFirst();
            name.append('/').append(only.name());
            path = path + "/" + only.name();
            dir = only.path();
            output = output || WorkspaceFileAccess.isOutputDir(dir);
        }
        return new Entry(name.toString(), path, true, null, output, empty);
    }

    /**
     * The shown children of {@code dir}, up to {@code max}: true when more remained unread. A
     * symlink counts as what it points at, and only while that stays in the checkout.
     */
    private static boolean readChildren(Path dir, Path realRoot, List<Child> out, int max) throws IOException {
        boolean[] more = {false};
        // The walk does not follow a link it starts at: a symlinked directory is read at its target.
        PathUtil.forEachChild(dir.toRealPath(), (entry, attrs) -> {
            Child c = child(entry, attrs, realRoot);
            if (c == null) return true;
            if (out.size() >= max) {
                more[0] = true;
                return false;
            }
            out.add(c);
            return true;
        });
        return more[0];
    }

    /** {@code attrs} are the entry's own, links not followed, as the listing read them. */
    private static @Nullable Child child(Path entry, BasicFileAttributes attrs, Path realRoot) {
        Path fileName = entry.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        if (!WorkspaceFileAccess.visibleName(name)) return null;
        if (attrs.isSymbolicLink()) {
            if (!contained(entry, realRoot)) return null;
            if (Files.isDirectory(entry)) return new Child(name, entry, true);
            if (!Files.isRegularFile(entry)) return null;
        } else if (attrs.isDirectory()) {
            return new Child(name, entry, true);
        } else if (!attrs.isRegularFile()) {
            return null;
        }
        return WorkspaceFileAccess.isKeyMaterial(name) ? null : new Child(name, entry, false);
    }

    /** Whether any directory on {@code rel}, from the root down, is a module's build output. */
    private static boolean underOutput(Path absRoot, String rel) {
        if (rel.isEmpty()) return false;
        Path at = absRoot;
        for (String seg : rel.split("/")) {
            at = at.resolve(seg);
            if (WorkspaceFileAccess.isOutputDir(at)) return true;
        }
        return false;
    }

    /**
     * Files whose checkout-relative path contains every space-separated term of {@code query},
     * case-insensitively. Breadth-first, sources before build output, so the shallow hand-written
     * file comes first. Files whose own name matches rank above those that match only by a
     * directory. At most {@code limit} come back; the walk stops at {@link #MAX_FIND_VISITS}
     * entries or {@link #FIND_DEADLINE_MILLIS}, and says so.
     */
    static Matches find(Path root, String query, int limit) {
        Path absRoot = root.toAbsolutePath().normalize();
        Path realRoot = realOrSelf(absRoot);
        int cap = limit <= 0 ? DEFAULT_MATCHES : Math.min(limit, MAX_MATCHES);
        List<String> terms = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!t.isEmpty()) terms.add(t);
        if (terms.isEmpty()) return new Matches(absRoot, List.of(), false, false);
        List<Found> byName = new ArrayList<>();
        List<Found> byPath = new ArrayList<>();
        boolean more = false;
        boolean partial = false;
        long deadline = Clock.SYSTEM.nanos() + FIND_DEADLINE_MILLIS * 1_000_000;
        int visits = 0;
        Set<Path> visited = new HashSet<>();
        visited.add(realRoot);
        Deque<Queued> sources = new ArrayDeque<>();
        Deque<Queued> outputs = new ArrayDeque<>();
        sources.add(new Queued("", false));
        while (!sources.isEmpty() || !outputs.isEmpty()) {
            Queued q = sources.isEmpty() ? outputs.poll() : sources.poll();
            String rel = q.rel();
            Path dir = rel.isEmpty() ? absRoot : absRoot.resolve(rel);
            List<Child> children = new ArrayList<>();
            try {
                readChildren(dir, realRoot, children, MAX_DIR_ENTRIES);
            } catch (IOException unreadable) {
                continue;
            }
            for (Child c : children) {
                if (++visits > MAX_FIND_VISITS || Clock.SYSTEM.nanos() > deadline) {
                    partial = true;
                    break;
                }
                String childRel = rel.isEmpty() ? c.name() : rel + "/" + c.name();
                if (c.dir()) {
                    if (visited.add(realOrSelf(c.path()))) {
                        boolean output = q.output() || WorkspaceFileAccess.isOutputDir(c.path());
                        (output ? outputs : sources).add(new Queued(childRel, output));
                    }
                    continue;
                }
                String lowerPath = childRel.toLowerCase(Locale.ROOT);
                if (!containsAll(lowerPath, terms)) continue;
                boolean nameHit = containsAll(c.name().toLowerCase(Locale.ROOT), terms);
                List<Found> tier = nameHit ? byName : byPath;
                if (tier.size() >= cap) {
                    more = true;
                    continue;
                }
                tier.add(new Found(childRel, WorkspaceFileAccess.langOf(c.name())));
            }
            if (partial) break;
        }
        LinkedHashSet<Found> ranked = new LinkedHashSet<>(byName);
        ranked.addAll(byPath);
        List<Found> files = new ArrayList<>(ranked);
        if (files.size() > cap) {
            files = files.subList(0, cap);
            more = true;
        }
        return new Matches(absRoot, List.copyOf(files), more, partial);
    }

    private static boolean containsAll(String haystack, List<String> terms) {
        for (String t : terms) if (!haystack.contains(t)) return false;
        return true;
    }

    private static boolean contained(Path p, Path realRoot) {
        try {
            return p.toRealPath().startsWith(realRoot);
        } catch (IOException broken) {
            return false;
        }
    }

    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }
}
