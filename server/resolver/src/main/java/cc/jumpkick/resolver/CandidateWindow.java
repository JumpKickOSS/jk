// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.version.Versions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The few versions of a package the solver is offered first, before it widens to the full history. */
final class CandidateWindow {

    private CandidateWindow() {}

    /** How many candidates the compact window keeps. */
    static final int SIZE = 4;

    /**
     * Cap the candidate list while preserving the soft-prefer front and, critically, keeping
     * something <em>stable</em> in the window.
     *
     * <p>The cap exists so PubGrub does not thrash on 80-version histories. Taking simply
     * the highest four, though, starves {@link
     * cc.jumpkick.resolver.pubgrub.AllowedSet#choosePreferred} of any stable candidate whenever a
     * project publishes four or more pre-releases above its latest release. jackson-annotations sits
     * at 3.0-rc5..rc2 above a stable 2.22, so a caret on 2.22 resolved to <b>3.0-rc5</b>.
     * The stable preference downstream was correct all along — it was simply never offered a stable.
     *
     * <p>So the highest <em>stable</em> versions fill the window first and pre-releases take only the
     * slots left over (which is what keeps a project that has never cut a stable release resolvable).
     * A constraint that genuinely needs a pre-release still resolves: every stable candidate fails it,
     * the window is exhausted, and the solver widens to the unfiltered history via {@link
     * MavenPackageSource#expandedVersions} — the path that exists for exactly this shape of miss. That keeps
     * transitive POM edges pinned to milestone builds working, since those arrive as constraints
     * rather than as manifest selectors.
     */
    static List<String> of(List<String> sortedHighestFirst) {
        if (sortedHighestFirst.size() <= SIZE) {
            // The full history is the universe, so the downstream stable preference can already see
            // a stable candidate. Nothing to protect against here.
            return sortedHighestFirst;
        }

        // A lock/BOM soft-prefer sits at index 0 without necessarily being the highest version, and
        // it must survive the cap even when it is itself a pre-release: an explicit pin outranks this
        // policy.
        String front = sortedHighestFirst.get(0);
        String naturalMax = highestOf(sortedHighestFirst);
        boolean pinnedFront = !front.equals(naturalMax);

        LinkedHashSet<String> picked = new LinkedHashSet<>();
        if (pinnedFront) {
            picked.add(front);
            // Keep the natural max too. AllowedSet infers "this front is a pin, take it
            // unconditionally" by finding some higher version in the universe — drop that and a
            // pre-release pin silently loses to a lower stable.
            picked.add(naturalMax);
        }
        for (String v : sortedHighestFirst) {
            if (picked.size() >= SIZE) break;
            if (Versions.isStable(v)) picked.add(v);
        }
        for (String v : sortedHighestFirst) {
            if (picked.size() >= SIZE) break;
            picked.add(v);
        }

        // Restore highest-first order (choosePreferred walks the universe in index order), then put
        // any pin back at the front where preferFirst/preferBom left it.
        List<String> out = new ArrayList<>(picked);
        out.sort((a, b) -> Versions.compare(b, a));
        if (pinnedFront) {
            out.remove(front);
            out.add(0, front);
        }
        return List.copyOf(out);
    }

    /**
     * Cap to the highest candidates with no stability policy at all — the window for a {@code
     * snapshot} package, which asked for the newest thing published whatever it is.
     */
    static List<String> highest(List<String> sortedHighestFirst) {
        if (sortedHighestFirst.size() <= SIZE) return sortedHighestFirst;
        List<String> out = new ArrayList<>(SIZE);
        for (String v : sortedHighestFirst) {
            if (out.contains(v)) continue;
            out.add(v);
            if (out.size() == SIZE) break;
        }
        return List.copyOf(out);
    }

    /** The highest version under Maven ordering, or null for an empty list. */
    static @Nullable String highestOf(List<String> versions) {
        String max = null;
        for (String v : versions) {
            if (max == null || Versions.compare(v, max) > 0) max = v;
        }
        return max;
    }
}
