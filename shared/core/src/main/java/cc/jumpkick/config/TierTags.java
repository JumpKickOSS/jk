// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The tags and the every-suite switch a test run takes from its manifests, before any CLI flag: the
 * one answer the CLI, MCP and BSP runs, the {@code --class} tier hints and {@code jk info} share.
 *
 * <p>Layers, each replacing a list only when it speaks for it: the workspace root's {@code [test]
 * include-tags} / {@code exclude-tags}, then the active {@code [profiles.<name>]} resolved through its
 * {@code inherits} chain (a child's list replaces its parent's when the child sets that key; {@code
 * all-suites} is on when any profile in the chain sets it). The root wins outright: a member's own
 * {@code [test]} lists are read only when no root layer spoke. A standalone project is its own root.
 *
 * <p>Read with {@link TomlScan}, so the engine-free CLI resolves exactly as the engine does.
 *
 * @param includeSet some layer set {@code include-tags} (an empty list then clears)
 * @param excludeSet some layer set {@code exclude-tags} (an empty list then clears)
 */
public record TierTags(
        List<String> includeTags, List<String> excludeTags, boolean includeSet, boolean excludeSet, boolean allSuites) {

    /** No layer spoke: no tag filter, the default suite. */
    public static final TierTags NONE = new TierTags(List.of(), List.of(), false, false, false);

    public TierTags {
        includeTags = List.copyOf(includeTags);
        excludeTags = List.copyOf(excludeTags);
    }

    /** True when some layer set either tag list, so the lists are final even when empty. */
    public boolean spoke() {
        return includeSet || excludeSet;
    }

    /**
     * What a run of the module at {@code dir} takes under {@code profile} ({@code null}: none
     * applies): the workspace root's layers, or the module's own {@code [test]} lists when no root
     * layer set a tag list. A profile's {@code all-suites} holds either way.
     */
    public static TierTags of(Path dir, @Nullable String profile) {
        Path root = rootOf(dir);
        TierTags fromRoot = ofRoot(root, profile);
        if (fromRoot.spoke() || sameDir(root, dir)) return fromRoot;
        TierTags own = baseline(dir);
        return own.spoke() ? own.withAllSuites(fromRoot.allSuites) : fromRoot;
    }

    /** The root's layers alone: its {@code [test]} lists, then {@code profile} through {@code inherits}. */
    public static TierTags ofRoot(Path root, @Nullable String profile) {
        TierTags base = baseline(root);
        if (profile == null || profile.isBlank()) return base;
        return base.under(profile(root, profile));
    }

    /**
     * {@code [profiles.<name>]} of the root manifest alone, its {@code inherits} chain merged: a list
     * is set when the profile or an ancestor sets it, the nearest setter's list winning. A profile the
     * manifest does not declare contributes nothing. A cycle, or an {@code all-suites} that is not a
     * boolean, is an {@link IllegalArgumentException}.
     */
    public static TierTags profile(Path root, String name) {
        Path manifest = ManifestPaths.manifestIn(root);
        List<TomlScan> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        @Nullable String at = name;
        while (at != null) {
            if (!seen.add(at)) {
                throw new IllegalArgumentException(
                        "cycle in profile inheritance at `" + at + "` (chain: " + seen + ")");
            }
            String key = "profiles." + at;
            TomlScan scan = TomlScan.scan(
                    manifest, key + ".include-tags", key + ".exclude-tags", key + ".all-suites", key + ".inherits");
            chain.add(scan);
            String parent = scan.get(key + ".inherits");
            at = parent == null || parent.isBlank() ? null : parent.strip();
        }
        TierTags merged = NONE;
        List<String> names = List.copyOf(seen);
        for (int i = chain.size() - 1; i >= 0; i--) {
            merged = merged.under(layer(chain.get(i), "profiles." + names.get(i)));
        }
        return merged;
    }

    /** The workspace root {@code dir} belongs to, or {@code dir} itself when it is standalone. */
    public static Path rootOf(Path dir) {
        return WorkspaceScan.owningRoot(dir).orElse(dir);
    }

    /** {@code [test] include-tags} / {@code exclude-tags} of the manifest in {@code dir} alone. */
    private static TierTags baseline(Path dir) {
        TomlScan scan = TomlScan.scan(ManifestPaths.manifestIn(dir), "test.include-tags", "test.exclude-tags");
        return layer(scan, "test");
    }

    private static TierTags layer(TomlScan scan, String table) {
        String inc = table + ".include-tags";
        String exc = table + ".exclude-tags";
        return new TierTags(
                scan.stringArray(inc),
                scan.stringArray(exc),
                scan.hasKey(inc),
                scan.hasKey(exc),
                table.startsWith("profiles.") && allSuites(scan, table + ".all-suites"));
    }

    /** {@code over} laid on this: each list {@code over} sets replaces this one's; either's all-suites holds. */
    private TierTags under(TierTags over) {
        return new TierTags(
                over.includeSet ? over.includeTags : includeTags,
                over.excludeSet ? over.excludeTags : excludeTags,
                includeSet || over.includeSet,
                excludeSet || over.excludeSet,
                allSuites || over.allSuites);
    }

    private TierTags withAllSuites(boolean on) {
        return new TierTags(includeTags, excludeTags, includeSet, excludeSet, allSuites || on);
    }

    /** {@code all-suites}: false when absent; anything but a TOML boolean is refused. */
    private static boolean allSuites(TomlScan scan, String key) {
        String value = scan.get(key);
        if (value == null) return false;
        return switch (value.strip()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false, not " + value.strip());
        };
    }

    private static boolean sameDir(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
}
