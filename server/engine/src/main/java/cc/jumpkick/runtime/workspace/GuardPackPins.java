// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.guard.rules.GuardPacks;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.host.Log;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rule packs {@code [guards] extends} pins in a lock root's {@code jk-guards.toml}, found the
 * way the lock finds them: a version is available when the first-party shelf stages it or the
 * project's repositories publish it.
 */
final class GuardPackPins {

    /** The table a pack's rewrite and report row name. */
    static final String TABLE = "guards.extends";

    private GuardPackPins() {}

    /** Every parseable {@code group:artifact:version} the root's rules file extends. */
    static List<GuardPacks.Coordinate> of(Path root) {
        List<GuardPacks.Coordinate> out = new ArrayList<>();
        try {
            for (String declared : GuardPacks.declared(root)) {
                GuardPacks.Coordinate c = GuardPacks.Coordinate.parse(declared);
                if (c != null) out.add(c);
            }
        } catch (IOException e) {
            Log.debug("guard packs: " + GuardsPresence.RULES_FILE + " unreadable", e);
        }
        return out;
    }

    /** The rules file the packs are declared in. */
    static Path file(Path root) {
        return GuardsPresence.rulesFile(root);
    }

    /**
     * Every version of {@code pack} the first-party shelves under {@code storeRoot} stage or the
     * repositories publish, the repositories' list memoised in {@code memo}.
     */
    static List<String> available(
            GuardPacks.Coordinate pack, Path storeRoot, RepoGroup repos, Map<String, List<String>> memo) {
        Set<String> versions = new LinkedHashSet<>();
        for (RepoArtifactStore store : RepoArtifactStore.firstParty(storeRoot)) {
            versions.addAll(store.versions(pack.group(), pack.artifact()));
        }
        versions.addAll(ManifestUpdates.available(pack.ga(), repos, memo));
        return versions.stream().sorted(Versions::compare).toList();
    }

    /**
     * {@code text}, a rules file, with the quoted {@code pack} coordinate moved to {@code to}.
     *
     * @throws IllegalStateException when the file does not spell the coordinate as one string
     */
    static String rewrite(String text, GuardPacks.Coordinate pack, String to) {
        Matcher m =
                Pattern.compile("([\"'])" + Pattern.quote(pack.gav()) + "\\1").matcher(text);
        if (!m.find()) throw new IllegalStateException(TABLE + " does not write " + pack.gav());
        String quote = m.group(1);
        String moved = quote + pack.ga() + ":" + to + quote;
        return text.substring(0, m.start()) + moved + text.substring(m.end());
    }
}
