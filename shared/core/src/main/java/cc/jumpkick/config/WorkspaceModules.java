// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The one reading of {@code [workspace] modules}: literal module paths and single-segment globs
 * ({@code libs/*}), expanded against the workspace root to the directories that hold a
 * {@code jk.toml}.
 *
 * <p>Every consumer of the list — the loader, the root finders, the affected-module mapper, the
 * runtime classpath — goes through here, so a glob means the same set of modules everywhere. A
 * wildcard matches one path segment; {@code **} is refused rather than guessed at. Within one glob
 * the matches are sorted, so the module order is a function of the tree, not of the directory
 * listing; across entries the declared order is kept and duplicates collapse to their first mention.
 */
public final class WorkspaceModules {

    private WorkspaceModules() {}

    /** True when {@code entry} is a pattern rather than a path. */
    public static boolean isGlob(String entry) {
        return entry.indexOf('*') >= 0 || entry.indexOf('?') >= 0 || entry.indexOf('[') >= 0;
    }

    /**
     * Module paths relative to {@code root}, literals as written and globs expanded. A glob that
     * matches no directory with a {@code jk.toml} is an error naming the pattern: an entry that
     * selects nothing is a typo, not an empty set.
     */
    public static List<String> expand(Path root, List<String> entries) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            String normalized = entry.replace('\\', '/');
            if (!isGlob(normalized)) {
                out.add(normalized);
                continue;
            }
            if (normalized.contains("**")) {
                throw new JkBuildParseException("[workspace] modules entry `" + entry
                        + "`: `**` is not supported — a wildcard matches one path segment; list the segments");
            }
            Set<String> matches = new TreeSet<>();
            collect(root, root, normalized.split("/"), 0, matches);
            if (matches.isEmpty()) {
                throw new JkBuildParseException(
                        "[workspace] modules entry `" + entry + "` matches no directory with a jk.toml");
            }
            out.addAll(matches);
        }
        return List.copyOf(out);
    }

    /**
     * The member directories the manifest at {@code root} lists under {@code [workspace] modules},
     * globs expanded, as {@code root.resolve(rel)} normalized, in declared order; empty when
     * {@code root} has no manifest or no list. A key scan of the manifest, not the full parser: the
     * list is literal text, the scan costs milliseconds where the parser's first read of a
     * workspace costs hundreds, and a manifest the parser would reject still names its members.
     * Quiet on purpose — a glob that matches nothing contributes no member rather than failing a
     * caller that is reading the tree, not validating the manifest.
     */
    public static List<Path> memberDirs(Path root) {
        Path manifest = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(manifest)) return List.of();
        List<String> rels = TomlScan.scan(manifest, "workspace.modules").stringArray("workspace.modules");
        if (rels.isEmpty()) return List.of();
        List<String> expanded;
        try {
            expanded = expand(root, rels);
        } catch (RuntimeException e) {
            expanded = rels.stream().filter(r -> r != null && !isGlob(r)).toList();
        }
        List<Path> out = new ArrayList<>();
        for (String rel : expanded) {
            if (rel != null && !rel.isBlank()) out.add(root.resolve(rel).normalize());
        }
        return List.copyOf(out);
    }

    /**
     * True when {@code entries} lists the module at {@code rel} (a {@code /}-separated path relative
     * to the root): a literal equal to it, or a glob it matches segment by segment. Lexical — no
     * directory is read — so a root finder can ask it for every ancestor cheaply.
     */
    public static boolean lists(List<String> entries, String rel) {
        String wanted = rel.replace('\\', '/');
        for (String entry : entries) {
            if (entry == null) continue;
            String normalized = entry.replace('\\', '/');
            if (normalized.equals(wanted)) return true;
            if (isGlob(normalized) && !normalized.contains("**") && globMatches(normalized, wanted)) return true;
        }
        return false;
    }

    private static boolean globMatches(String pattern, String rel) {
        String[] segments = pattern.split("/");
        String[] parts = rel.split("/");
        if (segments.length != parts.length) return false;
        for (int i = 0; i < segments.length; i++) {
            if (!matcher(segments[i]).matches(Path.of(parts[i]))) return false;
        }
        return true;
    }

    /**
     * Compiled matchers by glob segment. A root finder asks for one per segment per ancestor per
     * call, and the segments a tree uses are a handful of fixed strings, so compiling each once
     * removes a regex compile from every workspace lookup.
     */
    private static final ConcurrentMap<String, PathMatcher> MATCHERS = new ConcurrentHashMap<>();

    private static PathMatcher matcher(String segment) {
        return MATCHERS.computeIfAbsent(segment, s -> FileSystems.getDefault().getPathMatcher("glob:" + s));
    }

    private static void collect(Path root, Path dir, String[] segments, int index, Set<String> out) {
        if (index == segments.length) {
            if (Files.isRegularFile(dir.resolve(ManifestPaths.MANIFEST))) {
                out.add(root.relativize(dir).toString().replace('\\', '/'));
            }
            return;
        }
        String segment = segments[index];
        if (!isGlob(segment)) {
            collect(root, dir.resolve(segment), segments, index + 1, out);
            return;
        }
        PathMatcher matcher = matcher(segment);
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                Path name = child.getFileName();
                if (attrs.isDirectory() && name != null && !name.toString().startsWith(".") && matcher.matches(name)) {
                    collect(root, child, segments, index + 1, out);
                }
                return true;
            });
        } catch (IOException unreadable) {
            // A directory this process cannot list contributes nothing; the empty-match error
            // downstream names the pattern.
        }
    }
}
