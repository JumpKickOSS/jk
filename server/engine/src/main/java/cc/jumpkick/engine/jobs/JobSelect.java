// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleHints;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolve MCP/HTTP module names and tag lists into planner inputs. */
public final class JobSelect {

    private JobSelect() {}

    /**
     * Split {@code affected:<ref>} tokens from {@code -m} selectors and resolve via
     * {@link ModuleSelection#resolveOptional}. {@code null} when {@code tokens} is empty.
     */
    public static ModuleSelection.Result resolveTokens(Path entryDir, JkBuild entry, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;
        List<String> raw = new ArrayList<>();
        String affected = null;
        for (String d : tokens) {
            if (d == null || d.isBlank()) continue;
            if (d.startsWith("affected:")) affected = d.substring("affected:".length());
            else raw.add(d);
        }
        if (raw.isEmpty() && affected == null) return null;
        return ModuleSelection.resolveOptional(entryDir, entry, raw.isEmpty() ? null : String.join(",", raw), affected);
    }

    /** User-selected module dirs only (canonical). {@code null} when the caller did not filter. */
    public static Set<Path> selected(Path entryDir, JkBuild entry, List<String> modules) {
        if (modules == null || modules.isEmpty()) return null;
        ModuleSelection.Result sel;
        boolean tokenShape = false;
        for (String m : modules) {
            if (m != null && m.startsWith("affected:")) {
                tokenShape = true;
                break;
            }
        }
        if (tokenShape || modules.stream().anyMatch(m -> m != null && m.contains(","))) {
            sel = resolveTokens(entryDir, entry, modules);
        } else {
            sel = ModuleSelection.resolve(entryDir, entry, String.join(",", modules));
        }
        if (sel == null) return null;
        if (!sel.ok()) throw new IllegalArgumentException(sel.errorMessage());
        Set<Path> out = new LinkedHashSet<>();
        for (Path p : sel.moduleDirs()) out.add(BuildGraph.canonicalPath(p));
        return Set.copyOf(out);
    }

    /**
     * Selected module dirs plus build prereqs (canonical). {@code null} when the caller did not
     * filter — the engine forecasts dirtiness itself.
     */
    public static Set<Path> dirtyHint(Path entryDir, JkBuild entry, List<String> modules) {
        Set<Path> selected = selected(entryDir, entry, modules);
        if (selected == null) return null;
        return ModuleHints.withPrereqs(entryDir, entry, selected);
    }

    /**
     * Tag/suite filter. Any explicit list means the selection is final ({@code tagsResolved=true})
     * so per-module {@code [test]} tags are not folded back in.
     */
    public static TestSelection testSelection(List<String> includeTags, List<String> excludeTags, List<String> suites) {
        List<String> inc = includeTags == null ? List.of() : includeTags;
        List<String> exc = excludeTags == null ? List.of() : excludeTags;
        List<String> su = suites == null ? List.of() : suites;
        if (inc.isEmpty() && exc.isEmpty() && su.isEmpty()) return TestSelection.DEFAULT;
        return TestSelection.of(su, false, inc, exc, true);
    }
}
